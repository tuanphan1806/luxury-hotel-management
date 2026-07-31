package com.hotel.backend.repository;

import com.hotel.backend.constant.WorkScheduleStatus;
import com.hotel.backend.entity.WorkScheduleAssignment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WorkScheduleAssignmentRepository
        extends JpaRepository<WorkScheduleAssignment, Long> {

    @EntityGraph(attributePaths = {
            "employee", "shiftTemplate",
            "workShiftSession", "workShiftSession.cashierShift"})
    @Query("""
            select assignment from WorkScheduleAssignment assignment
            where assignment.scheduledStartUtc < :toUtc
              and assignment.scheduledEndUtc > :fromUtc
              and (:employeeId is null or assignment.employee.id = :employeeId)
              and (:status is null or assignment.status = :status)
            order by assignment.scheduledStartUtc asc,
                     assignment.employeeNameSnapshot asc,
                     assignment.id asc
            """)
    List<WorkScheduleAssignment> findInWindow(
            @Param("fromUtc") Instant fromUtc,
            @Param("toUtc") Instant toUtc,
            @Param("employeeId") Long employeeId,
            @Param("status") WorkScheduleStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select assignment from WorkScheduleAssignment assignment
            join fetch assignment.employee
            join fetch assignment.shiftTemplate
            left join fetch assignment.workShiftSession session
            left join fetch session.cashierShift
            where assignment.id = :id
            """)
    Optional<WorkScheduleAssignment> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select assignment.id from WorkScheduleAssignment assignment
            where assignment.status = 'SCHEDULED'
              and assignment.scheduledEndUtc < :cutoff
              and not exists (
                  select session.id from WorkShiftSession session
                  where session.assignment.id = assignment.id
              )
            order by assignment.scheduledEndUtc asc, assignment.id asc
            """)
    List<Long> findExpiredScheduledIds(
            @Param("cutoff") Instant cutoff,
            Pageable pageable);

    long countByEmployeeIdAndStatusIn(
            Long employeeId,
            Collection<WorkScheduleStatus> statuses);
}
