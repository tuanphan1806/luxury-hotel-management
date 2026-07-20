package com.hotel.backend.repository;

import com.hotel.backend.entity.ReservationInvoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReservationInvoiceRepository extends JpaRepository<ReservationInvoice, Long> {
    Optional<ReservationInvoice> findByReservationId(Long reservationId);
}
