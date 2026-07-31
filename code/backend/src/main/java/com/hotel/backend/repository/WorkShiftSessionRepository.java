package com.hotel.backend.repository;

import com.hotel.backend.constant.WorkShiftSessionStatus;
import com.hotel.backend.entity.WorkShiftSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkShiftSessionRepository extends JpaRepository<WorkShiftSession, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select session from WorkShiftSession session
            join fetch session.assignment assignment
            join fetch assignment.shiftTemplate
            join fetch session.employee
            left join fetch session.cashierShift
            where assignment.id = :assignmentId
            """)
    Optional<WorkShiftSession> findByAssignmentIdForUpdate(
            @Param("assignmentId") Long assignmentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select session from WorkShiftSession session
            where session.employee.id = :employeeId
              and session.status = 'ACTIVE'
            """)
    Optional<WorkShiftSession> findActiveByEmployeeIdForUpdate(
            @Param("employeeId") Long employeeId);

    @EntityGraph(attributePaths = {
            "assignment", "assignment.shiftTemplate", "employee", "cashierShift"})
    Optional<WorkShiftSession> findFirstByEmployeeIdAndStatusOrderByActualCheckInUtcDesc(
            Long employeeId,
            WorkShiftSessionStatus status);

    @Query("""
            select assignment.id from WorkShiftSession session
            join session.assignment assignment
            where session.status = :status
              and assignment.scheduledEndUtc < :cutoff
            order by assignment.scheduledEndUtc asc, session.id asc
            """)
    List<Long> findExpiredAssignmentIds(
            @Param("status") WorkShiftSessionStatus status,
            @Param("cutoff") Instant cutoff,
            Pageable pageable);
}
