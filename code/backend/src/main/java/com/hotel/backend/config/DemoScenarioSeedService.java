package com.hotel.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotel.backend.constant.AssignStatus;
import com.hotel.backend.constant.CleaningStatus;
import com.hotel.backend.constant.HoldStatus;
import com.hotel.backend.constant.IdCardType;
import com.hotel.backend.constant.PaymentPlan;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentPurpose;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.PricingAlgorithmVersion;
import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.constant.RefundCompletionMethod;
import com.hotel.backend.constant.RefundSourceType;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.ReservationServiceStatus;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.RoomStatus;
import com.hotel.backend.dto.request.CloseCashierShiftRequest;
import com.hotel.backend.dto.request.OpenCashierShiftRequest;
import com.hotel.backend.dto.request.ServiceOrderRequest;
import com.hotel.backend.dto.response.CashierShiftResponse;
import com.hotel.backend.entity.CustomerProfile;
import com.hotel.backend.entity.Guest;
import com.hotel.backend.entity.PaymentRefund;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.ReservationRoom;
import com.hotel.backend.entity.ReservationRoomType;
import com.hotel.backend.entity.ReservationServiceOrder;
import com.hotel.backend.entity.Room;
import com.hotel.backend.entity.RoomHold;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.AddOnServiceRepository;
import com.hotel.backend.repository.CustomerProfileRepository;
import com.hotel.backend.repository.GuestRepository;
import com.hotel.backend.repository.PaymentRefundRepository;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.repository.ReservationRoomRepository;
import com.hotel.backend.repository.ReservationRoomTypeRepository;
import com.hotel.backend.repository.ReservationServiceOrderRepository;
import com.hotel.backend.repository.RoomHoldRepository;
import com.hotel.backend.repository.RoomRepository;
import com.hotel.backend.repository.RoomTypeRepository;
import com.hotel.backend.repository.UserRepository;
import com.hotel.backend.service.CashierShiftService;
import com.hotel.backend.service.FinancialJournalService;
import com.hotel.backend.service.ReservationAddOnService;
import com.hotel.backend.service.ReservationAuditService;
import com.hotel.backend.service.ReservationInvoiceSnapshotService;
import com.hotel.backend.service.ReservationRateSnapshotService;
import com.hotel.backend.service.WalkInPricingService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Creates a rerunnable, internally consistent local demo dataset.
 *
 * <p>Every financial source is canonical: payments contain received/accepted/
 * refundable allocation, refunds are linked to their source payment, invoices
 * are produced by the immutable snapshot service and each source is posted
 * through {@link FinancialJournalService}. No provider event is fabricated, so
 * SePay reconciliation cannot mistake fixture data for a real bank event.</p>
 */
@Service
@RequiredArgsConstructor
public class DemoScenarioSeedService {

    private static final ZoneId HOTEL_ZONE =
            ZoneId.of("Asia/Ho_Chi_Minh");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final long OVERPAYMENT_AMOUNT = 100_000L;
    private static final String DEMO_NOTE_PREFIX = "[DEMO LOCAL] ";

    private final ReservationRepository reservationRepository;
    private final ReservationRoomTypeRepository reservationRoomTypeRepository;
    private final ReservationRoomRepository reservationRoomRepository;
    private final RoomHoldRepository roomHoldRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final CustomerProfileRepository customerProfileRepository;
    private final GuestRepository guestRepository;
    private final PaymentTransactionRepository paymentRepository;
    private final PaymentRefundRepository refundRepository;
    private final ReservationServiceOrderRepository serviceOrderRepository;
    private final AddOnServiceRepository addOnServiceRepository;
    private final WalkInPricingService walkInPricingService;
    private final ReservationRateSnapshotService rateSnapshotService;
    private final ReservationAddOnService reservationAddOnService;
    private final ReservationInvoiceSnapshotService invoiceSnapshotService;
    private final FinancialJournalService financialJournalService;
    private final CashierShiftService cashierShiftService;
    private final ReservationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    @Transactional
    public DemoSeedSummary seed() {
        SeedContext context = loadContext();
        LocalDateTime now = LocalDateTime.now(HOTEL_ZONE)
                .withSecond(0)
                .withNano(0);
        LocalDate today = now.toLocalDate();
        MutableSummary summary = new MutableSummary();

        List<ScenarioSpec> scenarios = new ArrayList<>(List.of(
                paymentPendingScenario(today, now),
                paidDraftScenario(today, now),
                confirmedDepositScenario(today, now),
                confirmedMultiRoomScenario(today, now),
                checkedInScenario(now),
                checkedOutCashScenario(now),
                checkedOutOvernightScenario(today),
                checkedOutServiceScenario(today),
                checkedOutLastMonthScenario(today),
                checkedOutTwoMonthsScenario(today),
                noShowScenario(today),
                cancelledBankRefundScenario(today),
                cancelledCashRefundScenario(today, now)));
        scenarios.addAll(completedAccountingScenarios(today));

        List<ScenarioSpec> pendingScenarios = scenarios.stream()
                .filter(scenario -> !reservationRepository
                        .existsByReservationCode(scenario.code()))
                .toList();
        summary.skippedReservations =
                scenarios.size() - pendingScenarios.size();
        prepareCashierShift(context.staff(), pendingScenarios, summary);

        for (ScenarioSpec scenario : pendingScenarios) {
            createScenario(context, scenario, summary);
            summary.createdReservations++;
        }
        closeSeedCashierShift(context.staff(), summary);
        entityManager.flush();
        return summary.snapshot();
    }

    private SeedContext loadContext() {
        User staff = requireUser("staff1");
        Map<String, User> customers = new LinkedHashMap<>();
        for (String username : List.of(
                "customer1", "customer2", "vtmai", "btngocc", "tvkhoa")) {
            customers.put(username, requireUser(username));
        }
        Map<String, RoomType> roomTypes = new LinkedHashMap<>();
        for (String code : List.of(
                "STANDARD", "DELUXE", "EXECUTIVE",
                "SUITE", "FAMILY", "PRESIDENTIAL")) {
            roomTypes.put(code, roomTypeRepository.findByCode(code)
                    .orElseThrow(() -> missingMaster(
                            "room type " + code)));
        }
        return new SeedContext(staff, customers, roomTypes);
    }

