package com.hotel.backend.repository;

import com.hotel.backend.constant.AuditNotificationStatus;
import com.hotel.backend.entity.AuditNotificationOutbox;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AuditNotificationOutboxRepository
        extends JpaRepository<AuditNotificationOutbox, Long> {

    boolean existsByAuditLogIdAndNotificationTypeAndRecipientEmail(
            Long auditLogId,
            String notificationType,
            String recipientEmail);

    @Query("""
        SELECT o.id FROM AuditNotificationOutbox o
        WHERE o.status IN :statuses AND o.nextAttemptAtUtc <= :now
        ORDER BY o.nextAttemptAtUtc, o.id
    """)
    List<Long> findDueIds(
            @Param("statuses") Collection<AuditNotificationStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM AuditNotificationOutbox o JOIN FETCH o.auditLog WHERE o.id = :id")
    Optional<AuditNotificationOutbox> findByIdForUpdate(@Param("id") Long id);

    @Query("""
        SELECT o.id FROM AuditNotificationOutbox o
        WHERE o.status = 'PROCESSING' AND o.lastAttemptAtUtc <= :stuckBefore
        ORDER BY o.lastAttemptAtUtc, o.id
    """)
    List<Long> findStuckProcessingIds(
            @Param("stuckBefore") Instant stuckBefore,
            Pageable pageable);

    long countByStatus(AuditNotificationStatus status);

    long countByStatusInAndCreatedAtUtcBefore(
            Collection<AuditNotificationStatus> statuses,
            Instant cutoff);
}
