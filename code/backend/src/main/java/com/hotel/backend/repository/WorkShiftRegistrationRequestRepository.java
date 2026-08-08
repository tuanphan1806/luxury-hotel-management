package com.hotel.backend.repository;

import com.hotel.backend.constant.WorkShiftRegistrationStatus;
import com.hotel.backend.entity.WorkShiftRegistrationRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WorkShiftRegistrationRequestRepository
        extends JpaRepository<WorkShiftRegistrationRequest, Long> {

    @EntityGraph(attributePaths = {
            "employee", "shiftTemplate", "reviewedBy", "assignment"})
    @Query("""
            select request from WorkShiftRegistrationRequest request
            where request.workDate between :from and :to
              and (:employeeId is null or request.employee.id = :employeeId)
            order by request.workDate asc,
                     request.shiftTemplate.sortOrder asc,
                     request.createdAtUtc desc,
                     request.id desc
            """)
    List<WorkShiftRegistrationRequest> findInWindow(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("employeeId") Long employeeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request from WorkShiftRegistrationRequest request
            join fetch request.employee
            join fetch request.shiftTemplate
            left join fetch request.reviewedBy
            left join fetch request.assignment
            where request.id = :id
            """)
    Optional<WorkShiftRegistrationRequest> findByIdForUpdate(@Param("id") Long id);

    boolean existsByEmployeeIdAndShiftTemplateIdAndWorkDateAndStatus(
            Long employeeId,
            Long shiftTemplateId,
            LocalDate workDate,
            WorkShiftRegistrationStatus status);

    boolean existsByShiftTemplateIdAndWorkDateAndStatus(
            Long shiftTemplateId,
            LocalDate workDate,
            WorkShiftRegistrationStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request from WorkShiftRegistrationRequest request
            join fetch request.employee
            where request.shiftTemplate.id = :shiftTemplateId
              and request.workDate = :workDate
              and request.status = :status
            order by request.id asc
            """)
    List<WorkShiftRegistrationRequest> findSlotByStatusForUpdate(
            @Param("shiftTemplateId") Long shiftTemplateId,
            @Param("workDate") LocalDate workDate,
            @Param("status") WorkShiftRegistrationStatus status);
}
