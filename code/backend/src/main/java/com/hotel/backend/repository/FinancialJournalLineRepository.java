package com.hotel.backend.repository;

import com.hotel.backend.entity.FinancialJournalLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.time.LocalDate;

public interface FinancialJournalLineRepository
        extends JpaRepository<FinancialJournalLine, Long> {
    List<FinancialJournalLine> findAllByJournalEntryIdOrderByLineNumberAsc(Long journalEntryId);

    List<FinancialJournalLine> findAllByJournalEntryBusinessDate(LocalDate businessDate);
}
