package com.hotel.backend.entity;

import com.hotel.backend.constant.FinancialAccountCode;
import com.hotel.backend.constant.FinancialEntryDirection;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Immutable
@Table(name = "financial_journal_lines", uniqueConstraints = {
        @UniqueConstraint(name = "uk_financial_journal_line_number",
                columnNames = {"journal_entry_id", "line_number"})
})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialJournalLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    private FinancialJournalEntry journalEntry;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_code", nullable = false, length = 32)
    private FinancialAccountCode accountCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private FinancialEntryDirection direction;

    @Column(nullable = false, precision = 19, scale = 0)
    private BigDecimal amount;

    @Column(length = 500)
    private String description;

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;
}
