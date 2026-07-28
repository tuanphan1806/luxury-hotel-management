package com.hotel.backend.repository;

import com.hotel.backend.entity.ReservationInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.time.Instant;
import java.util.List;

public interface ReservationInvoiceRepository extends JpaRepository<ReservationInvoice, Long> {
    Optional<ReservationInvoice> findByReservationId(Long reservationId);

    List<ReservationInvoice> findByIssuedAtUtcGreaterThanEqualAndIssuedAtUtcLessThan(
            Instant from,
            Instant to);

    @Query("""
        SELECT COUNT(invoice)
        FROM ReservationInvoice invoice
        WHERE invoice.issuedAtUtc >= :from
          AND invoice.issuedAtUtc < :to
          AND NOT EXISTS (
              SELECT journal.id
              FROM FinancialJournalEntry journal
              WHERE journal.invoice.id = invoice.id
          )
    """)
    long countUnpostedIssuedInvoices(
            @Param("from") Instant from,
            @Param("to") Instant to);
}
