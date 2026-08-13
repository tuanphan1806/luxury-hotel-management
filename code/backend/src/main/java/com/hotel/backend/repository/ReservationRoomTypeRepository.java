package com.hotel.backend.repository;

import com.hotel.backend.entity.ReservationRoomType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface ReservationRoomTypeRepository extends JpaRepository<ReservationRoomType, Long> {

    long countByReservationId(Long reservationId);

    long countByRoomTypeId(Long roomTypeId);

    /**
     * Finds active reservations whose committed guest allocation would no
     * longer fit after an administrator reduces a room type's hard capacity.
     * Legacy active lines without an explicit allocation are returned as a
     * conservative conflict instead of silently making check-in unsafe.
     */
    @Query("""
        SELECT r.reservationCode
        FROM ReservationRoomType rrt
        JOIN rrt.reservation r
        WHERE rrt.roomType.id = :roomTypeId
          AND r.status IN ('PAYMENT_PENDING', 'DRAFT',
                           'CANCELLATION_PENDING', 'CONFIRMED', 'CHECKED_IN')
          AND (
              rrt.lineGuestCount IS NULL
              OR rrt.lineGuestCount > (:maxGuests * rrt.quantity)
          )
        ORDER BY r.checkIn, r.id
    """)
    List<String> findActiveReservationCodesExceedingCapacity(
        @Param("roomTypeId") Long roomTypeId,
        @Param("maxGuests") int maxGuests
    );

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

    @Query("""
        SELECT DISTINCT rrt
        FROM ReservationRoomType rrt
        JOIN FETCH rrt.roomType
        LEFT JOIN FETCH rrt.roomHold
        LEFT JOIN FETCH rrt.rooms assignedRoom
        LEFT JOIN FETCH assignedRoom.room
        WHERE rrt.reservation.id IN :reservationIds
        ORDER BY rrt.reservation.id, rrt.roomType.id
    """)
    List<ReservationRoomType> findDetailsByReservationIds(
        @Param("reservationIds") Collection<Long> reservationIds
    );

    // Đếm số phòng đã được confirm/check-in trong khoảng ngày (dùng cho availability check)
    @Query("""
        SELECT COALESCE(SUM(rrt.quantity), 0)
        FROM ReservationRoomType rrt
        JOIN rrt.reservation r
        WHERE rrt.roomType.id = :roomTypeId
        AND r.status IN ('DRAFT', 'CANCELLATION_PENDING', 'CONFIRMED', 'CHECKED_IN')
        AND r.checkIn < :checkOut
        AND COALESCE(r.inventoryProtectedUntil, r.checkOut) > :checkIn
    """)
    int countBookedQuantity(
        @Param("roomTypeId") Long roomTypeId,
        @Param("checkIn")    LocalDateTime checkIn,
        @Param("checkOut")   LocalDateTime checkOut
    );

    /**
     * Batch variant used when displaying every room type on the public
     * availability screen.
     */
    @Query("""
        SELECT rrt.roomType.id AS roomTypeId,
               COALESCE(SUM(rrt.quantity), 0) AS quantity
        FROM ReservationRoomType rrt
        JOIN rrt.reservation r
        WHERE r.status IN ('DRAFT', 'CANCELLATION_PENDING', 'CONFIRMED', 'CHECKED_IN')
          AND r.checkIn < :checkOut
          AND COALESCE(r.inventoryProtectedUntil, r.checkOut) > :checkIn
        GROUP BY rrt.roomType.id
    """)
    List<RoomTypeQuantityProjection> countBookedQuantitiesGroupedByType(
        @Param("checkIn") LocalDateTime checkIn,
        @Param("checkOut") LocalDateTime checkOut
    );

    // Đếm số phòng đã được confirm/check-in, trừ reservation hiện tại (dùng khi update)
    @Query("""
        SELECT COALESCE(SUM(rrt.quantity), 0)
        FROM ReservationRoomType rrt
        JOIN rrt.reservation r
        WHERE rrt.roomType.id  = :roomTypeId
        AND r.id              != :excludeReservationId
        AND r.status IN ('DRAFT', 'CANCELLATION_PENDING', 'CONFIRMED', 'CHECKED_IN')
        AND r.checkIn < :checkOut
        AND COALESCE(r.inventoryProtectedUntil, r.checkOut) > :checkIn
    """)
    int countBookedQuantityExcluding(
        @Param("roomTypeId")           Long roomTypeId,
        @Param("excludeReservationId") Long excludeReservationId,
        @Param("checkIn")              LocalDateTime checkIn,
        @Param("checkOut")             LocalDateTime checkOut
    );
}
