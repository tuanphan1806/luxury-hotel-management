package com.hotel.backend.repository;

import com.hotel.backend.constant.CheckoutReconciliationRequestStatus;
import com.hotel.backend.entity.CheckoutReconciliationRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.time.Instant;

public interface CheckoutReconciliationRequestRepository
        extends JpaRepository<CheckoutReconciliationRequest, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM CheckoutReconciliationRequest r JOIN FETCH r.reservation WHERE r.id = :id")
    Optional<CheckoutReconciliationRequest> findByIdForUpdate(@Param("id") Long id);

    Page<CheckoutReconciliationRequest> findByStatusOrderByCreatedAtUtcAsc(
            CheckoutReconciliationRequestStatus status,
            Pageable pageable);

    boolean existsByReservationIdAndStatus(
            Long reservationId,
            CheckoutReconciliationRequestStatus status);

    long countByStatus(CheckoutReconciliationRequestStatus status);

    long countByStatusAndCreatedAtUtcBefore(
            CheckoutReconciliationRequestStatus status,
            Instant cutoff);

    Optional<CheckoutReconciliationRequest> findByIdempotencyKeyAndActorScope(
            String idempotencyKey,
            String actorScope);
}
