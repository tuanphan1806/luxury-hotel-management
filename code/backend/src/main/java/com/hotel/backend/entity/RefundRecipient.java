package com.hotel.backend.entity;

import com.hotel.backend.constant.RefundRecipientMethod;
import com.hotel.backend.constant.RefundRecipientStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "refund_recipients", indexes = {
        @Index(name = "idx_refund_recipients_reservation", columnList = "reservation_id"),
        @Index(name = "idx_refund_recipients_status", columnList = "status")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private String id;

    @Version
    @Builder.Default
    @Column(nullable = false)
    private Long version = 0L;

    /**
     * Nullable for an unmatched provider transfer.  Such a refund is owned by
     * paymentRefund directly and intentionally has no synthetic reservation.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    /** Canonical direct owner. Null only for legacy/pre-submitted recipients. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_refund_id", unique = true)
    private PaymentRefund paymentRefund;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RefundRecipientMethod method;

    @Column(name = "bank_code", nullable = false, length = 20)
    private String bankCode;

    @Column(name = "bank_name", nullable = false, length = 120)
    private String bankName;

    @Column(name = "account_number_ciphertext", nullable = false, length = 1000)
    private String accountNumberCiphertext;

    @Column(name = "account_number_last4", nullable = false, length = 4)
    private String accountNumberLast4;

    @Column(name = "account_holder_ciphertext", nullable = false, length = 1000)
    private String accountHolderCiphertext;

    @Column(name = "encryption_key_version", nullable = false, length = 20)
    private String encryptionKeyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RefundRecipientStatus status;

    @Column(name = "provided_by", nullable = false, length = 245)
    private String providedBy;

    @Column(name = "provided_at", nullable = false)
    private LocalDateTime providedAt;

    @Column(name = "verified_by", length = 245)
    private String verifiedBy;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
