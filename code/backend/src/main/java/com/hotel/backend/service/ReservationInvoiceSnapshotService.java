package com.hotel.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.PricingAlgorithmVersion;
import com.hotel.backend.constant.RateSnapshotStage;
import com.hotel.backend.constant.ReservationServiceStatus;
import com.hotel.backend.dto.response.ReservationInvoiceResponse;
import com.hotel.backend.dto.response.ReservationServiceResponse;
import com.hotel.backend.entity.CustomerProfile;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.ReservationInvoice;
import com.hotel.backend.entity.ReservationRateSnapshot;
import com.hotel.backend.entity.ReservationRoomType;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.ReservationInvoiceRepository;
import com.hotel.backend.repository.ReservationRateSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Creates and reads immutable invoice snapshots. Callers retain the reservation
 * lock and transaction boundaries that protect checkout and print operations.
 */
@Component
@RequiredArgsConstructor
public class ReservationInvoiceSnapshotService {

    private final ReservationInvoiceRepository reservationInvoiceRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentRefundService paymentRefundService;
    private final ReservationAddOnService reservationAddOnService;
    private final ReservationRateSnapshotRepository rateSnapshotRepository;
    private final ObjectMapper objectMapper;
    private final FinancialJournalService financialJournalService;

    @Value("${app.hotel-name:Luxury Hotel}")
    private String hotelName;
    @Value("${app.hotel-address:}")
    private String hotelAddress;
    @Value("${app.hotel-phone:}")
    private String hotelPhone;
    @Value("${app.hotel-email:}")
    private String hotelEmail;
    @Value("${app.hotel-tax-code:}")
    private String hotelTaxCode;

    public Optional<ReservationInvoiceResponse> findExisting(Long reservationId) {
        return reservationInvoiceRepository.findByReservationId(reservationId)
                .map(snapshot -> readSnapshot(snapshot.getSnapshotJson()));
    }

