package com.hotel.backend.repository;

import com.hotel.backend.entity.ReservationAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.time.Instant;
import java.util.Collection;
import com.hotel.backend.constant.AuditRiskLevel;

public interface ReservationAuditLogRepository extends JpaRepository<ReservationAuditLog, Long>,
        JpaSpecificationExecutor<ReservationAuditLog> {
    List<ReservationAuditLog> findByReservationIdOrderByOccurredAtUtcDescIdDesc(Long reservationId);

    /** Compatibility query retained for existing operational tests/clients. */
    List<ReservationAuditLog> findByReservationIdOrderByCreatedAtDesc(Long reservationId);

    boolean existsByDedupKey(String dedupKey);

    long countByRiskLevelInAndOccurredAtUtcAfter(
            Collection<AuditRiskLevel> riskLevels,
            Instant occurredAfter);

    @Query("""
        SELECT DISTINCT a.actorName
        FROM ReservationAuditLog a
        WHERE a.actorName IS NOT NULL
        ORDER BY a.actorName
    """)
    List<String> findActorNames(org.springframework.data.domain.Pageable pageable);

    @Query("""
        SELECT DISTINCT a.actorName
        FROM ReservationAuditLog a
        WHERE a.actorName IS NOT NULL
          AND lower(a.actorName) LIKE lower(concat('%', :query, '%'))
        ORDER BY a.actorName
    """)
    List<String> searchActorNames(@Param("query") String query,
                                  org.springframework.data.domain.Pageable pageable);
}