    private void createScenario(
            SeedContext context,
            ScenarioSpec scenario,
            MutableSummary summary) {
        User customer = context.customers().get(scenario.customerUsername());
        if (customer == null) {
            throw missingMaster("customer " + scenario.customerUsername());
        }
        CustomerProfile customerProfile = customerProfileRepository
                .findByLinkedUserId(customer.getId())
                .orElseThrow(() -> missingMaster(
                        "customer profile " + scenario.customerUsername()));

        List<WalkInPricingService.LineInput> pricingInputs =
                scenario.lines().stream()
                        .map(line -> new WalkInPricingService.LineInput(
                                requireRoomType(context, line.roomTypeCode()),
                                line.quantity(),
                                line.guestCount(),
                                line.quantity()))
                        .toList();
        WalkInPricingService.Calculation pricing =
                walkInPricingService.calculateIfEligible(
                                scenario.checkIn(),
                                scenario.checkOut(),
                                scenario.guestCount(),
                                pricingInputs)
                        .orElseThrow(() -> new IllegalStateException(
                                "Pricing V2 must be enabled for demo scenarios"));

        ReservationAddOnService.BookingQuote serviceQuote =
                quoteServices(scenario, pricing);
        BigDecimal totalAmount = pricing.totalBeforeServices()
                .add(serviceQuote.totalAmount())
                .setScale(2);
        BigDecimal requiredInitialPayment = requiredInitialPayment(
                scenario.paymentPlan(), totalAmount);

        Reservation reservation = Reservation.builder()
                .reservationCode(scenario.code())
                .guestToken("demo-" + scenario.code().toLowerCase(Locale.ROOT))
                .customerProfile(customerProfile)
                .checkIn(scenario.checkIn())
                .checkOut(scenario.checkOut())
                .actualCheckIn(scenario.actualCheckIn())
                .actualCheckOut(scenario.actualCheckOut())
                .pricingVersion(PricingAlgorithmVersion.MOTEL_PACKAGE_V2)
                .displayPackageSummary(pricing.displayPackage())
                .inventoryProtectedUntil(pricing.inventoryProtectedUntil())
                .lastActivityAt(scenario.activityAt())
                .totalAmount(totalAmount)
                .paymentPlan(scenario.paymentPlan())
                .requiredInitialPayment(requiredInitialPayment)
                .lateCheckoutFee(ZERO)
                .earlyCheckoutAdjustment(ZERO)
                .checkoutAdditionalFee(ZERO)
                .discountAmount(ZERO)
                .taxAmount(ZERO)
                .status(scenario.status())
                .statusBeforeCancellation(
                        scenario.status() == ReservationStatus.CANCELLED
                                ? ReservationStatus.CONFIRMED
                                : null)
                .cancellationFee(ZERO)
                .refundableAmount(scenario.refundMode()
                        == RefundMode.FULL_ACCEPTED
                        ? requiredInitialPayment
                        : ZERO)
                .cancellationReason(
                        scenario.status() == ReservationStatus.CANCELLED
                                ? "Khách thay đổi kế hoạch; dữ liệu demo hoàn tiền"
                                : null)
                .cancellationReasonCode(
                        scenario.status() == ReservationStatus.CANCELLED
                                ? "DEMO_GUEST_PLAN_CHANGED"
                                : null)
                .note(DEMO_NOTE_PREFIX + scenario.description())
                .guestCount(scenario.guestCount())
                .build();
        reservation = reservationRepository.saveAndFlush(reservation);

        if (!serviceQuote.lines().isEmpty()) {
            reservationAddOnService.attachBookingTime(
                    reservation, serviceQuote, customer);
        }

        Map<Long, ReservationRoomType> savedLines =
                createReservationLines(
                        context, reservation, scenario, pricing);
        rateSnapshotService.createWalkInSnapshots(
                reservation,
                savedLines,
                pricing,
                serviceQuote.totalAmount());

        if (scenario.status() == ReservationStatus.CHECKED_OUT) {
            markServicesFulfilled(
                    reservation, context.staff(), scenario.actualCheckOut());
        }

        PaymentTransaction payment = createPaymentIfNeeded(
                context, reservation, scenario, totalAmount,
                requiredInitialPayment, summary);
        createRefundIfNeeded(
                context, reservation, scenario, payment, summary);

        if (scenario.status() == ReservationStatus.CHECKED_OUT) {
            invoiceSnapshotService.createSnapshot(reservation);
            summary.createdInvoices++;
            summary.createdJournalEntries++;
        }

        recordScenarioAudit(reservation, scenario);
        entityManager.flush();
        backdateRuntimeRows(reservation, payment, scenario);
    }

    private Map<Long, ReservationRoomType> createReservationLines(
            SeedContext context,
            Reservation reservation,
            ScenarioSpec scenario,
            WalkInPricingService.Calculation pricing) {
        Map<Long, WalkInPricingService.CalculatedLine> calculated =
                new LinkedHashMap<>();
        pricing.lines().forEach(line ->
                calculated.put(line.roomType().getId(), line));
        Map<Long, ReservationRoomType> savedLines =
                new LinkedHashMap<>();
        List<ReservationRoom> assignedRooms = new ArrayList<>();

        for (LineSpec spec : scenario.lines()) {
            RoomType roomType =
                    requireRoomType(context, spec.roomTypeCode());
            WalkInPricingService.CalculatedLine price =
                    Objects.requireNonNull(calculated.get(roomType.getId()));
            ReservationRoomType line = ReservationRoomType.builder()
                    .reservation(reservation)
                    .roomType(roomType)
                    .quantity(spec.quantity())
                    .roomPrice(price.breakdown().roomChargePerRoom())
                    .subtotal(price.breakdown().lineTotalBeforeServices())
                    .lineGuestCount(price.lineGuestCount())
                    .minimumCommittedRoomCharge(
                            price.breakdown().roomCharge())
                    .maxPackageReached(
                            price.breakdown().appliedPackage())
                    .build();
            line = reservationRoomTypeRepository.saveAndFlush(line);
            reservation.getRoomTypes().add(line);
            savedLines.put(roomType.getId(), line);

            List<Room> concreteRooms = concreteRooms(
                    roomType, spec.quantity(), scenario.status());
            for (int index = 0; index < spec.quantity(); index++) {
                Room room = index < concreteRooms.size()
                        ? concreteRooms.get(index) : null;
                ReservationRoom reservationRoom =
                        reservationRoomRepository.save(
                                ReservationRoom.builder()
                                        .reservationRoomType(line)
                                        .room(room)
                                        .assignedBy(room != null
                                                ? context.staff() : null)
                                        .status(assignStatus(
                                                scenario.status(), room))
                                        .build());
                line.getRooms().add(reservationRoom);
                if (room != null) {
                    assignedRooms.add(reservationRoom);
                    if (scenario.status()
                            == ReservationStatus.CHECKED_IN) {
                        room.setStatus(RoomStatus.CHECKED_IN);
                        room.setCleaningStatus(CleaningStatus.CLEAN);
                        roomRepository.save(room);
                    }
                }
            }
            createHoldIfRelevant(line, scenario);
        }
        createGuests(
                reservation,
                assignedRooms,
                scenario,
                context.customers().get(scenario.customerUsername()));
        return savedLines;
    }

