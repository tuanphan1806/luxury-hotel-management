package com.hotel.backend.repository;

import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.entity.PaymentProviderEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.List;
import java.time.Instant;

public interface PaymentProviderEventRepository extends JpaRepository<PaymentProviderEvent, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT event FROM PaymentProviderEvent event WHERE event.id = :id")
    Optional<PaymentProviderEvent> findByIdForUpdate(@Param("id") String id);

    Optional<PaymentProviderEvent> findByProviderAndDedupKey(
            PaymentProvider provider,
            String dedupKey);

    Optional<PaymentProviderEvent> findByProviderAndProviderEventId(
            PaymentProvider provider,
            String providerEventId);

    Optional<PaymentProviderEvent> findByProviderAndProviderReference(
            PaymentProvider provider,
            String providerReference);

    List<PaymentProviderEvent> findByProviderAndStatusOrderByCreatedAtAsc(
            PaymentProvider provider,
            com.hotel.backend.constant.PaymentProviderEventStatus status);

    long countByReceivedAtUtcBetween(Instant from, Instant to);

    long countByStatus(com.hotel.backend.constant.PaymentProviderEventStatus status);

    long countByStatusAndTransferTypeIgnoreCase(
            com.hotel.backend.constant.PaymentProviderEventStatus status,
            String transferType);

    @Query("""
        SELECT MIN(COALESCE(event.providerOccurredAtUtc, event.receivedAtUtc))
        FROM PaymentProviderEvent event
        WHERE event.provider = :provider
          AND event.status = :status
          AND event.nextRetryAtUtc <= :now
    """)
    Optional<Instant> findEarliestDueRetryOccurredAt(
            @Param("provider") PaymentProvider provider,
            @Param("status") com.hotel.backend.constant.PaymentProviderEventStatus status,
            @Param("now") Instant now);
}
