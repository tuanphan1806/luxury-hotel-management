package com.hotel.backend.repository;

import com.hotel.backend.entity.ReservationRateSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReservationRateSnapshotRepository
        extends JpaRepository<ReservationRateSnapshot, Long> {

    List<ReservationRateSnapshot>
    findByReservationRoomTypeIdOrderBySnapshotSequenceAsc(
            Long reservationRoomTypeId);

    Optional<ReservationRateSnapshot>
    findFirstByReservationRoomTypeIdOrderBySnapshotSequenceDesc(
            Long reservationRoomTypeId);

    @Query("""
        SELECT snapshot
        FROM ReservationRateSnapshot snapshot
        JOIN FETCH snapshot.reservationRoomType reservationLine
        WHERE reservationLine.reservation.id = :reservationId
        ORDER BY reservationLine.id, snapshot.snapshotSequence
    """)
    List<ReservationRateSnapshot>
    findByReservationIdOrderByLineAndSequence(
            @Param("reservationId") Long reservationId);
}