    private ReservationAddOnService.BookingQuote quoteServices(
            ScenarioSpec scenario,
            WalkInPricingService.Calculation pricing) {
        if (scenario.serviceCode() == null) {
            return new ReservationAddOnService.BookingQuote(
                    List.of(), BigDecimal.ZERO);
        }
        var service = addOnServiceRepository
                .findByCodeIgnoreCase(scenario.serviceCode())
                .filter(item -> item.isActive())
                .orElseThrow(() -> missingMaster(
                        "active add-on " + scenario.serviceCode()));
        ServiceOrderRequest request = ServiceOrderRequest.builder()
                .serviceId(service.getId())
                .quantity(scenario.serviceQuantity())
                .notes("Dịch vụ mẫu gắn đúng reservation")
                .build();
        return reservationAddOnService
                .quoteBookingTimeForPackageCycles(
                        List.of(request),
                        scenario.guestCount(),
                        pricing.packageCycles());
    }

    private void markServicesFulfilled(
            Reservation reservation,
            User staff,
            LocalDateTime fulfilledAt) {
        Instant fulfilledAtUtc = toInstant(fulfilledAt);
        List<ReservationServiceOrder> orders =
                serviceOrderRepository.findByReservationIdForUpdate(
                        reservation.getId());
        for (ReservationServiceOrder order : orders) {
            order.setStatus(ReservationServiceStatus.FULFILLED);
            order.setFulfilledAtUtc(fulfilledAtUtc);
            order.setLastUpdatedByUser(staff);
        }
        serviceOrderRepository.saveAll(orders);
    }

    private PaymentTransaction createPaymentIfNeeded(
            SeedContext context,
            Reservation reservation,
            ScenarioSpec scenario,
            BigDecimal totalAmount,
            BigDecimal requiredInitialPayment,
            MutableSummary summary) {
        if (scenario.paymentMode() == PaymentMode.NONE) {
            return null;
        }
        String txnRef = "DEMO-" + scenario.code() + "-PAY";
        PaymentTransaction existing =
                paymentRepository.findByTxnRef(txnRef).orElse(null);
        if (existing != null) return existing;

        long total = vnd(totalAmount);
        long accepted = switch (scenario.paymentMode()) {
            case DEPOSIT -> vnd(requiredInitialPayment);
            case FULL, FULL_PLUS_OVERPAYMENT -> total;
            case NONE -> 0L;
        };
        long received = scenario.paymentMode()
                == PaymentMode.FULL_PLUS_OVERPAYMENT
                ? accepted + OVERPAYMENT_AMOUNT
                : accepted;
        long refundRequired = received - accepted;
        LocalDateTime paidAt = scenario.paymentAt() != null
                ? scenario.paymentAt() : scenario.activityAt();

        PaymentTransaction payment = paymentRepository.saveAndFlush(
                PaymentTransaction.builder()
                        .reservation(reservation)
                        .txnRef(txnRef)
                        .providerTxnId("DEMO-PROVIDER-" + scenario.code())
                        .providerReference("DEMO-BANK-" + scenario.code())
                        .provider(scenario.paymentProvider())
                        .purpose(scenario.paymentPurpose())
                        .status(PaymentStatus.SUCCESS)
                        .amount(accepted)
                        .expectedAmount(accepted)
                        .receivedAmount(received)
                        .acceptedAmount(accepted)
                        .refundRequiredAmount(refundRequired)
                        .currency("VND")
                        .orderInfo("Thanh toán dữ liệu mẫu "
                                + reservation.getReservationCode())
                        .ipAddress("127.0.0.1")
                        .bankCode(scenario.paymentProvider()
                                == PaymentProvider.SEPAY ? "TPBANK" : "CASH")
                        .providerPayDate(paidAt.format(
                                DateTimeFormatter.ofPattern("yyyyMMddHHmmss")))
                        .responseCode("00")
                        .message("Demo canonical payment")
                        .paidAt(paidAt)
                        .paidAtUtc(toInstant(paidAt))
                        .build());
        reservation.getTransactions().add(payment);
        financialJournalService.postPayment(payment);
        summary.createdPayments++;
        summary.createdJournalEntries++;

        if (payment.getProvider() == PaymentProvider.CASH) {
            ensureCashierShift(context.staff(), summary);
            cashierShiftService.recordCashPayment(
                    payment, context.staff());
        }
        return payment;
    }

