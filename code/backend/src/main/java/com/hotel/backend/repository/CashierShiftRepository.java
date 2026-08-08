package com.hotel.backend.repository;

import com.hotel.backend.constant.CashierShiftStatus;
import com.hotel.backend.entity.CashierShift;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.time.LocalDate;
import java.util.List;
import java.math.BigDecimal;

public interface CashierShiftRepository extends JpaRepository<CashierShift, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select shift from CashierShift shift
            where shift.openedBy.id = :userId
              and shift.status in :statuses
            """)
    Optional<CashierShift> findActiveByUserIdForUpdate(
            @Param("userId") Long userId,
            @Param("statuses") Collection<CashierShiftStatus> statuses);

    Optional<CashierShift> findFirstByOpenedByIdAndStatusInOrderByOpenedAtUtcDesc(
            Long userId,
            Collection<CashierShiftStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select shift from CashierShift shift where shift.id = :id")
    Optional<CashierShift> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select shift from CashierShift shift
            where shift.workShiftSession.id = :sessionId
            """)
    Optional<CashierShift> findByWorkShiftSessionIdForUpdate(
            @Param("sessionId") Long sessionId);

    Page<CashierShift> findAllByOpenedById(Long userId, Pageable pageable);

    long countByBusinessDateAndStatusIn(
            LocalDate businessDate,
            Collection<CashierShiftStatus> statuses);

    List<CashierShift> findAllByBusinessDate(LocalDate businessDate);

    @Query("""
            select coalesce(sum(shift.varianceAmount), 0)
            from CashierShift shift
            where shift.businessDate = :businessDate
            """)
    BigDecimal sumVarianceByBusinessDate(@Param("businessDate") LocalDate businessDate);

    @Query("""
            select (count(shift.id) > 0) from CashierShift shift
            join shift.workShiftSession session
            join session.assignment assignment
            where assignment.shiftTemplate.id = :shiftTemplateId
              and assignment.workDate = :workDate
              and shift.status in :statuses
            """)
    boolean existsActiveForWorkShift(
            @Param("shiftTemplateId") Long shiftTemplateId,
            @Param("workDate") LocalDate workDate,
            @Param("statuses") Collection<CashierShiftStatus> statuses);
}
