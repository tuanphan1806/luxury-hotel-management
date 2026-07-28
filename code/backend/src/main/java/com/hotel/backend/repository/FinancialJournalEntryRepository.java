package com.hotel.backend.repository;

import com.hotel.backend.constant.FinancialPostingKind;
import com.hotel.backend.constant.FinancialSourceType;
import com.hotel.backend.entity.FinancialJournalEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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

    Page<FinancialJournalEntry> findAllByBusinessDate(LocalDate businessDate, Pageable pageable);

    boolean existsByPaymentTransactionId(String paymentTransactionId);

    boolean existsByRefundId(String refundId);

    boolean existsByInvoiceId(Long invoiceId);
}
