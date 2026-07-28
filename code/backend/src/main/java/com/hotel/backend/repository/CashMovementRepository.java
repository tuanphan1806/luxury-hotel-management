package com.hotel.backend.repository;

import com.hotel.backend.constant.CashMovementSourceType;
import com.hotel.backend.constant.CashMovementType;
import com.hotel.backend.entity.CashMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface CashMovementRepository extends JpaRepository<CashMovement, Long> {

    Optional<CashMovement> findBySourceTypeAndSourceIdAndMovementType(
            CashMovementSourceType sourceType,
            String sourceId,
            CashMovementType movementType);

    List<CashMovement> findAllByCashierShiftIdOrderByOccurredAtUtcAscIdAsc(Long cashierShiftId);

    long countByCashierShiftId(Long cashierShiftId);

    @Query(value = """
            select coalesce(sum(case when direction = 'IN' then amount else -amount end), 0)
            from cash_movements
            where cashier_shift_id = :shiftId
            """, nativeQuery = true)
    BigDecimal calculateExpectedCash(@Param("shiftId") Long shiftId);

    @Query(value = """
            select cashier_shift_id as "shiftId",
                   count(*) as "movementCount",
                   coalesce(sum(case when direction = 'IN' then amount else -amount end), 0)
                       as "expectedCash"
            from cash_movements
            where cashier_shift_id in (:shiftIds)
            group by cashier_shift_id
            """, nativeQuery = true)
    List<CashShiftMovementSummary> summarizeByCashierShiftIds(
            @Param("shiftIds") Collection<Long> shiftIds);

    interface CashShiftMovementSummary {
        Long getShiftId();
        Long getMovementCount();
        BigDecimal getExpectedCash();
    }
}
