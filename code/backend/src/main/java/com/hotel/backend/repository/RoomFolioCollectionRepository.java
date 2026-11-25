package com.hotel.backend.repository;

import com.hotel.backend.entity.RoomFolioCollection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RoomFolioCollectionRepository extends JpaRepository<RoomFolioCollection, Long> {
    Optional<RoomFolioCollection> findByReservationIdAndClosedAtIsNull(Long reservationId);
}