    private void createRefundIfNeeded(
            SeedContext context,
            Reservation reservation,
            ScenarioSpec scenario,
            PaymentTransaction payment,
            MutableSummary summary) {
        if (scenario.refundMode() == RefundMode.NONE) return;
        if (payment == null) {
            throw new IllegalStateException(
                    "Refund demo requires a source payment");
        }
        String sourceKey = "demo-refund:" + scenario.code();
        if (refundRepository.findBySourceKey(sourceKey).isPresent()) return;

        long amount = scenario.refundMode()
                == RefundMode.OVERPAYMENT
                ? OVERPAYMENT_AMOUNT
                : Objects.requireNonNull(payment.getAcceptedAmount());
        LocalDateTime completedAt = scenario.refundAt() != null
                ? scenario.refundAt() : scenario.activityAt();
        boolean cash = scenario.refundChannel()
                == RefundChannel.CASH_AT_COUNTER;
        String compact = scenario.code()
                .replace("DEMO-FIN-", "")
                .replace("-", "");

        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("originalAmount",
                Objects.requireNonNull(payment.getReceivedAmount()));
        detail.put("penaltyPercent", 0);
        detail.put("penaltyAmount", 0);
        detail.put("refundAmount", amount);
        detail.put("policyApplied",
                scenario.refundMode() == RefundMode.OVERPAYMENT
                        ? "OVERPAYMENT_AUTO_REFUND"
                        : "DEMO_FLEXIBLE_FULL_REFUND");

        PaymentRefund refund = refundRepository.saveAndFlush(
                PaymentRefund.builder()
                        .paymentTransaction(payment)
                        .reservation(reservation)
                        .sourceType(scenario.refundMode()
                                == RefundMode.OVERPAYMENT
                                ? RefundSourceType.UNACCEPTED_PAYMENT
                                : RefundSourceType.ACCEPTED_ALLOCATION)
                        .sourceKey(sourceKey)
                        .provider(cash
                                ? PaymentProvider.CASH
                                : PaymentProvider.SEPAY)
                        .channel(scenario.refundChannel())
                        .status(RefundStatus.SUCCEEDED)
                        .amount(amount)
                        .requestedAmount(amount)
                        .actualRefundAmount(amount)
                        .requestId(fit("DR" + compact, 32))
                        .refundCode(fit("DEMO-RF-" + compact, 64))
                        .providerRefundTxnId(
                                "DEMO-REFUND-PROVIDER-" + compact)
                        .responseCode("00")
                        .transactionStatus("SUCCESS")
                        .completionMethod(cash
                                ? RefundCompletionMethod.CASH_HANDOVER
                                : RefundCompletionMethod.MANUAL_FALLBACK)
                        .manualTransferReference(cash
                                ? null
                                : "DEMO-MANUAL-" + compact)
                        .manualTransferredBy(context.staff().getUsername())
                        .manualTransferredAt(completedAt)
                        .manualTransferredAtUtc(toInstant(completedAt))
                        .reason("Hoàn tiền dữ liệu mẫu")
                        .message("Demo refund completed")
                        .requestedBy(context.staff().getUsername())
                        .requestedAt(completedAt.minusMinutes(5))
                        .completedAt(completedAt)
                        .requestedAtUtc(toInstant(
                                completedAt.minusMinutes(5)))
                        .completedAtUtc(toInstant(completedAt))
                        .refundDetailJson(detail)
                        .build());
        financialJournalService.postRefund(refund);
        summary.createdRefunds++;
        summary.createdJournalEntries++;

        if (cash) {
            ensureCashierShift(context.staff(), summary);
            cashierShiftService.recordCashRefund(
                    refund, context.staff());
        }
    }

    private void ensureCashierShift(
            User staff,
            MutableSummary summary) {
        CashierShiftResponse current = cashierShiftService.current(staff);
        if (current != null) {
            if (!isSeedCashierShift(current)) {
                throw activeCashierShiftConflict(current);
            }
            summary.managedCashShiftId = current.id();
            return;
        }
        OpenCashierShiftRequest request =
                new OpenCashierShiftRequest();
        request.setNote(
                "Ca dữ liệu mẫu tự động cho kiểm thử thu và hoàn tiền mặt");
        CashierShiftResponse opened =
                cashierShiftService.open(request, staff);
        summary.managedCashShiftId = opened.id();
        summary.cashShiftOpened = true;
    }

    private void prepareCashierShift(
            User staff,
            List<ScenarioSpec> pendingScenarios,
            MutableSummary summary) {
        CashierShiftResponse current = cashierShiftService.current(staff);
        if (current != null && isSeedCashierShift(current)) {
            summary.managedCashShiftId = current.id();
            return;
        }
        boolean requiresCashDrawer = pendingScenarios.stream()
                .anyMatch(this::usesCashDrawer);
        if (requiresCashDrawer && current != null) {
            throw activeCashierShiftConflict(current);
        }
    }

    private void closeSeedCashierShift(
            User staff,
            MutableSummary summary) {
        if (summary.managedCashShiftId == null) return;
        CloseCashierShiftRequest request =
                new CloseCashierShiftRequest();
        request.setNote(
                "Tự động kết thúc ca sau khi hoàn tất dữ liệu mẫu");
        cashierShiftService.close(
                summary.managedCashShiftId,
                request,
                staff);
        summary.cashShiftClosed = true;
    }

    private boolean usesCashDrawer(ScenarioSpec scenario) {
        return scenario.paymentProvider() == PaymentProvider.CASH
                || scenario.refundChannel()
                == RefundChannel.CASH_AT_COUNTER;
    }

    private boolean isSeedCashierShift(CashierShiftResponse shift) {
        if (shift.note() == null) return false;
        return shift.note().startsWith("Ca dữ liệu mẫu tự động")
                || shift.note().startsWith(
                "Ca demo local tự mở để kiểm thử");
    }

    private IllegalStateException activeCashierShiftConflict(
            CashierShiftResponse shift) {
        return new IllegalStateException(
                "Cannot seed demo cash scenarios while cashier shift "
                        + shift.shiftCode()
                        + " is active for staff1. Close the real shift "
                        + "before running demo seed.");
    }

    private void createHoldIfRelevant(
            ReservationRoomType line,
            ScenarioSpec scenario) {
        if (!scenario.onlineBooking()
                || scenario.status()
                == ReservationStatus.PAYMENT_PENDING) {
            return;
        }
        HoldStatus holdStatus = scenario.status()
                == ReservationStatus.CANCELLED
                ? HoldStatus.RELEASED
                : HoldStatus.CONVERTED;
        LocalDateTime base = scenario.paymentAt() != null
                ? scenario.paymentAt() : scenario.createdAt();
        RoomHold hold = roomHoldRepository.save(
                RoomHold.builder()
                        .reservationRoomType(line)
                        .expiresAt(base.plusMinutes(5))
                        .status(holdStatus)
                        .build());
        line.setRoomHold(hold);
    }

