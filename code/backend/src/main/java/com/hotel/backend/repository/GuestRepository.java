package com.hotel.backend.repository;

import com.hotel.backend.entity.Guest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface GuestRepository extends JpaRepository<Guest, Long> {

    List<Guest> findByReservationRoomId(Long reservationRoomId);

    @Query("""
        SELECT g FROM Guest g
        JOIN FETCH g.reservationRoom rr
        JOIN FETCH rr.reservationRoomType rrt
        JOIN FETCH rrt.reservation r
        LEFT JOIN FETCH rr.room room
        ORDER BY g.id
    """)
    List<Guest> findAllWithStayDetails();

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM Guest g WHERE g.id = :guestId")
    java.util.Optional<Guest> findByIdForUpdate(
            @Param("guestId") Long guestId);

    @Query("""
        SELECT g FROM Guest g
        JOIN FETCH g.reservationRoom rr
        JOIN FETCH rr.reservationRoomType rrt
        JOIN FETCH rrt.reservation r
        LEFT JOIN FETCH rr.room room
        WHERE rrt.reservation.id = :reservationId
    """)
    List<Guest> findAllByReservationId(@Param("reservationId") Long reservationId);

    boolean existsByReservationRoomIdAndIsPrimaryTrue(Long reservationRoomId);

    boolean existsByReservationRoomIdAndIsPrimaryTrueAndCheckedOutAtIsNull(
            Long reservationRoomId);

    long countByReservationRoomId(Long reservationRoomId);

    long countByReservationRoomIdAndCheckedOutAtIsNull(
            Long reservationRoomId);

    @Query("""
        SELECT rr.reservationRoomType.id AS reservationRoomTypeId,
               COUNT(g.id) AS guestCount
        FROM Guest g
        JOIN g.reservationRoom rr
        WHERE rr.reservationRoomType.reservation.id = :reservationId
          AND g.checkedOutAt IS NULL
        GROUP BY rr.reservationRoomType.id
    """)
    List<ReservationLineGuestCountProjection> countActiveGuestsByReservationLine(
            @Param("reservationId") Long reservationId);
    

    @Query("""
        SELECT g FROM Guest g
        WHERE g.checkedOutAt IS NOT NULL
        AND g.checkedOutAt <= :cutoff
    """)
    List<Guest> findGuestsToCleanup(@Param("cutoff") LocalDateTime cutoff);
 
    @Modifying
    @Query("""
        DELETE FROM Guest g
        WHERE g.checkedOutAt IS NOT NULL
        AND g.checkedOutAt <= :cutoff
    """)
    int bulkDeleteExpiredGuests(@Param("cutoff") LocalDateTime cutoff);

    @Query("""
        SELECT g.id FROM Guest g
        JOIN g.reservationRoom rr
        JOIN rr.reservationRoomType rrt
        JOIN rrt.reservation r
        WHERE g.checkedOutAt IS NULL
          AND r.status = com.hotel.backend.constant.ReservationStatus.NO_SHOW
          AND r.checkOut <= :cutoff
    """)
    List<Long> findExpiredNoShowGuestIds(@Param("cutoff") LocalDateTime cutoff);
}