    @Transactional
    public ReservationInvoiceResponse createSnapshot(Reservation reservation) {
        var existing = reservationInvoiceRepository.findByReservationId(reservation.getId());
        if (existing.isPresent()) {
            return readSnapshot(existing.get().getSnapshotJson());
        }

        BigDecimal additionalFee = amountOrZero(reservation.getCheckoutAdditionalFee());
        BigDecimal discount = amountOrZero(reservation.getDiscountAmount());
        BigDecimal tax = amountOrZero(reservation.getTaxAmount());
        BigDecimal total = amountOrZero(reservation.getTotalAmount());
        List<ReservationServiceResponse> services =
                reservationAddOnService.listInternal(reservation.getId());
        BigDecimal addOnServiceAmount = services.stream()
                .filter(item -> item.getStatus() == ReservationServiceStatus.CONFIRMED
                        || item.getStatus() == ReservationServiceStatus.FULFILLED)
                .map(ReservationServiceResponse::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        InvoicePricing pricing = resolveInvoicePricing(
                reservation,
                total,
                additionalFee,
                addOnServiceAmount);
        BigDecimal roomCharge = pricing.actualRoomCharge();
        BigDecimal plannedRoomCharge = pricing.plannedRoomCharge();
        BigDecimal extraGuestCharge = pricing.extraGuestCharge();
        BigDecimal earlyAdjustment = pricing.earlyCheckoutAdjustment();
        BigDecimal lateFee = pricing.lateCheckoutFee();

        List<PaymentTransaction> transactions = paymentTransactionRepository
                .findByReservationId(reservation.getId()).stream()
                .filter(transaction -> transaction.getStatus() == PaymentStatus.SUCCESS
                        || transaction.getStatus() == PaymentStatus.REFUND_PENDING
                        || transaction.getStatus() == PaymentStatus.REFUNDED)
                .sorted(Comparator.comparing(PaymentTransaction::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        long grossPaid = transactions.stream()
                .mapToLong(transaction -> transaction.getReceivedAmount() != null
                        ? transaction.getReceivedAmount()
                        : transaction.getAmount() != null ? transaction.getAmount() : 0L)
                .sum();
        long acceptedPaid = transactions.stream()
                .mapToLong(transaction -> transaction.getAcceptedAmount() != null
                        ? transaction.getAcceptedAmount()
                        : transaction.getAmount() != null ? transaction.getAmount() : 0L)
                .sum();
        long netPaid = paymentRefundService.getNetPaidAmount(reservation.getId());
        long refunded = Math.max(0L, grossPaid - netPaid);
        long balance = total.longValue() - netPaid;
        boolean refundPending = transactions.stream()
                .anyMatch(transaction -> transaction.getStatus() == PaymentStatus.REFUND_PENDING);

        CustomerProfile customer = reservation.getCustomerProfile();
        LocalDateTime issuedAt = reservation.getActualCheckOut() != null
                ? reservation.getActualCheckOut() : LocalDateTime.now();
        Instant issuedAtUtc = issuedAt.atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .toInstant();
        ReservationInvoiceResponse response = ReservationInvoiceResponse.builder()
                .invoiceNumber("INV-" + reservation.getReservationCode())
                .reservationId(reservation.getId())
                .reservationCode(reservation.getReservationCode())
                .issuedAt(issuedAt)
                .issuedAtUtc(issuedAtUtc)
                .hotelName(hotelName)
                .hotelAddress(hotelAddress)
                .hotelPhone(hotelPhone)
                .hotelEmail(hotelEmail)
                .hotelTaxCode(hotelTaxCode)
                .customerName(customer.getFullName())
                .customerPhone(customer.getPhone())
                .customerEmail(customer.getEmail())
                .customerAddress(customer.getAddress())
                .plannedCheckIn(reservation.getCheckIn())
                .plannedCheckOut(reservation.getCheckOut())
                .actualCheckIn(reservation.getActualCheckIn())
                .actualCheckOut(reservation.getActualCheckOut())
                .guestCount(reservation.getGuestCount())
                .note(reservation.getNote())
                .pricingVersion(reservation.getPricingVersion())
                .roomTypes(pricing.roomTypeLines())
                .services(services)
                .payments(transactions.stream()
                        .map(transaction -> ReservationInvoiceResponse.PaymentLine.builder()
                                .transactionId(transaction.getId())
                                .transactionReference(transaction.getTxnRef())
                                .provider(transaction.getProvider().name())
                                .purpose(transaction.getPurpose() != null ? transaction.getPurpose().name() : null)
                                .status(transaction.getStatus().name())
                                .amount(transaction.getAmount())
                                .refundAmount(transaction.getRefundAmount())
                                .refundProvider(transaction.getRefundProvider() != null
                                        ? transaction.getRefundProvider().name() : null)
                                .refundChannel(paymentRefundService.latestChannelForPayment(transaction.getId()))
                                .paidAt(transaction.getPaidAt())
                                .paidAtUtc(transaction.getPaidAtUtc())
                                .createdAt(transaction.getCreatedAt())
                                .build())
                        .toList())
                .plannedRoomCharge(plannedRoomCharge)
                .roomCharge(roomCharge)
                .actualRoomCharge(roomCharge)
                .extraGuestCharge(extraGuestCharge)
                .postCommitmentRoomIncrease(
                        pricing.postCommitmentRoomIncrease())
                .earlyCheckoutAdjustment(earlyAdjustment)
                .lateCheckoutFee(lateFee)
                .checkoutAdditionalFee(additionalFee)
                .addOnServiceAmount(addOnServiceAmount)
                .discountAmount(discount)
                .taxAmount(tax)
                .totalAmount(total)
                .grossPaidAmount(grossPaid)
                .refundedAmount(refunded)
                .completedRefundAmount(refunded)
                .netPaidAmount(netPaid)
                .balanceAmount(balance)
                .remainingAmount(balance)
                .settlementStatus(refundPending ? "REFUND_PENDING"
                        : balance > 0 ? "BALANCE_DUE"
                        : balance < 0 ? "OVERPAID" : "PAID")
                .build();

        try {
            String snapshot = objectMapper.writeValueAsString(response);
            ReservationInvoice invoice = reservationInvoiceRepository.saveAndFlush(ReservationInvoice.builder()
                    .reservation(reservation)
                    .invoiceNumber(response.getInvoiceNumber())
                    .issuedAt(response.getIssuedAt())
                    .totalAmount(total)
                    .currency("VND")
                    .roomCharge(roomCharge)
                    .actualRoomCharge(roomCharge)
                    .plannedRoomCharge(plannedRoomCharge)
                    .pricingVersion(reservation.getPricingVersion())
                    .extraGuestCharge(extraGuestCharge)
                    .earlyCheckoutAdjustment(earlyAdjustment)
                    .lateCheckoutFee(lateFee)
                    .additionalFee(additionalFee)
                    .addOnServiceAmount(addOnServiceAmount)
                    .discountAmount(discount)
                    .taxAmount(tax)
                    .grossReceivedAmount(grossPaid)
                    .acceptedPaidAmount(acceptedPaid)
                    .refundedAmount(refunded)
                    .completedRefundAmount(refunded)
                    .balanceAmount(balance)
                    .remainingAmount(balance)
                    .settlementStatus(response.getSettlementStatus())
                    .snapshotJson(snapshot)
                    .snapshotHash(sha256(snapshot))
                    .snapshotCreatedAtUtc(Instant.now())
                    .issuedAtUtc(issuedAtUtc)
                    .createdAtUtc(Instant.now())
                    .build());
            financialJournalService.postInvoice(invoice);
            return response;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Không thể tạo snapshot hóa đơn", exception);
        }
    }

    private ReservationInvoiceResponse readSnapshot(String snapshotJson) {
        try {
            return objectMapper.readValue(snapshotJson, ReservationInvoiceResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Snapshot hóa đơn không hợp lệ", exception);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể tạo hash snapshot", exception);
        }
    }

    private BigDecimal amountOrZero(BigDecimal amount) {
        return amount != null ? amount : BigDecimal.ZERO;
    }

    private InvoicePricing resolveInvoicePricing(
            Reservation reservation,
            BigDecimal total,
            BigDecimal additionalFee,
            BigDecimal addOnServiceAmount) {
        if (reservation.getPricingVersion()
                != PricingAlgorithmVersion.MOTEL_PACKAGE_V2) {
            BigDecimal earlyAdjustment =
                    amountOrZero(reservation.getEarlyCheckoutAdjustment());
            BigDecimal lateFee =
                    amountOrZero(reservation.getLateCheckoutFee());
            BigDecimal roomCharge = total
                    .subtract(lateFee)
                    .subtract(additionalFee)
                    .subtract(addOnServiceAmount)
                    .max(BigDecimal.ZERO);
            List<ReservationInvoiceResponse.RoomTypeLine> lines =
                    reservation.getRoomTypes().stream()
                            .map(item -> ReservationInvoiceResponse
                                    .RoomTypeLine.builder()
                                    .roomTypeCode(
                                            item.getRoomType().getCode())
                                    .roomTypeName(
                                            item.getRoomType()
                                                    .getTypeName())
                                    .quantity(item.getQuantity())
                                    .pricePerRoomForStay(
                                            item.getRoomPrice())
                                    .plannedRoomCharge(
                                            item.getSubtotal())
                                    .actualRoomCharge(
                                            item.getSubtotal())
                                    .plannedExtraGuestCharge(
                                            BigDecimal.ZERO)
                                    .extraGuestCharge(
                                            BigDecimal.ZERO)
                                    .plannedSubtotal(
                                            item.getSubtotal())
                                    .actualSubtotal(
                                            item.getSubtotal())
                                    .build())
                            .toList();
            return new InvoicePricing(
                    roomCharge.add(earlyAdjustment),
                    roomCharge,
                    BigDecimal.ZERO,
                    lateFee,
                    earlyAdjustment,
                    lateFee,
                    lines);
        }

        BigDecimal plannedRoomCharge = BigDecimal.ZERO;
        BigDecimal actualRoomCharge = BigDecimal.ZERO;
        BigDecimal extraGuestCharge = BigDecimal.ZERO;
        List<ReservationInvoiceResponse.RoomTypeLine> lines =
                new ArrayList<>();
        List<ReservationRoomType> orderedLines = reservation
                .getRoomTypes().stream()
                .sorted(Comparator.comparing(
                        item -> item.getRoomType().getId()))
                .toList();
        for (ReservationRoomType item : orderedLines) {
            List<ReservationRateSnapshot> snapshots =
                    rateSnapshotRepository
                            .findByReservationRoomTypeIdOrderBySnapshotSequenceAsc(
                                    item.getId());
            if (snapshots.isEmpty()
                    || snapshots.get(0).getSnapshotStage()
                            != RateSnapshotStage.COMMITMENT) {
                throw new IllegalStateException(
                        "Reservation Pricing V2 thiếu snapshot cam kết cho dòng "
                                + item.getId());
            }
            ReservationRateSnapshot commitment = snapshots.get(0);
            ReservationRateSnapshot latest =
                    snapshots.get(snapshots.size() - 1);
            BigDecimal plannedLineRoom =
                    amountOrZero(commitment.getFinalRoomCharge());
            BigDecimal actualLineRoom =
                    amountOrZero(latest.getFinalRoomCharge());
            BigDecimal plannedLineExtraGuest =
                    amountOrZero(commitment.getExtraGuestCharge());
            BigDecimal lineExtraGuest =
                    amountOrZero(latest.getExtraGuestCharge());
            plannedRoomCharge =
                    plannedRoomCharge.add(plannedLineRoom);
            actualRoomCharge =
                    actualRoomCharge.add(actualLineRoom);
            extraGuestCharge =
                    extraGuestCharge.add(lineExtraGuest);
            lines.add(ReservationInvoiceResponse.RoomTypeLine.builder()
                    .roomTypeCode(item.getRoomType().getCode())
                    .roomTypeName(item.getRoomType().getTypeName())
                    .quantity(item.getQuantity())
                    .pricePerRoomForStay(actualLineRoom.divide(
                            BigDecimal.valueOf(item.getQuantity()),
                            2,
                            RoundingMode.HALF_UP))
                    .plannedRoomCharge(plannedLineRoom)
                    .actualRoomCharge(actualLineRoom)
                    .plannedExtraGuestCharge(
                            plannedLineExtraGuest)
                    .extraGuestCharge(lineExtraGuest)
                    .plannedSubtotal(plannedLineRoom.add(
                            plannedLineExtraGuest))
                    .actualSubtotal(actualLineRoom.add(
                            lineExtraGuest))
                    .appliedPackage(
                            latest.getAppliedPackage().name())
                    .pricingSnapshotHash(
                            latest.getSnapshotHash())
                    .build());
        }
        BigDecimal lateRoomIncrease = actualRoomCharge
                .subtract(plannedRoomCharge)
                .max(BigDecimal.ZERO);
        return new InvoicePricing(
                plannedRoomCharge,
                actualRoomCharge,
                extraGuestCharge,
                lateRoomIncrease,
                BigDecimal.ZERO,
                lateRoomIncrease,
                List.copyOf(lines));
    }

    private record InvoicePricing(
            BigDecimal plannedRoomCharge,
            BigDecimal actualRoomCharge,
            BigDecimal extraGuestCharge,
            BigDecimal postCommitmentRoomIncrease,
            BigDecimal earlyCheckoutAdjustment,
            BigDecimal lateCheckoutFee,
            List<ReservationInvoiceResponse.RoomTypeLine> roomTypeLines) {
    }
}