    private List<Room> concreteRooms(
            RoomType roomType,
            int quantity,
            ReservationStatus status) {
        if (!List.of(
                ReservationStatus.CHECKED_IN,
                ReservationStatus.CHECKED_OUT).contains(status)) {
            return List.of();
        }
        List<Room> candidates = roomRepository
                .findByRoomTypeId(roomType.getId())
                .stream()
                .sorted(Comparator.comparing(Room::getRoomName))
                .filter(room -> reservationRoomRepository
                        .findActiveReservationIdByRoomId(
                                room.getId())
                        .isEmpty())
                .filter(room -> status != ReservationStatus.CHECKED_IN
                        || (room.getStatus() == RoomStatus.AVAILABLE
                        && room.getCleaningStatus()
                                == CleaningStatus.CLEAN
                        && Boolean.TRUE.equals(room.getSellable())
                        && room.getDecommissionedAt() == null))
                .limit(quantity)
                .toList();
        if (candidates.size() != quantity) {
            throw new IllegalStateException(
                    "Not enough concrete rooms for demo "
                            + roomType.getCode());
        }
        return candidates;
    }

    private void createGuests(
            Reservation reservation,
            List<ReservationRoom> assignedRooms,
            ScenarioSpec scenario,
            User customer) {
        if (assignedRooms.isEmpty()) return;
        if (scenario.guestCount() < assignedRooms.size()) {
            throw new IllegalStateException(
                    "Demo guest count is smaller than assigned rooms");
        }
        int guestSequence = 0;
        int remaining = scenario.guestCount();
        for (int roomIndex = 0;
             roomIndex < assignedRooms.size();
             roomIndex++) {
            ReservationRoom reservationRoom =
                    assignedRooms.get(roomIndex);
            int minimumForRemainingRooms =
                    assignedRooms.size() - roomIndex - 1;
            int roomGuests = roomIndex == 0
                    ? remaining - minimumForRemainingRooms
                    : 1;
            remaining -= roomGuests;
            for (int index = 0; index < roomGuests; index++) {
                guestSequence++;
                boolean primary = guestSequence == 1;
                Guest guest = guestRepository.save(
                        Guest.builder()
                                .reservationRoom(reservationRoom)
                                .fullName(primary
                                        ? customer.getFullName()
                                        : "Khách demo "
                                        + reservation.getReservationCode()
                                        + " #" + guestSequence)
                                .phone(primary
                                        ? customer.getPhone()
                                        : "0908" + String.format(
                                        "%06d",
                                        Math.floorMod(
                                                reservation.getId()
                                                        .intValue()
                                                        * 31
                                                        + guestSequence,
                                                1_000_000)))
                                .email(primary
                                        ? customer.getEmail() : null)
                                .idCardNumber("DEMO"
                                        + reservation.getId()
                                        + String.format(
                                        "%03d", guestSequence))
                                .idCardType(IdCardType.CCCD)
                                .dateOfBirth(LocalDate.of(
                                        1990 + guestSequence % 10,
                                        1 + guestSequence % 12,
                                        1 + guestSequence % 27))
                                .nationality("Việt Nam")
                                .isPrimary(primary)
                                .checkedOutAt(
                                        scenario.status()
                                                == ReservationStatus.CHECKED_OUT
                                                ? scenario.actualCheckOut()
                                                : null)
                                .build());
                reservationRoom.getGuests().add(guest);
            }
        }
    }

    private void recordScenarioAudit(
            Reservation reservation,
            ScenarioSpec scenario) {
        ReservationAuditAction action = switch (scenario.status()) {
            case PAYMENT_PENDING ->
                    ReservationAuditAction.PAYMENT_SESSION_CREATED;
            case DRAFT -> ReservationAuditAction.PAYMENT_RECEIVED;
            case CONFIRMED -> ReservationAuditAction.CONFIRM;
            case CHECKED_IN -> ReservationAuditAction.CHECK_IN;
            case CHECKED_OUT -> ReservationAuditAction.CHECK_OUT;
            case CANCELLED -> ReservationAuditAction.CANCEL;
            case NO_SHOW -> ReservationAuditAction.MARK_NO_SHOW;
            case CANCELLATION_PENDING ->
                    ReservationAuditAction.CANCEL;
        };
        auditService.recordSystem(
                reservation,
                "RESERVATION",
                String.valueOf(reservation.getId()),
                action,
                "Khởi tạo kịch bản dữ liệu mẫu: "
                        + scenario.description(),
                null,
                Map.of("status", scenario.status().name()),
                Map.of(
                        "fixture", true,
                        "reservationCode", scenario.code()),
                "DEMO-SEED-" + scenario.code(),
                "DEMO-SEED:" + scenario.code() + ":" + action.name());
    }

    private void backdateRuntimeRows(
            Reservation reservation,
            PaymentTransaction payment,
            ScenarioSpec scenario) {
        Timestamp created = Timestamp.valueOf(scenario.createdAt());
        jdbcTemplate.update(
                "UPDATE reservations SET created_at = ?, updated_at = ? "
                        + "WHERE id = ?",
                created,
                Timestamp.valueOf(scenario.activityAt()),
                reservation.getId());
        if (payment != null) {
            Timestamp paid = Timestamp.valueOf(
                    scenario.paymentAt() != null
                            ? scenario.paymentAt()
                            : scenario.activityAt());
            jdbcTemplate.update(
                    "UPDATE payment_transactions "
                            + "SET created_at = ?, updated_at = ? WHERE id = ?",
                    paid, paid, payment.getId());
        }
        refundRepository.findByReservationId(reservation.getId())
                .forEach(refund -> {
                    LocalDateTime completed =
                            refund.getCompletedAt() != null
                                    ? refund.getCompletedAt()
                                    : scenario.activityAt();
                    Timestamp timestamp =
                            Timestamp.valueOf(completed);
                    jdbcTemplate.update(
                            "UPDATE payment_refunds "
                                    + "SET created_at = ?, updated_at = ? "
                                    + "WHERE id = ?",
                            timestamp, timestamp, refund.getId());
                });
    }

