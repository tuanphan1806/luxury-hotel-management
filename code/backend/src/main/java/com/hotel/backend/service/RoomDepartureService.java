package com.hotel.backend.service;

import com.hotel.backend.constant.*;
import com.hotel.backend.dto.request.PaymentRequest;
import com.hotel.backend.dto.request.RoomFolioSelection;
import com.hotel.backend.dto.response.PaymentResponse;
import com.hotel.backend.dto.response.RoomFolio;
import com.hotel.backend.entity.*;
import com.hotel.backend.exception.*;
import com.hotel.backend.repository.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RoomDepartureService {
    private final ReservationRepository reservations;
    private final ReservationRoomRepository slots;
    private final RoomRepository rooms;
    private final GuestRepository guests;
    private final ReservationServiceOrderRepository orders;
    private final PricingV2LifecycleService pricing;
    private final PaymentRefundService refunds;
    private final PaymentTransactionRepository payments;
    private final PaymentService cashier;
    private final ReservationAuditService audit;
    private final ReservationService stays;
    private final LegacyRoomDeparturePricing legacy;
    private final ReservationAddOnService addOns;
    private final RoomFolioPayments folioPayments;

    public record RoomOption(Long id, String name) {}
    public record Options(List<RoomOption> occupied, List<RoomFolio.ServiceLine> services, long remainingFee,
                          BigDecimal groupDiscount, BigDecimal groupTax, List<RoomFolio> folios,
                          RoomFolioPayments.Progress collection) {}
    public record Command(@NotNull RoomBillingMode billingMode, @NotNull @Valid RoomFolioSelection selection,
                          @NotNull @PositiveOrZero Long expectedTotal, @NotNull @PositiveOrZero Long cashReceived) {}
    public record Quote(BigDecimal roomCharge, BigDecimal extraCharge, BigDecimal serviceCharge,
                        long additionalFee, long total, long availableCredit, long minimumCredit, long maximumCredit, long cashDue,
                        long cashCollected, long qrCollected) {}

    @Transactional(readOnly = true)
    public Options options(Long id) {
        Reservation reservation = reservations.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        var allSlots = slots.findAllByReservationId(id);
        var services = orders.findDetailedByReservationId(id);
        return new Options(allSlots.stream().filter(slot -> slot.getStatus() == AssignStatus.CHECKED_IN)
                .sorted(Comparator.comparing(ReservationRoom::getId))
                .map(slot -> new RoomOption(slot.getId(), slot.getRoom().getRoomName())).toList(),
                availableServices(allSlots, services), remainingFee(reservation, allSlots),
                reservation.getDiscountAmount(), reservation.getTaxAmount(), RoomFolio.issued(allSlots), folioPayments.current(id));
    }

    private long remainingFee(Reservation reservation, List<ReservationRoom> all) {
        long total = reservation.getCheckoutAdditionalFee() == null ? 0 : reservation.getCheckoutAdditionalFee().longValueExact();
        return total - all.stream().map(ReservationRoom::getDeparture).filter(Objects::nonNull)
                .mapToLong(RoomDeparture::getAdditionalFee).sum();
    }

    private List<RoomFolio.ServiceLine> availableServices(List<ReservationRoom> all, List<ReservationServiceOrder> orders) {
        Map<Long, RoomFolio.ServiceLine> remaining = new LinkedHashMap<>();
        orders.stream().filter(s -> s.getStatus() == ReservationServiceStatus.FULFILLED).forEach(s ->
                remaining.put(s.getId(), new RoomFolio.ServiceLine(s.getId(), s.getServiceNameSnapshot(),
                        s.getBillableQuantity(), s.getTotalPrice())));
        for (var slot : all) {
            if (slot.getDeparture() == null) continue;
            for (var used : slot.getDepartureServices()) {
                remaining.computeIfPresent(used.getOrderId(), (id, line) -> new RoomFolio.ServiceLine(id,
                        line.name(), line.quantity() - used.getQuantity(), line.amount().subtract(used.getAmount())));
            }
        }
        return remaining.values().stream().filter(s -> s.quantity() > 0).toList();
    }

    @Transactional(readOnly = true)
    public Quote preview(Long id, List<Long> slotIds, RoomFolioSelection selection, RoomBillingMode mode) {
        Reservation reservation = reservations.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        var calculation = prepare(reservation, slotIds, selection, LocalDateTime.now());
        checkSettlementAllowed(id, calculation, mode == null ? RoomBillingMode.SEPARATE : mode);
        return calculation.quote();
    }

    private record Charge(ReservationRoom slot, int guests, int extras, BigDecimal room,
                          BigDecimal extra, BigDecimal includedLate) {}
    private record Calculation(List<Charge> charges, List<RoomServiceAllocation> services, List<Guest> guests,
                               boolean lastOccupied, long previouslyIssued, long remainingGroupTotal, Quote quote, RoomFolioPayments.Progress collection) {}

    /** The remaining part of one pooled rate line; consuming a share carries VND rounding to the next room. */
    private static class LineBalance {
        int rooms, extras;
        final int included;
        BigDecimal room, extra;
        LineBalance(PricingV2LifecycleService.LineProjection line) {
            var departed = DepartedRoomCharges.from(line.reservationLine());
            rooms = departed.totalRooms() - departed.released();
            extras = Math.max(0, line.breakdown().extraGuestCount() - departed.extraGuests());
            included = line.latest().getIncludedGuests();
            room = line.finalRoomCharge().subtract(departed.roomCharge());
            extra = line.finalExtraGuestCharge().subtract(departed.extraCharge());
        }
        Charge take(ReservationRoom slot, int guests) {
            int units = Math.min(extras, Math.max(0, guests - included));
            BigDecimal share = DepartedRoomCharges.share(room, 1, rooms);
            BigDecimal guestShare = rooms == 1 ? extra : extras > 0 ? DepartedRoomCharges.share(extra, units, extras)
                    : DepartedRoomCharges.share(extra, 1, rooms);
            rooms--;
            extras -= units;
            room = room.subtract(share);
            extra = extra.subtract(guestShare);
            return new Charge(slot, guests, units, share, guestShare, BigDecimal.ZERO);
        }
    }

    private List<RoomServiceAllocation> allocateServices(Reservation r, List<ReservationRoom> all, RoomFolioSelection selection) {
        if (selection.additionalFee() > remainingFee(r, all)) throw invalid("Phụ phí vượt phần chưa phân bổ trên đơn.");
        Map<Long, RoomFolio.ServiceLine> choices = new HashMap<>();
        availableServices(all, orders.findDetailedByReservationId(r.getId())).forEach(s -> choices.put(s.id(), s));
        List<RoomServiceAllocation> result = new ArrayList<>();
        for (var requested : selection.services()) {
            var line = choices.remove(requested.orderId());
            if (line == null || requested.quantity() < 1 || requested.quantity() > line.quantity()) {
                throw invalid("Số lượng dịch vụ vượt phần đã phục vụ và chưa phân bổ trên đơn.");
            }
            result.add(new RoomServiceAllocation(line.id(), line.name(), requested.quantity(),
                    DepartedRoomCharges.share(line.amount(), requested.quantity(), line.quantity())));
        }
        return result;
    }

    private Calculation prepare(Reservation r, List<Long> slotIds, RoomFolioSelection selection, LocalDateTime at) {
        var collection = folioPayments.current(r.getId());
        folioPayments.checkSelection(collection, new RoomFolioCollection.Pick(slotIds, selection));
        if (r.getStatus() != ReservationStatus.CHECKED_IN) throw invalid("Đơn phải đang lưu trú.");
        var all = slots.findAllByReservationId(r.getId());
        if (all.size() < 2) throw invalid("Trả từng căn chỉ dành cho đơn có từ hai căn.");
        var wanted = new HashSet<>(slotIds);
        var selected = all.stream().filter(s -> wanted.contains(s.getId())).sorted(Comparator.comparing(ReservationRoom::getId)).toList();
        if (selected.isEmpty() || selected.size() != slotIds.size()) throw invalid("Danh sách căn không thuộc đơn hoặc bị trùng.");
        if (selected.stream().anyMatch(s -> s.getStatus() != AssignStatus.CHECKED_IN || s.getDeparture() != null || s.getRoom() == null)) {
            throw invalid("Một căn đã trả hoặc chưa nhận. Hãy tải lại danh sách.");
        }
        var services = allocateServices(r, all, selection);
        var leavingGuests = guests.findAllByReservationId(r.getId()).stream()
                .filter(g -> g.getCheckedOutAt() == null && wanted.contains(g.getReservationRoom().getId())).toList();
        Map<Long, Integer> counts = new HashMap<>();
        leavingGuests.forEach(g -> counts.merge(g.getReservationRoom().getId(), 1, Integer::sum));
        List<Charge> charges = new ArrayList<>();
        BigDecimal projected;
        if (pricing.supports(r)) {
            var projection = pricing.project(r, at);
            projected = projection.projectedTotalAmount();
            Map<Long, LineBalance> perLine = new HashMap<>();
            projection.lines().forEach(l -> perLine.put(l.reservationLine().getId(), new LineBalance(l)));
            for (var slot : selected) {
                charges.add(perLine.get(slot.getReservationRoomType().getId()).take(slot, counts.getOrDefault(slot.getId(), 0)));
            }
        } else {
            projected = legacy.project(r, at).total();
            for (var slot : selected) {
                var amount = legacy.active(r, slot.getReservationRoomType(), at);
                charges.add(new Charge(slot, counts.getOrDefault(slot.getId(), 0), 0,
                        amount.base().add(amount.late()), BigDecimal.ZERO, amount.late()));
            }
        }
        BigDecimal room = charges.stream().map(Charge::room).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal extra = charges.stream().map(Charge::extra).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal service = services.stream().map(RoomServiceAllocation::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long total = Math.addExact(room.add(extra).add(service).longValueExact(), selection.additionalFee());
        long previouslyIssued = all.stream().map(ReservationRoom::getDeparture).filter(Objects::nonNull)
                .mapToLong(d -> d.getAllocatedPayment().longValueExact()).sum();
        var funding = folioPayments.funding(r.getId(), previouslyIssued);
        long netPaid = funding.netPaid();
        long allocated = funding.netSeparate();
        long cashTender = collection == null ? 0 : collection.cash();
        long qrTender = collection == null ? 0 : collection.qr();
        long tendered = cashTender + qrTender;
        if (tendered > total || tendered > Math.max(0, netPaid - allocated)) {
            throw invalid("Khoản đã thu cần đối soát trên hóa đơn chung trước khi lập lại phiếu.");
        }
        long maximumCredit = Math.min(total - tendered, Math.max(0, netPaid - allocated - tendered));
        long minimumCredit = Math.min(maximumCredit, Math.max(0, total - tendered - Math.max(0, projected.longValueExact() - netPaid)));
        long credit = selection.useCredit() == null ? maximumCredit : selection.useCredit();
        if (credit < minimumCredit || credit > maximumCredit) {
            throw invalid("Số tiền cấn trừ vượt phần khả dụng hoặc sẽ thu thừa tiền của đoàn.");
        }
        boolean last = all.stream().filter(s -> s.getStatus() == AssignStatus.CHECKED_IN).count() == selected.size();
        var quote = new Quote(room, extra, service, selection.additionalFee(), total, credit, minimumCredit, maximumCredit,
                total - credit - tendered, cashTender, qrTender);
        return new Calculation(charges, services, leavingGuests, last, previouslyIssued, projected.longValueExact() - allocated, quote, collection);
    }

    @Transactional
    public RoomFolio depart(Long id, List<Long> slotIds, Command command, User operator, HttpServletRequest request) {
        Reservation r = reservations.findByIdForUpdate(id).orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now();
        Calculation calculation = prepare(r, slotIds, command.selection(), now);
        List<ReservationRoom> selected = calculation.charges().stream().map(Charge::slot).toList();
        for (Long roomId : selected.stream().map(s -> s.getRoom().getId()).sorted().toList()) {
            Room physical = rooms.findByIdForUpdate(roomId).orElseThrow(() -> new AppException(ErrorCode.ROOM_NOT_FOUND));
            if (physical.getStatus() != RoomStatus.CHECKED_IN) throw invalid("Trạng thái căn đã đổi. Hãy tải lại.");
        }
        var quote = calculation.quote();
        boolean separate = command.billingMode() == RoomBillingMode.SEPARATE;
        checkSettlementAllowed(id, calculation, command.billingMode());
        var collection = calculation.collection();
        if (collection != null && (collection.pendingTransactionId() != null || quote.cashDue() != 0)) {
            throw invalid("Phiếu chưa thu đủ hoặc mã QR đang chờ xác nhận.");
        }
        long cash = separate ? quote.cashDue() : 0;
        if (command.expectedTotal() != quote.total() || command.cashReceived() != cash) {
            throw invalid("Chi phí hoặc tiền đã thu vừa thay đổi. Hãy xem lại trước khi xác nhận.");
        }
        ReservationRoom first = selected.get(0);
        String folioNumber = r.getReservationCode() + "-P" + first.getId();
        Long collectionId = separate ? (collection != null ? collection.id()
                : folioPayments.start(id, new RoomFolioCollection.Pick(slotIds, command.selection()))) : null;
        if (cash > 0) {
            PaymentRequest payment = new PaymentRequest();
            payment.setBookingId(id);
            payment.setPurpose(PaymentPurpose.FINAL_PAYMENT);
            payment.setOrderInfo("Thanh toán phiếu phòng " + folioNumber);
            cashier.createCashPayment(payment, request, operator, cash, collectionId);
        }
        if (pricing.supports(r)) pricing.applyAutomatic(r, now, RateSnapshotStage.ADJUSTMENT);

        long credit = quote.availableCredit();
        long bank = quote.qrCollected();
        for (var charge : calculation.charges()) {
            var slot = charge.slot();
            boolean firstInFolio = slot == first;
            BigDecimal services = firstInFolio ? quote.serviceCharge() : BigDecimal.ZERO;
            long fee = firstInFolio ? quote.additionalFee() : 0;
            long assigned = separate ? charge.room().add(charge.extra()).add(services).longValueExact() + fee : 0;
            long prepaid = Math.min(credit, assigned);
            credit -= prepaid;
            long qr = Math.min(bank, assigned - prepaid);
            bank -= qr;
            slot.setDeparture(RoomDeparture.builder().departedAt(now).roomName(slot.getRoom().getRoomName()).folioNumber(folioNumber)
                    .billingMode(command.billingMode()).guestCount(charge.guests()).billedExtraGuests(charge.extras())
                    .roomCharge(charge.room()).includedLateFee(charge.includedLate()).extraCharge(charge.extra())
                    .serviceCharge(services).additionalFee(fee).allocatedPayment(BigDecimal.valueOf(assigned)).cashCollected(assigned - prepaid - qr).qrCollected(qr).build());
            slot.setStatus(AssignStatus.CHECKED_OUT);
            slot.getRoom().setStatus(RoomStatus.AVAILABLE);
            slot.getRoom().setCleaningStatus(CleaningStatus.DIRTY);
        }
        first.getDepartureServices().addAll(calculation.services());
        calculation.guests().forEach(g -> g.setCheckedOutAt(now));
        if (separate) folioPayments.issue(id, collectionId, quote.total(), calculation.previouslyIssued());
        if (calculation.lastOccupied()) r.setActualCheckOut(now);
        var folio = RoomFolio.from(selected);
        audit.record(r, "RESERVATION_ROOM", String.valueOf(first.getId()), ReservationAuditAction.PARTIAL_CHECK_OUT,
                "Trả căn " + folio.roomName(), null, null,
                Map.of("folio", folio.number(), "slots", slotIds, "mode", command.billingMode(), "allocated", folio.allocatedPayment(),
                        "cashCollected", cash + quote.cashCollected(), "qrCollected", quote.qrCollected(), "total", quote.total(), "selection", command.selection()),
                UUID.randomUUID().toString(), "PARTIAL_CHECK_OUT:" + first.getId());
        if (calculation.lastOccupied() && addOns.checkoutBlockers(id).isEmpty()
                && stays.getCheckoutReconciliation(id, operator).getStatus() == CheckoutReconciliationStatus.MATCHED) {
            stays.checkOut(id);
        }
        return folio;
    }

    public record Tender(@NotNull PaymentProvider provider, @NotNull @Valid RoomFolioSelection selection,
                         @NotNull @PositiveOrZero Long expectedTotal, @NotNull @Positive Long expectedDue) {}

    @Transactional
    public PaymentResponse collect(Long id, List<Long> slotIds, Tender tender, User operator, HttpServletRequest request) {
        Reservation reservation = reservations.findByIdForUpdate(id).orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        var calculation = prepare(reservation, slotIds, tender.selection(), LocalDateTime.now());
        checkSettlementAllowed(id, calculation, RoomBillingMode.SEPARATE);
        var collection = calculation.collection();
        var quote = calculation.quote();
        if (collection != null && collection.pendingTransactionId() != null) {
            if (tender.provider() == PaymentProvider.SEPAY) {
                if (!Objects.equals(collection.pendingAmount(), tender.expectedDue())
                        || quote.cashDue() != tender.expectedDue() || quote.total() != tender.expectedTotal()) {
                    throw invalid("Mệnh giá QR đang chờ khác báo giá mới. Kiểm tra giao dịch trước khi hủy hoặc tạo mã mới.");
                }
                return cashier.replayPaymentResponse(collection.pendingTransactionId(), operator, null);
            }
            throw invalid("Mã QR đang chờ. Hãy kiểm tra trạng thái hoặc hủy mã trước khi thu tiền mặt.");
        }
        if (quote.cashDue() != tender.expectedDue() || quote.total() != tender.expectedTotal()) {
            throw invalid("Số tiền phải thu đã đổi. Hãy cập nhật phiếu trước khi thu.");
        }
        Long collectionId = collection == null ? folioPayments.start(id, new RoomFolioCollection.Pick(slotIds, tender.selection())) : collection.id();
        return cashier.collectRoomFolio(id, collectionId, quote.cashDue(), tender.provider(), request, operator);
    }

    public PaymentResponse replayTender(String transactionId, User operator) {
        return cashier.replayPaymentResponse(transactionId, operator, null);
    }

    @Transactional
    public void releaseCollection(Long id, Long collectionId, long expectedReceived) {
        var reservation = reservations.findByIdForUpdate(id).orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        var collection = folioPayments.current(id);
        if (collection == null) return;
        if (!collection.id().equals(collectionId) || collection.cash() + collection.qr() != expectedReceived) {
            throw invalid("Trạng thái thu tiền đã thay đổi. Hãy kiểm tra số đã nhận trước khi đóng phiếu.");
        }
        if (collection.pendingTransactionId() != null) cashier.abandonPendingPayment(collection.pendingTransactionId());
        folioPayments.close(collectionId);
        audit.record(reservation, "ROOM_FOLIO_COLLECTION", String.valueOf(collectionId), ReservationAuditAction.ROOM_FOLIO_RELEASED,
                "Đóng phiếu thu, giữ khoản đã nhận trên hóa đơn chung", null, null,
                Map.of("slots", collection.pick().slotIds(), "cash", collection.cash(), "qr", collection.qr()),
                UUID.randomUUID().toString(), "ROOM_FOLIO_RELEASED:" + collectionId);
    }

    private void checkSettlementAllowed(Long id, Calculation calculation, RoomBillingMode mode) {
        boolean ownPending = calculation.collection() != null && calculation.collection().pendingTransactionId() != null;
        if (!ownPending && (payments.existsByReservationIdAndPurposeAndStatus(id, PaymentPurpose.FINAL_PAYMENT, PaymentStatus.PENDING)
                || payments.existsByReservationIdAndPurposeAndStatus(id, PaymentPurpose.WALK_IN, PaymentStatus.PENDING))) {
            throw invalid("Hãy đối soát hoặc hủy QR thanh toán đang chờ trước khi trả căn.");
        }
        if (mode == RoomBillingMode.GROUP) {
            if (calculation.collection() != null) throw invalid("Hãy đóng phiếu đang thu trước khi gộp vào hóa đơn cuối.");
            if (calculation.lastOccupied()) throw invalid("Để gộp các căn cuối, hãy dùng Đối soát & trả phòng.");
            return;
        }
        if (calculation.quote().total() > calculation.remainingGroupTotal()) {
            throw invalid("Phần tách vượt tổng còn lại sau giảm giá của đoàn. Hãy dùng hóa đơn chung hoặc kiểm tra lại phân bổ.");
        }
        if (refunds.getOutstandingReservedRefundAmount(id) > 0 || refunds.getUncoveredRequiredRefundAmount(id) > 0) {
            throw invalid("Hãy đối soát phần hoàn tiền đang chờ trước khi tách hóa đơn.");
        }
    }

    private AppException invalid(String message) { return new AppException(ErrorCode.INVALID_REQUEST, message); }
}
