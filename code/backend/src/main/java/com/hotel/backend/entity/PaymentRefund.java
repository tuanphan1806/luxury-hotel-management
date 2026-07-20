package com.hotel.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.constant.RefundSourceType;
import com.hotel.backend.constant.RefundStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.time.Instant;

@Entity
@Table(name = "payment_refunds", indexes = {
        @Index(name = "idx_payment_refunds_status", columnList = "status"),
        @Index(name = "idx_payment_refunds_payment", columnList = "payment_transaction_id"),
        @Index(name = "idx_payment_refunds_source", columnList = "source_type,source_key")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentRefund {

    @Version
    @Column(nullable = false)
    private Long version;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_transaction_id")
    private PaymentTransaction paymentTransaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_event_id")
    private PaymentProviderEvent providerEvent;

    /** Giao dịch tiền ra đã hoàn tất refund; khác với providerEvent nguồn tiền vào. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "completion_provider_event_id", unique = true)
    private PaymentProviderEvent completionProviderEvent;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private RefundSourceType sourceType = RefundSourceType.LEGACY;

    @Column(name = "source_key", unique = true, length = 191)
    private String sourceKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RefundChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RefundStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id")
    private RefundRecipient recipient;

    @Column(nullable = false)
    private Long amount;

    @Column(name = "requested_amount", nullable = false)
    private Long requestedAmount;

    @Column(name = "actual_refund_amount")
    private Long actualRefundAmount;

    @Column(name = "request_id", nullable = false, unique = true, length = 32)
    private String requestId;

    @Column(name = "refund_code", nullable = false, unique = true, length = 64)
    private String refundCode;

    @Column(name = "request_history", length = 1000)
    private String requestHistory;

    @Column(name = "transaction_type", nullable = false, length = 2)
    private String transactionType;

    @Column(name = "original_transaction_date", length = 14)
    private String originalTransactionDate;

    @Column(name = "provider_refund_txn_id")
    private String providerRefundTxnId;

    @Column(name = "response_code", length = 20)
    private String responseCode;

    @Column(name = "transaction_status", length = 20)
    private String transactionStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_method", length = 32)
    private com.hotel.backend.constant.RefundCompletionMethod completionMethod;

    @Column(name = "manual_transfer_reference", unique = true, length = 100)
    private String manualTransferReference;

    @Column(name = "manual_transferred_by", length = 245)
    private String manualTransferredBy;

    @Column(name = "manual_transferred_at")
    private LocalDateTime manualTransferredAt;

    /** Canonical UTC timestamp; manualTransferredAt remains for compatibility reads. */
    @Column(name = "manual_transferred_at_utc")
    private Instant manualTransferredAtUtc;

    @Column(name = "manual_fallback_available_at_utc")
    private Instant manualFallbackAvailableAtUtc;

    @Column(name = "manual_fallback_opened_at_utc")
    private Instant manualFallbackOpenedAtUtc;

    @Column(name = "manual_fallback_opened_by", length = 245)
    private String manualFallbackOpenedBy;

    @Column(name = "manual_fallback_reason", length = 255)
    private String manualFallbackReason;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proof_asset_id", unique = true)
    private MediaAsset proofAsset;

    @Column(length = 255)
    private String reason;

    @Column(length = 500)
    private String message;

    @Column(name = "requested_by", length = 245)
    private String requestedBy;

    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "requested_at_utc")
    private Instant requestedAtUtc;

    @Column(name = "completed_at_utc")
    private Instant completedAtUtc;

    @Column(name = "cancelled_at_utc")
    private Instant cancelledAtUtc;

    @Column(name = "cancelled_by", length = 245)
    private String cancelledBy;

    @Column(name = "cancellation_reason", length = 255)
    private String cancellationReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "refund_detail_json", columnDefinition = "jsonb")
    private JsonNode refundDetailJson;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void inferLegacyChannel() {
        if (channel == null && provider != null) {
            channel = provider == PaymentProvider.VNPAY
                    ? RefundChannel.VNPAY_ORIGINAL
                    : provider == PaymentProvider.CASH && status == RefundStatus.SUCCEEDED
                    ? RefundChannel.CASH_AT_COUNTER
                    : RefundChannel.MANUAL_BANK_TRANSFER;
        }
        if (requestedAmount == null) requestedAmount = amount;
        if (refundCode == null || refundCode.isBlank()) {
            refundCode = "RF" + java.util.UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 16).toUpperCase(java.util.Locale.ROOT);
        }
        if (sourceKey == null || sourceKey.isBlank()) {
            sourceKey = "legacy:" + java.util.UUID.randomUUID();
        }
        if (reservation == null && paymentTransaction != null) {
            reservation = paymentTransaction.getReservation();
        }
        if (requestedAtUtc == null) requestedAtUtc = Instant.now();
    }
}
