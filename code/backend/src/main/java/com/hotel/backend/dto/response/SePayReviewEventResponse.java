package com.hotel.backend.dto.response;

import com.hotel.backend.entity.PaymentProviderEvent;

import java.time.LocalDateTime;
import java.time.Instant;
import com.hotel.backend.constant.PaymentProviderEventStatus;

/**
 * Dữ liệu tối thiểu cho Staff/Admin đối soát giao dịch vào/ra chưa ghép được.
 * Không trả raw webhook hay số tài khoản đầy đủ.
 */
public record SePayReviewEventResponse(
        String eventId,
        String providerEventId,
        String providerReference,
        String bankReferenceCode,
        String transferType,
        Long amount,
        String paymentCode,
        String providerOccurredAt,
        Instant providerOccurredAtUtc,
        Instant receivedAtUtc,
        Integer processingAttempts,
        String accountNumberMasked,
        String message,
        PaymentProviderEventStatus status,
        String paymentTransactionId,
        String reviewedBy,
        Instant reviewedAtUtc,
        String reviewNote,
        LocalDateTime createdAt) {

    public static SePayReviewEventResponse from(PaymentProviderEvent event) {
        return new SePayReviewEventResponse(
                event.getId(),
                event.getProviderEventId(),
                event.getProviderReference(),
                event.getBankReferenceCode(),
                event.getTransferType(),
                event.getAmount(),
                event.getPaymentCode(),
                event.getProviderOccurredAt(),
                event.getProviderOccurredAtUtc(),
                event.getReceivedAtUtc(),
                event.getProcessingAttempts(),
                event.getAccountNumberMasked(),
                event.getMessage(),
                event.getStatus(),
                event.getPaymentTransaction() != null
                        ? event.getPaymentTransaction().getId() : null,
                event.getReviewedBy(),
                event.getReviewedAtUtc(),
                event.getReviewNote(),
                event.getCreatedAt());
    }
}
