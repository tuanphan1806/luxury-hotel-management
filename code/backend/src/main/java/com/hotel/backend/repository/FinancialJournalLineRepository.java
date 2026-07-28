package com.hotel.backend.repository;

import com.hotel.backend.constant.FinancialAccountCode;
import com.hotel.backend.constant.FinancialEntryDirection;
import com.hotel.backend.entity.FinancialJournalLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.time.LocalDate;

public interface FinancialJournalLineRepository
        extends JpaRepository<FinancialJournalLine, Long> {
    List<FinancialJournalLine> findAllByJournalEntryIdOrderByLineNumberAsc(Long journalEntryId);

    List<FinancialJournalLine> findAllByJournalEntryBusinessDate(LocalDate businessDate);

    List<FinancialJournalLine> findAllByJournalEntryIdInOrderByJournalEntryIdAscLineNumberAsc(
            Collection<Long> journalEntryIds);

    @Query("""
            select line.accountCode as accountCode,
                   line.direction as direction,
                   coalesce(sum(line.amount), 0) as amount
            from FinancialJournalLine line
            where line.journalEntry.businessDate = :businessDate
            group by line.accountCode, line.direction
            """)
    List<BusinessDayAccountTotal> summarizeAccounts(@Param("businessDate") LocalDate businessDate);

    interface BusinessDayAccountTotal {
        FinancialAccountCode getAccountCode();
        FinancialEntryDirection getDirection();
        BigDecimal getAmount();
    }
}
