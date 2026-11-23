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

    @Query("""
        SELECT DISTINCT line.id AS lineId, line.roomType.id AS roomTypeId,
               assigned.room.id AS assignedRoomId, line.quantity AS quantity, r.status AS reservationStatus,
               CASE WHEN r.actualCheckIn < r.checkIn THEN r.actualCheckIn ELSE r.checkIn END AS startsAt,
               COALESCE(r.inventoryProtectedUntil, r.checkOut) AS endsAt
        FROM ReservationRoomType line JOIN line.reservation r
        LEFT JOIN line.roomHold hold
        LEFT JOIN line.rooms assigned ON assigned.status = 'ASSIGNED' AND assigned.room IS NOT NULL
        WHERE line.roomType.id IN :roomTypeIds
          AND (:excludeReservationId IS NULL OR r.id <> :excludeReservationId)
          AND (r.status IN ('DRAFT', 'CANCELLATION_PENDING', 'CONFIRMED', 'CHECKED_IN')
               OR (r.status = 'PAYMENT_PENDING' AND hold.status = 'ACTIVE' AND hold.expiresAt > :now))
          AND (r.checkIn < :checkOut OR r.actualCheckIn < :checkOut)
          AND (COALESCE(r.inventoryProtectedUntil, r.checkOut) > :checkIn
               OR (r.status = 'CHECKED_IN' AND COALESCE(r.inventoryProtectedUntil, r.checkOut) <= :overdueAsOf))
        """)
    List<RoomDemandPeriod> findInventoryPeriods(
        @Param("roomTypeIds") Collection<Long> roomTypeIds,
        @Param("excludeReservationId") Long excludeReservationId,
        @Param("checkIn") LocalDateTime checkIn,
        @Param("checkOut") LocalDateTime checkOut,
        @Param("now") LocalDateTime now,
        @Param("overdueAsOf") LocalDateTime overdueAsOf
    );
}
