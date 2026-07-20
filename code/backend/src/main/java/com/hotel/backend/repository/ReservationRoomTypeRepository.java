package com.hotel.backend.repository;

import com.hotel.backend.entity.ReservationRoomType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ReservationRoomTypeRepository extends JpaRepository<ReservationRoomType, Long> {

    long countByReservationId(Long reservationId);

    @Query("""
        SELECT rrt
        FROM ReservationRoomType rrt
        JOIN FETCH rrt.roomType
        LEFT JOIN FETCH rrt.roomHold
        WHERE rrt.reservation.id = :reservationId
        ORDER BY rrt.roomType.id
    """)
    List<ReservationRoomType> findDetailsByReservationId(
        @Param("reservationId") Long reservationId
    );

    // Đếm số phòng đã được confirm/check-in trong khoảng ngày (dùng cho availability check)
    @Query("""
        SELECT COALESCE(SUM(rrt.quantity), 0)
        FROM ReservationRoomType rrt
        JOIN rrt.reservation r
        WHERE rrt.roomType.id = :roomTypeId
        AND r.status IN ('DRAFT', 'CANCELLATION_PENDING', 'CONFIRMED', 'CHECKED_IN')
        AND r.checkIn  < :checkOut
        AND r.checkOut > :checkIn
    """)
    int countBookedQuantity(
        @Param("roomTypeId") Long roomTypeId,
        @Param("checkIn")    LocalDateTime checkIn,
        @Param("checkOut")   LocalDateTime checkOut
    );

    // Đếm số phòng đã được confirm/check-in, trừ reservation hiện tại (dùng khi update)
    @Query("""
        SELECT COALESCE(SUM(rrt.quantity), 0)
        FROM ReservationRoomType rrt
        JOIN rrt.reservation r
        WHERE rrt.roomType.id  = :roomTypeId
        AND r.id              != :excludeReservationId
        AND r.status IN ('DRAFT', 'CANCELLATION_PENDING', 'CONFIRMED', 'CHECKED_IN')
        AND r.checkIn  < :checkOut
        AND r.checkOut > :checkIn
    """)
    int countBookedQuantityExcluding(
        @Param("roomTypeId")           Long roomTypeId,
        @Param("excludeReservationId") Long excludeReservationId,
        @Param("checkIn")              LocalDateTime checkIn,
        @Param("checkOut")             LocalDateTime checkOut
    );
}
