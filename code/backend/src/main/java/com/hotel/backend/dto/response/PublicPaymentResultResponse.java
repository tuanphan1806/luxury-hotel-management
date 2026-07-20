package com.hotel.backend.dto.response;

import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentPurpose;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.service.PaymentRefundService;

/**
 * Kết quả thanh toán tối thiểu dùng cho trang theo dõi thanh toán.
 * Không chứa thông tin khách hàng hoặc token truy cập reservation.
 */
public record PublicPaymentResultResponse(
        String transactionId,
        String transactionReference,
        Long bookingId,
        String reservationCode,
        PaymentProvider provider,
        PaymentStatus status,
        PaymentPurpose purpose,
        Long amount,
        Long expectedAmount,
        Long receivedAmount,
        Long acceptedAmount,
        Long refundRequiredAmount,
        String message,
        String requestedBankCode,
        String bankCode,
        String cardType,
        String responseCode,
        String providerPayDate,
        String qrCodeUrl,
        String transferContent,
        String bankAccountNumber,
        String bankName,
        String accountHolder,
        Long refundedAmount,
        Long refundOutstandingAmount,
        RefundChannel refundChannel,
        RefundStatus refundStatus,
        java.time.Instant paidAtUtc,
        java.time.OffsetDateTime expiresAt) {

    public static PublicPaymentResultResponse from(
            PaymentTransaction transaction,
            SePayPaymentInstructions instructions,
            PaymentRefundService.PaymentRefundSummary refundSummary) {
        PaymentRefundService.PaymentRefundSummary safeRefundSummary = refundSummary != null
                ? refundSummary
                : PaymentRefundService.PaymentRefundSummary.empty();
        return new PublicPaymentResultResponse(
                transaction.getId(),
                transaction.getTxnRef(),
                transaction.getReservation().getId(),
                transaction.getReservation().getReservationCode(),
                transaction.getProvider(),
                transaction.getStatus(),
                transaction.getPurpose(),
                transaction.getAmount(),
                transaction.getExpectedAmount(),
                transaction.getReceivedAmount(),
                transaction.getAcceptedAmount(),
                transaction.getRefundRequiredAmount(),
                transaction.getMessage(),
                transaction.getRequestedBankCode(),
                transaction.getBankCode(),
                transaction.getCardType(),
                transaction.getResponseCode(),
                transaction.getProviderPayDate(),
                instructions != null ? instructions.qrCodeUrl() : null,
                instructions != null ? instructions.transferContent() : null,
                instructions != null ? instructions.bankAccountNumber() : null,
                instructions != null ? instructions.bankName() : null,
                instructions != null ? instructions.accountHolder() : null,
                safeRefundSummary.completedAmount(),
                safeRefundSummary.outstandingAmount(),
                safeRefundSummary.latestChannel(),
                safeRefundSummary.latestStatus(),
                transaction.getPaidAtUtc(),
                transaction.getExpiresAtUtc() != null
                        ? transaction.getExpiresAtUtc().atOffset(java.time.ZoneOffset.UTC)
                        : transaction.getExpiresAt() != null
                        ? transaction.getExpiresAt()
                        .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                        .toOffsetDateTime()
                        : null);
    }
}
