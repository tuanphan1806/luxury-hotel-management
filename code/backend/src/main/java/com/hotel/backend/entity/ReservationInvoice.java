package com.hotel.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;

@Entity
@Table(name = "reservation_invoices", indexes = {
        @Index(name = "idx_invoice_number", columnList = "invoice_number", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReservationInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false, unique = true)
    private Reservation reservation;

    @Column(name = "invoice_number", nullable = false, unique = true, length = 64)
    private String invoiceNumber;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Builder.Default
    // ISO-4217 currency codes are fixed-width in the canonical ledger schema.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currency = "VND";

    @Column(name = "room_charge", precision = 19, scale = 2)
    private BigDecimal roomCharge;

    /** Immutable normalized actual room charge; roomCharge remains the legacy alias. */
    @Column(name = "actual_room_charge", precision = 19, scale = 2)
    private BigDecimal actualRoomCharge;

    @Column(name = "planned_room_charge", precision = 19, scale = 2)
    private BigDecimal plannedRoomCharge;

    @Column(name = "early_checkout_adjustment", precision = 19, scale = 2)
    private BigDecimal earlyCheckoutAdjustment;

    @Column(name = "late_checkout_fee", precision = 19, scale = 2)
    private BigDecimal lateCheckoutFee;

    @Column(name = "additional_fee", precision = 19, scale = 2)
    private BigDecimal additionalFee;

    @Column(name = "discount_amount", precision = 19, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "tax_amount", precision = 19, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "gross_received_amount")
    private Long grossReceivedAmount;

    @Column(name = "accepted_paid_amount")
    private Long acceptedPaidAmount;

    @Column(name = "refunded_amount")
    private Long refundedAmount;

    @Column(name = "completed_refund_amount")
    private Long completedRefundAmount;

    @Column(name = "balance_amount")
    private Long balanceAmount;

    @Column(name = "remaining_amount")
    private Long remainingAmount;

    @Column(name = "settlement_status", length = 32)
    private String settlementStatus;

    @Lob
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;

    // SHA-256 is stored as a fixed-width lowercase hexadecimal digest.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "snapshot_hash", length = 64, columnDefinition = "CHAR(64)")
    private String snapshotHash;

    @Column(name = "snapshot_created_at_utc")
    private Instant snapshotCreatedAtUtc;

    @Column(name = "issued_at_utc")
    private Instant issuedAtUtc;

    @Column(name = "created_at_utc", updatable = false)
    private Instant createdAtUtc;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
