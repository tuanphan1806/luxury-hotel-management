package com.hotel.backend.repository;

import com.hotel.backend.constant.ReservationServiceStatus;
import com.hotel.backend.entity.ReservationServiceOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReservationServiceOrderRepository
        extends JpaRepository<ReservationServiceOrder, Long> {

    @Query("""
            select orderLine from ReservationServiceOrder orderLine
            join fetch orderLine.service
            where orderLine.reservation.id = :reservationId
            order by orderLine.createdAt asc, orderLine.id asc
            """)
    List<ReservationServiceOrder> findDetailedByReservationId(
            @Param("reservationId") Long reservationId);

    @Query("""
            select orderLine from ReservationServiceOrder orderLine
            where orderLine.reservation.id = :reservationId
            order by orderLine.id asc
            """)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ReservationServiceOrder> findByReservationIdForUpdate(
            @Param("reservationId") Long reservationId);

    @Query("""
            select orderLine from ReservationServiceOrder orderLine
            join fetch orderLine.service
            where orderLine.id = :orderId and orderLine.reservation.id = :reservationId
            """)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ReservationServiceOrder> findByIdAndReservationIdForUpdate(
            @Param("orderId") Long orderId,
            @Param("reservationId") Long reservationId);

    List<ReservationServiceOrder> findByReservationIdAndStatusInOrderByCreatedAtAscIdAsc(
            Long reservationId,
            Collection<ReservationServiceStatus> statuses);

    boolean existsByReservationIdAndStatusIn(
            Long reservationId,
            Collection<ReservationServiceStatus> statuses);

    long countByStatusIn(Collection<ReservationServiceStatus> statuses);

    @Query("""
            select coalesce(sum(orderLine.totalPrice), 0)
            from ReservationServiceOrder orderLine
            where orderLine.reservation.id = :reservationId
              and orderLine.status in :statuses
            """)
    BigDecimal sumTotalPriceByReservationIdAndStatusIn(
            @Param("reservationId") Long reservationId,
            @Param("statuses") Collection<ReservationServiceStatus> statuses);
}
