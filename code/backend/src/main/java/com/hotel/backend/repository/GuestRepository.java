package com.hotel.backend.repository;

import com.hotel.backend.entity.Guest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface GuestRepository extends JpaRepository<Guest, Long> {

    List<Guest> findByReservationRoomId(Long reservationRoomId);

    @Query("""
        SELECT g FROM Guest g
        JOIN g.reservationRoom rr
        JOIN rr.reservationRoomType rrt
        WHERE rrt.reservation.id = :reservationId
    """)
    List<Guest> findAllByReservationId(@Param("reservationId") Long reservationId);

    boolean existsByReservationRoomIdAndIsPrimaryTrue(Long reservationRoomId);
    

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
