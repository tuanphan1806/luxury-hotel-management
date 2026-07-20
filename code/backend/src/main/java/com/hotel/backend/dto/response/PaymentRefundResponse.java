package com.hotel.backend.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.constant.RefundCompletionMethod;
import com.hotel.backend.constant.RefundRecipientStatus;
import com.hotel.backend.constant.RefundSourceType;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.entity.PaymentRefund;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class PaymentRefundResponse {
    private String refundId;
    private String transactionId;
    private String transactionReference;
    private String providerTransactionId;
    private Long bookingId;
    private String reservationCode;
    private PaymentProvider originalProvider;
    private PaymentProvider refundProvider;
    private RefundChannel refundChannel;
    private RefundStatus status;
    private String canonicalStatus;
    private RefundSourceType sourceType;
    private String canonicalSourceType;
    private String sourceKey;
    private Long amount;
    private Long requestedAmount;
    private Long actualRefundAmount;
    private String requestId;
    private String refundCode;
    private Long expectedAmount;
    private String refundTransactionId;
    private String bankReferenceCode;
    private RefundCompletionMethod completionMethod;
    private String responseCode;
    private String transactionStatus;
    private String message;
    private java.time.Instant cancelledAtUtc;
    private String cancelledBy;
    private String cancellationReason;
    private JsonNode refundDetail;
    private RefundRecipientStatus recipientStatus;
    private String recipientBankCode;
    private String recipientBankName;
    private String recipientAccountMasked;
    private boolean recipientRequired;
    private String manualTransferReference;
    private LocalDateTime manualTransferredAt;
    private java.time.Instant manualTransferredAtUtc;
    private java.time.Instant manualFallbackAvailableAtUtc;
    private java.time.Instant manualFallbackOpenedAtUtc;
    private String manualFallbackOpenedBy;
    private String manualFallbackReason;
    private String proofImageUrl;
    private LocalDateTime requestedAt;
    private java.time.Instant requestedAtUtc;
    private LocalDateTime completedAt;
    private java.time.Instant completedAtUtc;
    private LocalDateTime updatedAt;
    private boolean canRetry;
    private boolean canReconcile;
    private boolean canCompleteManually;
    private boolean canCompleteCash;
    private boolean awaitingBankConfirmation;

    public static PaymentRefundResponse from(PaymentRefund refund) {
        var payment = refund.getPaymentTransaction();
        var reservation = refund.getReservation() != null
                ? refund.getReservation()
                : payment != null ? payment.getReservation() : null;
        return PaymentRefundResponse.builder()
                .refundId(refund.getId())
                .transactionId(payment != null ? payment.getId() : null)
                .transactionReference(payment != null ? payment.getTxnRef() : null)
                .providerTransactionId(payment != null ? payment.getProviderTxnId() : null)
                .bookingId(reservation != null ? reservation.getId() : null)
                .reservationCode(reservation != null ? reservation.getReservationCode() : null)
                .originalProvider(payment != null ? payment.getProvider() : refund.getProvider())
                .refundProvider(refund.getProvider())
                .refundChannel(refund.getChannel())
                .status(refund.getStatus())
                .canonicalStatus(refund.getStatus() != null
                        ? refund.getStatus().canonicalName() : null)
                .sourceType(refund.getSourceType())
                .canonicalSourceType(refund.getSourceType() != null
                        ? refund.getSourceType().canonicalName() : null)
                .sourceKey(refund.getSourceKey())
                .amount(refund.getAmount())
                .requestedAmount(refund.getRequestedAmount())
                .actualRefundAmount(refund.getActualRefundAmount())
                .requestId(refund.getRequestId())
                .refundCode(refund.getRefundCode())
                .expectedAmount(refund.getRequestedAmount() != null
                        ? refund.getRequestedAmount() : refund.getAmount())
                .refundTransactionId(refund.getProviderRefundTxnId())
                .bankReferenceCode(refund.getCompletionProviderEvent() != null
                        ? refund.getCompletionProviderEvent().getBankReferenceCode() : null)
                .completionMethod(refund.getCompletionMethod())
                .responseCode(refund.getResponseCode())
                .transactionStatus(refund.getTransactionStatus())
                .message(refund.getMessage())
                .cancelledAtUtc(refund.getCancelledAtUtc())
                .cancelledBy(refund.getCancelledBy())
                .cancellationReason(refund.getCancellationReason())
                .refundDetail(refund.getRefundDetailJson())
                .recipientStatus(refund.getRecipient() != null
                        ? refund.getRecipient().getStatus() : null)
                .recipientBankCode(refund.getRecipient() != null
                        ? refund.getRecipient().getBankCode() : null)
                .recipientBankName(refund.getRecipient() != null
                        ? refund.getRecipient().getBankName() : null)
                .recipientAccountMasked(refund.getRecipient() != null
                        ? "****" + refund.getRecipient().getAccountNumberLast4() : null)
                .recipientRequired(refund.getChannel() == RefundChannel.MANUAL_BANK_TRANSFER
                        && refund.getRecipient() == null
                        && refund.getStatus() == RefundStatus.AWAITING_CUSTOMER_INFO)
                .manualTransferReference(refund.getManualTransferReference())
                .manualTransferredAt(refund.getManualTransferredAt())
                .manualTransferredAtUtc(refund.getManualTransferredAtUtc())
                .manualFallbackAvailableAtUtc(refund.getManualFallbackAvailableAtUtc())
                .manualFallbackOpenedAtUtc(refund.getManualFallbackOpenedAtUtc())
                .manualFallbackOpenedBy(refund.getManualFallbackOpenedBy())
                .manualFallbackReason(refund.getManualFallbackReason())
                .proofImageUrl(refund.getProofAsset() != null ? refund.getProofAsset().getUrl() : null)
                .requestedAt(refund.getRequestedAt())
                .requestedAtUtc(refund.getRequestedAtUtc())
                .completedAt(refund.getCompletedAt())
                .completedAtUtc(refund.getCompletedAtUtc())
                .updatedAt(refund.getUpdatedAt())
                .canRetry((refund.getChannel() == RefundChannel.VNPAY_ORIGINAL
                        && (refund.getStatus() == RefundStatus.FAILED
                        || refund.getStatus() == RefundStatus.REQUESTED))
                        || (refund.getStatus() == RefundStatus.CANCELLED
                        && refund.getChannel() != RefundChannel.VNPAY_ORIGINAL))
                .canReconcile(refund.getChannel() == RefundChannel.VNPAY_ORIGINAL
                        && refund.getStatus() == RefundStatus.PROCESSING)
                .canCompleteManually(refund.getChannel() == RefundChannel.MANUAL_BANK_TRANSFER
                        && java.util.List.of(RefundStatus.REQUESTED,
                        RefundStatus.PROCESSING,
                        RefundStatus.READY_FOR_MANUAL_TRANSFER).contains(refund.getStatus())
                        && refund.getRecipient() != null
                        && (refund.getManualFallbackOpenedAtUtc() != null
                        || (refund.getManualFallbackAvailableAtUtc() != null
                        && !java.time.Instant.now().isBefore(
                        refund.getManualFallbackAvailableAtUtc()))
                        || (refund.getStatus() == RefundStatus.READY_FOR_MANUAL_TRANSFER
                        && refund.getManualFallbackAvailableAtUtc() == null)))
                .canCompleteCash(refund.getChannel() == RefundChannel.CASH_AT_COUNTER
                        && refund.getStatus() == RefundStatus.REQUESTED)
                .awaitingBankConfirmation(refund.getChannel() == RefundChannel.MANUAL_BANK_TRANSFER
                        && java.util.List.of(RefundStatus.REQUESTED,
                        RefundStatus.PROCESSING,
                        RefundStatus.READY_FOR_MANUAL_TRANSFER).contains(refund.getStatus())
                        && refund.getRecipient() != null)
                .build();
    }
}
