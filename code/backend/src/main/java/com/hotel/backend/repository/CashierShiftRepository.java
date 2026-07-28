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

    Page<CashierShift> findAllByOpenedById(Long userId, Pageable pageable);

    long countByBusinessDateAndStatusIn(
            LocalDate businessDate,
            Collection<CashierShiftStatus> statuses);

    List<CashierShift> findAllByBusinessDate(LocalDate businessDate);
}