    private RoomType requireRoomType(
            SeedContext context,
            String code) {
        RoomType roomType = context.roomTypes().get(code);
        if (roomType == null) {
            throw missingMaster("room type " + code);
        }
        return roomType;
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> missingMaster(
                        "user " + username));
    }

    private IllegalStateException missingMaster(String value) {
        return new IllegalStateException(
                "Missing demo master data: " + value
                        + ". Enable master-data and demo-user seed first.");
    }

    private BigDecimal requiredInitialPayment(
            PaymentPlan plan,
            BigDecimal total) {
        return switch (plan) {
            case DEPOSIT_50 -> total.multiply(
                            new BigDecimal("0.5"))
                    .setScale(0, RoundingMode.CEILING)
                    .setScale(2);
            case PREPAY_100 -> total;
            case PAY_AT_HOTEL -> ZERO;
        };
    }

    private AssignStatus assignStatus(
            ReservationStatus reservationStatus,
            Room room) {
        if (room == null) {
            return reservationStatus == ReservationStatus.CANCELLED
                    ? AssignStatus.CANCELLED
                    : AssignStatus.PENDING_ASSIGN;
        }
        return switch (reservationStatus) {
            case CHECKED_IN -> AssignStatus.CHECKED_IN;
            case CHECKED_OUT -> AssignStatus.CHECKED_OUT;
            case CANCELLED -> AssignStatus.CANCELLED;
            default -> AssignStatus.ASSIGNED;
        };
    }

    private long vnd(BigDecimal value) {
        return value.setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();
    }

    private Instant toInstant(LocalDateTime value) {
        return value.atZone(HOTEL_ZONE).toInstant();
    }

    private String fit(String value, int maximumLength) {
        return value.length() <= maximumLength
                ? value
                : value.substring(0, maximumLength);
    }

    private LocalDateTime at(
            LocalDate base,
            int dayOffset,
            int hour,
            int minute) {
        return base.plusDays(dayOffset)
                .atTime(hour, minute);
    }

    private ScenarioSpec paymentPendingScenario(
            LocalDate today,
            LocalDateTime now) {
        return scenario(
                "DEMO-FIN-PAYMENT-PENDING",
                "customer1",
                ReservationStatus.PAYMENT_PENDING,
                now.minusHours(1),
                at(today, 2, 14, 0),
                at(today, 3, 12, 0),
                null,
                null,
                PaymentPlan.DEPOSIT_50,
                List.of(line("STANDARD", 1, 2)),
                PaymentMode.NONE,
                null,
                null,
                RefundMode.NONE,
                null,
                null,
                null,
                null,
                0,
                true,
                "Đơn vừa tạo, chưa mở QR và chưa giữ phòng");
    }

    private ScenarioSpec paidDraftScenario(
            LocalDate today,
            LocalDateTime now) {
        return scenario(
                "DEMO-FIN-DRAFT-PAID",
                "customer2",
                ReservationStatus.DRAFT,
                now.minusHours(5),
                at(today, 3, 20, 0),
                at(today, 4, 8, 0),
                null,
                null,
                PaymentPlan.DEPOSIT_50,
                List.of(line("DELUXE", 1, 2)),
                PaymentMode.DEPOSIT,
                PaymentProvider.SEPAY,
                PaymentPurpose.DEPOSIT,
                RefundMode.NONE,
                null,
                now.minusHours(4),
                null,
                null,
                0,
                true,
                "Đã cọc 50%, chờ nhân viên xác nhận");
    }

    private ScenarioSpec confirmedDepositScenario(
            LocalDate today,
            LocalDateTime now) {
        return scenario(
                "DEMO-FIN-CONFIRMED-DEPOSIT",
                "vtmai",
                ReservationStatus.CONFIRMED,
                at(today, -2, 9, 15),
                at(today, 5, 20, 0),
                at(today, 6, 8, 0),
                null,
                null,
                PaymentPlan.DEPOSIT_50,
                List.of(line("SUITE", 1, 2)),
                PaymentMode.DEPOSIT,
                PaymentProvider.SEPAY,
                PaymentPurpose.DEPOSIT,
                RefundMode.NONE,
                null,
                at(today, -2, 9, 20),
                null,
                null,
                0,
                true,
                "Đơn qua đêm đã xác nhận, còn tiền cuối kỳ");
    }

    private ScenarioSpec confirmedMultiRoomScenario(
            LocalDate today,
            LocalDateTime now) {
        return scenario(
                "DEMO-FIN-CONFIRMED-MULTI",
                "btngocc",
                ReservationStatus.CONFIRMED,
                at(today, -5, 11, 0),
                at(today, 10, 12, 0),
                at(today, 11, 12, 0),
                null,
                null,
                PaymentPlan.PREPAY_100,
                List.of(
                        line("STANDARD", 2, 4),
                        line("DELUXE", 1, 2)),
                PaymentMode.FULL,
                PaymentProvider.SEPAY,
                PaymentPurpose.DEPOSIT,
                RefundMode.NONE,
                null,
                at(today, -5, 11, 5),
                null,
                "PRIVATE_BBQ_SET",
                1,
                true,
                "Đơn 3 phòng thuộc 2 hạng, đã trả 100% và có dịch vụ");
    }

    private ScenarioSpec checkedInScenario(LocalDateTime now) {
        return scenario(
                "DEMO-FIN-CHECKED-IN",
                "tvkhoa",
                ReservationStatus.CHECKED_IN,
                now.minusDays(1),
                now.minusHours(3),
                now.plusHours(9),
                now.minusHours(3),
                null,
                PaymentPlan.DEPOSIT_50,
                List.of(line("STANDARD", 1, 2)),
                PaymentMode.DEPOSIT,
                PaymentProvider.SEPAY,
                PaymentPurpose.DEPOSIT,
                RefundMode.NONE,
                null,
                now.minusDays(1).plusMinutes(5),
                null,
                "IN_ROOM_BREAKFAST",
                1,
                true,
                "Khách đang lưu trú, đã cọc và còn nghĩa vụ cuối kỳ");
    }

    private ScenarioSpec checkedOutCashScenario(LocalDateTime now) {
        return scenario(
                "DEMO-FIN-CHECKED-OUT-CASH",
                "customer1",
                ReservationStatus.CHECKED_OUT,
                now.minusDays(2),
                now.minusHours(4),
                now.minusHours(2),
                now.minusHours(4),
                now.minusHours(2),
                PaymentPlan.PAY_AT_HOTEL,
                List.of(line("STANDARD", 1, 1)),
                PaymentMode.FULL,
                PaymentProvider.CASH,
                PaymentPurpose.FINAL_PAYMENT,
                RefundMode.NONE,
                null,
                now.minusMinutes(20),
                null,
                null,
                0,
                false,
                "Nghỉ giờ đã checkout và thu đủ tiền mặt trong ca hiện tại");
    }

    private ScenarioSpec checkedOutOvernightScenario(
            LocalDate today) {
        return scenario(
                "DEMO-FIN-CHECKED-OUT-WEEK",
                "customer2",
                ReservationStatus.CHECKED_OUT,
                at(today, -9, 10, 0),
                at(today, -7, 20, 0),
                at(today, -6, 8, 0),
                at(today, -7, 20, 0),
                at(today, -6, 8, 0),
                PaymentPlan.PREPAY_100,
                List.of(line("DELUXE", 1, 2)),
                PaymentMode.FULL,
                PaymentProvider.SEPAY,
                PaymentPurpose.DEPOSIT,
                RefundMode.NONE,
                null,
                at(today, -9, 10, 5),
                null,
                null,
                0,
                true,
                "Đơn qua đêm tuần trước, đã thanh toán và xuất hóa đơn");
    }

    private ScenarioSpec checkedOutServiceScenario(
            LocalDate today) {
        return scenario(
                "DEMO-FIN-CHECKED-OUT-SERVICE",
                "vtmai",
                ReservationStatus.CHECKED_OUT,
                at(today, -18, 9, 0),
                at(today, -14, 12, 0),
                at(today, -13, 12, 0),
                at(today, -14, 12, 0),
                at(today, -13, 12, 0),
                PaymentPlan.PREPAY_100,
                List.of(line("FAMILY", 1, 4)),
                PaymentMode.FULL,
                PaymentProvider.SEPAY,
                PaymentPurpose.DEPOSIT,
                RefundMode.NONE,
                null,
                at(today, -18, 9, 5),
                null,
                "IN_ROOM_BREAKFAST",
                1,
                true,
                "Đơn ngày đêm có doanh thu phòng và bữa sáng tại phòng");
    }

    private ScenarioSpec checkedOutLastMonthScenario(
            LocalDate today) {
        return scenario(
                "DEMO-FIN-CHECKED-OUT-LAST-MONTH",
                "btngocc",
                ReservationStatus.CHECKED_OUT,
                at(today, -40, 15, 0),
                at(today, -36, 20, 0),
                at(today, -35, 8, 0),
                at(today, -36, 20, 0),
                at(today, -35, 8, 0),
                PaymentPlan.PREPAY_100,
                List.of(line("EXECUTIVE", 1, 2)),
                PaymentMode.FULL_PLUS_OVERPAYMENT,
                PaymentProvider.SEPAY,
                PaymentPurpose.DEPOSIT,
                RefundMode.OVERPAYMENT,
                RefundChannel.MANUAL_BANK_TRANSFER,
                at(today, -40, 15, 10),
                at(today, -35, 9, 0),
                null,
                0,
                true,
                "Đơn tháng trước có chuyển thừa và hoàn phần thừa");
    }

    private ScenarioSpec checkedOutTwoMonthsScenario(
            LocalDate today) {
        return scenario(
                "DEMO-FIN-CHECKED-OUT-TWO-MONTHS",
                "tvkhoa",
                ReservationStatus.CHECKED_OUT,
                at(today, -75, 8, 0),
                at(today, -70, 12, 0),
                at(today, -68, 12, 0),
                at(today, -70, 12, 0),
                at(today, -68, 12, 0),
                PaymentPlan.PREPAY_100,
                List.of(
                        line("STANDARD", 2, 4),
                        line("DELUXE", 1, 2)),
                PaymentMode.FULL,
                PaymentProvider.SEPAY,
                PaymentPurpose.DEPOSIT,
                RefundMode.NONE,
                null,
                at(today, -75, 8, 5),
                null,
                "EXTRA_ROLLAWAY_BED",
                1,
                true,
                "Đơn nhiều phòng hai tháng trước để kiểm thử báo cáo tháng");
    }

    private List<ScenarioSpec> completedAccountingScenarios(
            LocalDate today) {
        return List.of(
                completedScenario(
                        "DEMO-FIN-CHECKED-OUT-YESTERDAY",
                        "customer2",
                        at(today, -4, 10, 0),
                        at(today, -1, 9, 0),
                        at(today, -1, 14, 0),
                        List.of(line("STANDARD", 1, 1)),
                        null,
                        "Đơn nghỉ giờ hôm qua"),
                completedScenario(
                        "DEMO-FIN-CHECKED-OUT-THREE-DAYS",
                        "vtmai",
                        at(today, -6, 11, 0),
                        at(today, -3, 20, 0),
                        at(today, -2, 8, 0),
                        List.of(line("SUITE", 1, 2)),
                        null,
                        "Đơn qua đêm ba ngày trước"),
                completedScenario(
                        "DEMO-FIN-CHECKED-OUT-TEN-DAYS",
                        "btngocc",
                        at(today, -14, 8, 0),
                        at(today, -10, 12, 0),
                        at(today, -9, 12, 0),
                        List.of(line("EXECUTIVE", 1, 2)),
                        "MINI_PROJECTOR",
                        "Đơn ngày đêm mười ngày trước có thuê máy chiếu"),
                completedScenario(
                        "DEMO-FIN-CHECKED-OUT-TWENTYONE-DAYS",
                        "tvkhoa",
                        at(today, -24, 14, 0),
                        at(today, -21, 15, 0),
                        at(today, -21, 21, 0),
                        List.of(line("DELUXE", 1, 2)),
                        null,
                        "Đơn nghỉ giờ ba tuần trước"),
                completedScenario(
                        "DEMO-FIN-CHECKED-OUT-TWENTYEIGHT-DAYS",
                        "customer1",
                        at(today, -32, 9, 0),
                        at(today, -28, 12, 0),
                        at(today, -27, 12, 0),
                        List.of(line("FAMILY", 1, 4)),
                        "IN_ROOM_BREAKFAST",
                        "Đơn ngày đêm cuối tháng trước có bữa sáng"));
    }

    private ScenarioSpec completedScenario(
            String code,
            String customerUsername,
            LocalDateTime createdAt,
            LocalDateTime checkIn,
            LocalDateTime checkOut,
            List<LineSpec> lines,
            String serviceCode,
            String description) {
        return scenario(
                code,
                customerUsername,
                ReservationStatus.CHECKED_OUT,
                createdAt,
                checkIn,
                checkOut,
                checkIn,
                checkOut,
                PaymentPlan.PREPAY_100,
                lines,
                PaymentMode.FULL,
                PaymentProvider.SEPAY,
                PaymentPurpose.DEPOSIT,
                RefundMode.NONE,
                null,
                createdAt.plusMinutes(5),
                null,
                serviceCode,
                serviceCode != null ? 1 : 0,
                true,
                description);
    }

    private ScenarioSpec noShowScenario(LocalDate today) {
        return scenario(
                "DEMO-FIN-NO-SHOW",
                "customer1",
                ReservationStatus.NO_SHOW,
                at(today, -25, 10, 0),
                at(today, -20, 20, 0),
                at(today, -19, 8, 0),
                null,
                null,
                PaymentPlan.PAY_AT_HOTEL,
                List.of(line("STANDARD", 1, 1)),
                PaymentMode.NONE,
                null,
                null,
                RefundMode.NONE,
                null,
                null,
                null,
                null,
                0,
                false,
                "Khách bảo lãnh tại quầy không đến, không phát sinh tiền");
    }

    private ScenarioSpec cancelledBankRefundScenario(
            LocalDate today) {
        return scenario(
                "DEMO-FIN-CANCELLED-BANK",
                "customer2",
                ReservationStatus.CANCELLED,
                at(today, -12, 9, 0),
                at(today, -5, 20, 0),
                at(today, -4, 8, 0),
                null,
                null,
                PaymentPlan.DEPOSIT_50,
                List.of(line("SUITE", 1, 2)),
                PaymentMode.DEPOSIT,
                PaymentProvider.SEPAY,
                PaymentPurpose.DEPOSIT,
                RefundMode.FULL_ACCEPTED,
                RefundChannel.MANUAL_BANK_TRANSFER,
                at(today, -12, 9, 5),
                at(today, -11, 14, 0),
                null,
                0,
                true,
                "Hủy đơn và hoàn đủ tiền cọc qua chuyển khoản");
    }

    private ScenarioSpec cancelledCashRefundScenario(
            LocalDate today,
            LocalDateTime now) {
        return scenario(
                "DEMO-FIN-CANCELLED-CASH",
                "vtmai",
                ReservationStatus.CANCELLED,
                now.minusDays(1),
                at(today, 4, 14, 0),
                at(today, 5, 12, 0),
                null,
                null,
                PaymentPlan.DEPOSIT_50,
                List.of(line("DELUXE", 1, 2)),
                PaymentMode.DEPOSIT,
                PaymentProvider.CASH,
                PaymentPurpose.DEPOSIT,
                RefundMode.FULL_ACCEPTED,
                RefundChannel.CASH_AT_COUNTER,
                now.minusMinutes(15),
                now.minusMinutes(5),
                null,
                0,
                false,
                "Thu cọc rồi hoàn tiền mặt tại quầy trong cùng ca");
    }

    private ScenarioSpec scenario(
            String code,
            String customerUsername,
            ReservationStatus status,
            LocalDateTime createdAt,
            LocalDateTime checkIn,
            LocalDateTime checkOut,
            LocalDateTime actualCheckIn,
            LocalDateTime actualCheckOut,
            PaymentPlan paymentPlan,
            List<LineSpec> lines,
            PaymentMode paymentMode,
            PaymentProvider paymentProvider,
            PaymentPurpose paymentPurpose,
            RefundMode refundMode,
            RefundChannel refundChannel,
            LocalDateTime paymentAt,
            LocalDateTime refundAt,
            String serviceCode,
            int serviceQuantity,
            boolean onlineBooking,
            String description) {
        int guestCount = lines.stream()
                .mapToInt(LineSpec::guestCount)
                .sum();
        LocalDateTime activityAt = refundAt != null
                ? refundAt
                : actualCheckOut != null
                ? actualCheckOut
                : actualCheckIn != null
                ? actualCheckIn
                : paymentAt != null ? paymentAt : createdAt;
        return new ScenarioSpec(
                code,
                customerUsername,
                status,
                createdAt,
                checkIn,
                checkOut,
                actualCheckIn,
                actualCheckOut,
                activityAt,
                paymentPlan,
                List.copyOf(lines),
                guestCount,
                paymentMode,
                paymentProvider,
                paymentPurpose,
                refundMode,
                refundChannel,
                paymentAt,
                refundAt,
                serviceCode,
                serviceQuantity,
                onlineBooking,
                description);
    }

    private LineSpec line(
            String roomTypeCode,
            int quantity,
            int guestCount) {
        return new LineSpec(roomTypeCode, quantity, guestCount);
    }

    private enum PaymentMode {
        NONE,
        DEPOSIT,
        FULL,
        FULL_PLUS_OVERPAYMENT
    }

    private enum RefundMode {
        NONE,
        FULL_ACCEPTED,
        OVERPAYMENT
    }

    private record LineSpec(
            String roomTypeCode,
            int quantity,
            int guestCount) {
    }

    private record ScenarioSpec(
            String code,
            String customerUsername,
            ReservationStatus status,
            LocalDateTime createdAt,
            LocalDateTime checkIn,
            LocalDateTime checkOut,
            LocalDateTime actualCheckIn,
            LocalDateTime actualCheckOut,
            LocalDateTime activityAt,
            PaymentPlan paymentPlan,
            List<LineSpec> lines,
            int guestCount,
            PaymentMode paymentMode,
            PaymentProvider paymentProvider,
            PaymentPurpose paymentPurpose,
            RefundMode refundMode,
            RefundChannel refundChannel,
            LocalDateTime paymentAt,
            LocalDateTime refundAt,
            String serviceCode,
            int serviceQuantity,
            boolean onlineBooking,
            String description) {
    }

    private record SeedContext(
            User staff,
            Map<String, User> customers,
            Map<String, RoomType> roomTypes) {
    }

    public record DemoSeedSummary(
            int createdReservations,
            int skippedReservations,
            int createdPayments,
            int createdRefunds,
            int createdInvoices,
            int createdJournalEntries,
            boolean cashShiftOpened,
            boolean cashShiftClosed) {
    }

    private static final class MutableSummary {
        private int createdReservations;
        private int skippedReservations;
        private int createdPayments;
        private int createdRefunds;
        private int createdInvoices;
        private int createdJournalEntries;
        private boolean cashShiftOpened;
        private boolean cashShiftClosed;
        private Long managedCashShiftId;

        private DemoSeedSummary snapshot() {
            return new DemoSeedSummary(
                    createdReservations,
                    skippedReservations,
                    createdPayments,
                    createdRefunds,
                    createdInvoices,
                    createdJournalEntries,
                    cashShiftOpened,
                    cashShiftClosed);
        }
    }
}
