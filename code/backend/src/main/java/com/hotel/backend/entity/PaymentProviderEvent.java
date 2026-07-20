package com.hotel.backend.entity;

import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentProviderEventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_provider_events", uniqueConstraints = {
        @UniqueConstraint(name = "uk_provider_events_provider_event",
                columnNames = {"provider", "provider_event_id"}),
        @UniqueConstraint(name = "uk_provider_events_provider_reference",
                columnNames = {"provider", "provider_reference"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentProviderEvent {
    @Version
    @Builder.Default
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private PaymentProvider provider;

    @Column(name = "provider_event_id", nullable = false, length = 255)
    private String providerEventId;

    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    /** Mã tham chiếu ngân hàng nguyên gốc, dùng làm bằng chứng đối soát. */
    @Column(name = "bank_reference_code", length = 255)
    private String bankReferenceCode;

    @Column(name = "provider_txn_id", length = 255)
    private String providerTxnId;

    @Column(name = "merchant_account_id", length = 128)
    private String merchantAccountId;

    @Column(name = "dedup_key", nullable = false, length = 191)
    private String dedupKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_transaction_id")
    private PaymentTransaction paymentTransaction;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentProviderEventStatus status;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "transfer_type", length = 10)
    private String transferType;

    @Column(name = "account_number_masked", length = 32)
    private String accountNumberMasked;

    @Column(name = "amount")
    private Long amount;

    @Column(name = "payment_code", length = 40)
    private String paymentCode;

    @Column(name = "provider_occurred_at", length = 40)
    private String providerOccurredAt;

    /** Parsed provider time. New values are always persisted as UTC Instants. */
    @Column(name = "provider_occurred_at_utc")
    private Instant providerOccurredAtUtc;

    @Column(name = "received_at_utc")
    private Instant receivedAtUtc;

    @Builder.Default
    @Column(name = "processing_attempts", nullable = false)
    private Integer processingAttempts = 0;

    @Column(name = "next_retry_at_utc")
    private Instant nextRetryAtUtc;

    @Column(name = "last_attempt_at_utc")
    private Instant lastAttemptAtUtc;

    @Column(name = "processed_at_utc")
    private Instant processedAtUtc;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "reviewed_by", length = 245)
    private String reviewedBy;

    @Column(name = "reviewed_at_utc")
    private Instant reviewedAtUtc;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "message", length = 500)
    private String message;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
