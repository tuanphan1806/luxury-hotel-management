package com.hotel.backend.repository;

import com.hotel.backend.entity.WorkShiftRequirement;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WorkShiftRequirementRepository
        extends JpaRepository<WorkShiftRequirement, Long> {

    @EntityGraph(attributePaths = "shiftTemplate")
    List<WorkShiftRequirement> findAllByWorkDateBetweenOrderByWorkDateAsc(
            LocalDate from,
            LocalDate to);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select requirement from WorkShiftRequirement requirement
            join fetch requirement.shiftTemplate
            where requirement.shiftTemplate.id = :shiftTemplateId
              and requirement.workDate = :workDate
            """)
    Optional<WorkShiftRequirement> findForUpdate(
            @Param("shiftTemplateId") Long shiftTemplateId,
            @Param("workDate") LocalDate workDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select requirement from WorkShiftRequirement requirement
            join fetch requirement.shiftTemplate
            where requirement.id = :id
            """)
    Optional<WorkShiftRequirement> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = "shiftTemplate")
    Optional<WorkShiftRequirement> findByShiftTemplateIdAndWorkDate(
            Long shiftTemplateId,
            LocalDate workDate);

    @EntityGraph(attributePaths = "shiftTemplate")
    List<WorkShiftRequirement> findAllByShiftTemplateIdInAndWorkDateBetween(
            List<Long> shiftTemplateIds,
            LocalDate from,
            LocalDate to);

    @Query("""
            select requirement.id from WorkShiftRequirement requirement
            where requirement.status = 'OPEN'
              and requirement.workDate <= :throughDate
            order by requirement.workDate asc,
                     requirement.sortOrderSnapshot asc,
                     requirement.id asc
            """)
    List<Long> findOpenIdsThroughDate(
            @Param("throughDate") LocalDate throughDate,
            Pageable pageable);
}
