package com.hotel.backend.repository;

import com.hotel.backend.entity.ReservationInvoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.time.Instant;
import java.util.List;

public interface ReservationInvoiceRepository extends JpaRepository<ReservationInvoice, Long> {
    Optional<ReservationInvoice> findByReservationId(Long reservationId);

    List<ReservationInvoice> findByIssuedAtUtcGreaterThanEqualAndIssuedAtUtcLessThan(
            Instant from,
            Instant to);
}
