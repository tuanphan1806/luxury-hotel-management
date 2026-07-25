package com.hotel.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.dto.response.ReservationInvoiceResponse;
import com.hotel.backend.entity.CustomerProfile;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.ReservationInvoice;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.ReservationInvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
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
    private final ObjectMapper objectMapper;

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

    public ReservationInvoiceResponse createSnapshot(Reservation reservation) {
        var existing = reservationInvoiceRepository.findByReservationId(reservation.getId());
        if (existing.isPresent()) {
            return readSnapshot(existing.get().getSnapshotJson());
        }

        BigDecimal lateFee = amountOrZero(reservation.getLateCheckoutFee());
        BigDecimal additionalFee = amountOrZero(reservation.getCheckoutAdditionalFee());
        BigDecimal earlyAdjustment = amountOrZero(reservation.getEarlyCheckoutAdjustment());
        BigDecimal discount = amountOrZero(reservation.getDiscountAmount());
        BigDecimal tax = amountOrZero(reservation.getTaxAmount());
        BigDecimal total = amountOrZero(reservation.getTotalAmount());
        BigDecimal roomCharge = total.subtract(lateFee).subtract(additionalFee).max(BigDecimal.ZERO);
        BigDecimal plannedRoomCharge = roomCharge.add(earlyAdjustment);

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
                .roomTypes(reservation.getRoomTypes().stream()
                        .map(item -> ReservationInvoiceResponse.RoomTypeLine.builder()
                                .roomTypeName(item.getRoomType().getTypeName())
                                .quantity(item.getQuantity())
                                .pricePerRoomForStay(item.getRoomPrice())
                                .plannedSubtotal(item.getSubtotal())
                                .build())
                        .toList())
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
                .earlyCheckoutAdjustment(earlyAdjustment)
                .lateCheckoutFee(lateFee)
                .checkoutAdditionalFee(additionalFee)
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
            reservationInvoiceRepository.save(ReservationInvoice.builder()
                    .reservation(reservation)
                    .invoiceNumber(response.getInvoiceNumber())
                    .issuedAt(response.getIssuedAt())
                    .totalAmount(total)
                    .currency("VND")
                    .roomCharge(roomCharge)
                    .actualRoomCharge(roomCharge)
                    .plannedRoomCharge(plannedRoomCharge)
                    .earlyCheckoutAdjustment(earlyAdjustment)
                    .lateCheckoutFee(lateFee)
                    .additionalFee(additionalFee)
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
}
