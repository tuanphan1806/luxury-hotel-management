package com.hotel.backend.repository;

import com.hotel.backend.constant.FinancialPostingKind;
import com.hotel.backend.constant.FinancialSourceType;
import com.hotel.backend.entity.FinancialJournalEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FinancialJournalEntryRepository
        extends JpaRepository<FinancialJournalEntry, Long> {

    Optional<FinancialJournalEntry> findBySourceTypeAndSourceIdAndPostingKind(
            FinancialSourceType sourceType,
            String sourceId,
            FinancialPostingKind postingKind);

    boolean existsBySourceTypeAndSourceIdAndPostingKind(
            FinancialSourceType sourceType,
            String sourceId,
            FinancialPostingKind postingKind);

    List<FinancialJournalEntry> findAllByBusinessDateOrderByPostedAtUtcAscIdAsc(
            LocalDate businessDate);

    @EntityGraph(attributePaths = "reservation")
    Page<FinancialJournalEntry> findAllByBusinessDate(LocalDate businessDate, Pageable pageable);

    @Query("""
            select count(entry) as entryCount,
                   coalesce(sum(entry.totalDebit), 0) as totalDebit,
                   coalesce(sum(entry.totalCredit), 0) as totalCredit,
                   coalesce(sum(case when entry.totalDebit <> entry.totalCredit then 1 else 0 end), 0)
                       as unbalancedCount
            from FinancialJournalEntry entry
            where entry.businessDate = :businessDate
            """)
    BusinessDayJournalSummary summarizeBusinessDate(@Param("businessDate") LocalDate businessDate);

    boolean existsByPaymentTransactionId(String paymentTransactionId);

    boolean existsByRefundId(String refundId);

    boolean existsByInvoiceId(Long invoiceId);

    interface BusinessDayJournalSummary {
        Long getEntryCount();
        BigDecimal getTotalDebit();
        BigDecimal getTotalCredit();
        Long getUnbalancedCount();
    }
}
