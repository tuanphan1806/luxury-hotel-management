package com.hotel.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.constant.FinancialPostingKind;
import com.hotel.backend.constant.FinancialSourceType;
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
@Table(name = "financial_journal_entries", uniqueConstraints = {
        @UniqueConstraint(name = "uk_financial_journal_source",
                columnNames = {"source_type", "source_id", "posting_kind"})
})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialJournalEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entry_number", nullable = false, unique = true, length = 64)
    private String entryNumber;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "original_business_date", nullable = false)
    private LocalDate originalBusinessDate;

    @Column(name = "occurred_at_utc", nullable = false)
    private Instant occurredAtUtc;

    @Column(name = "posted_at_utc", nullable = false)
    private Instant postedAtUtc;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private FinancialSourceType sourceType;

    @Column(name = "source_id", nullable = false, length = 255)
    private String sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "posting_kind", nullable = false, length = 32)
    private FinancialPostingKind postingKind;

    @Column(nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currency;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "late_posting", nullable = false)
    private boolean latePosting;

    @Column(name = "total_debit", nullable = false, precision = 19, scale = 0)
    private BigDecimal totalDebit;

    @Column(name = "total_credit", nullable = false, precision = 19, scale = 0)
    private BigDecimal totalCredit;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode detailJson;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_transaction_id")
    private PaymentTransaction paymentTransaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_id")
    private PaymentRefund refund;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id")
    private ReservationInvoice invoice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_event_id")
    private PaymentProviderEvent providerEvent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversal_of_entry_id")
    private FinancialJournalEntry reversalOfEntry;

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;
}
