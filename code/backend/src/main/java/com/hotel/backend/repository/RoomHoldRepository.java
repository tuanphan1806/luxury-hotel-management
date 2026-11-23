package com.hotel.backend.repository;

import com.hotel.backend.constant.HoldStatus;
import com.hotel.backend.entity.RoomHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RoomHoldRepository extends JpaRepository<RoomHold, Long> {

    Optional<RoomHold> findByReservationRoomTypeId(Long reservationRoomTypeId);

    List<RoomHold> findByStatus(HoldStatus status);
    boolean existsByStatus(HoldStatus status);

    @Query("""
        SELECT DISTINCT r.id
        FROM RoomHold rh
        JOIN rh.reservationRoomType rrt
        JOIN rrt.reservation r
        WHERE rh.status = 'ACTIVE'
          AND rh.expiresAt <= :now
        ORDER BY r.id
    """)
    List<Long> findReservationIdsWithExpiredActiveHolds(@Param("now") LocalDateTime now);

    @Query("""
        SELECT rh
        FROM RoomHold rh
        JOIN rh.reservationRoomType rrt
        WHERE rrt.reservation.id = :reservationId
        ORDER BY rh.id
    """)
    List<RoomHold> findByReservationId(@Param("reservationId") Long reservationId);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT rh
        FROM RoomHold rh
        JOIN rh.reservationRoomType rrt
        WHERE rrt.reservation.id = :reservationId
        ORDER BY rh.id
    """)
    List<RoomHold> findByReservationIdForUpdate(@Param("reservationId") Long reservationId);


    // Lấy tất cả hold ACTIVE đã hết hạn (dùng cho scheduler)
    @Query("""
        SELECT rh FROM RoomHold rh
        WHERE rh.status    = 'ACTIVE'
        AND rh.expiresAt  <= :now
    """)
    List<RoomHold> findExpiredActiveHolds(@Param("now") LocalDateTime now);

    // Bulk update hold EXPIRED (dùng cho scheduler)
    @Modifying
    @Query("""
        UPDATE RoomHold rh
        SET rh.status = 'EXPIRED'
        WHERE rh.status   = 'ACTIVE'
        AND rh.expiresAt <= :now
    """)
    int bulkExpireHolds(@Param("now") LocalDateTime now);
}
