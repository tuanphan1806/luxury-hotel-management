package com.hotel.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.constant.BusinessDayCloseStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Immutable
@Table(name = "business_day_closes")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessDayClose {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_date", nullable = false, unique = true)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BusinessDayCloseStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "closed_by", nullable = false)
    private User closedBy;

    @Column(name = "closed_by_name", nullable = false, length = 150)
    private String closedByName;

    @Column(name = "closed_by_role", nullable = false, length = 32)
    private String closedByRole;

    @Column(name = "closed_at_utc", nullable = false)
    private Instant closedAtUtc;

    @Column(name = "journal_entry_count", nullable = false)
    private Long journalEntryCount;

    @Column(name = "total_debit", nullable = false, precision = 19, scale = 0)
    private BigDecimal totalDebit;

    @Column(name = "total_credit", nullable = false, precision = 19, scale = 0)
    private BigDecimal totalCredit;

    @Column(name = "payment_received_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal paymentReceivedAmount;

    @Column(name = "refund_completed_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal refundCompletedAmount;

    @Column(name = "recognized_revenue_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal recognizedRevenueAmount;

    @Column(name = "pending_refund_payable_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal pendingRefundPayableAmount;

    @Column(name = "cash_variance_amount", nullable = false, precision = 19, scale = 0)
    private BigDecimal cashVarianceAmount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode summaryJson;

    @Column(name = "summary_hash", nullable = false, length = 64)
    private String summaryHash;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;
}
