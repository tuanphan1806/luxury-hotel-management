package com.hotel.backend.repository;

import com.hotel.backend.constant.RoomStatus;
import com.hotel.backend.constant.CleaningStatus;
import com.hotel.backend.entity.Room;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import java.util.List;
import java.util.Optional;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {

       @Override
       @EntityGraph(attributePaths = "roomType")
       List<Room> findAll();

       @Override
       @EntityGraph(attributePaths = "roomType")
       Page<Room> findAll(Pageable pageable);

       @EntityGraph(attributePaths = {"roomType", "maintenanceHistory"})
       @Query("SELECT DISTINCT r FROM Room r WHERE r.id = :id")
       Optional<Room> findDetailById(@Param("id") Long id);

       boolean existsByRoomName(String roomName);       
       Optional<Room> findByRoomName(String roomName);
       List<Room> findByStatus(RoomStatus status);      
       long countByStatus(RoomStatus status);
       long countByCleaningStatus(CleaningStatus cleaningStatus);
       List<Room> findByRoomTypeId(Long roomTypeId);    
       long countByRoomTypeId(Long roomTypeId);

       @Lock(LockModeType.PESSIMISTIC_WRITE)
       @Query("SELECT r FROM Room r WHERE r.id = :id")
       Optional<Room> findByIdForUpdate(@Param("id") Long id);

       @Query("SELECT r FROM Room r JOIN FETCH r.roomType rt WHERE " +
              "(:keyword IS NULL OR LOWER(r.roomName) LIKE CONCAT('%', LOWER(CAST(:keyword AS string)), '%') " +
              " OR LOWER(rt.typeName) LIKE CONCAT('%', LOWER(CAST(:keyword AS string)), '%'))"+
              "AND (:status IS NULL OR r.status = :status) " +
              "AND (:cleaningStatus IS NULL OR r.cleaningStatus = :cleaningStatus)")
       List<Room> search(@Param("keyword") String keyword,
                         @Param("status") RoomStatus status,
                         @Param("cleaningStatus") CleaningStatus cleaningStatus);   


       @Query(value = "SELECT r FROM Room r JOIN FETCH r.roomType rt WHERE LOWER(r.roomName) LIKE :keyword OR LOWER(rt.typeName) LIKE :keyword OR LOWER(r.status) LIKE :keyword or LOWER(r.cleaningStatus) LIKE :keyword",
              countQuery = "SELECT COUNT(r) FROM Room r JOIN r.roomType rt WHERE LOWER(r.roomName) LIKE :keyword OR LOWER(rt.typeName) LIKE :keyword OR LOWER(r.status) LIKE :keyword or LOWER(r.cleaningStatus) LIKE :keyword")
       Page<Room> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

       @Query("""
              SELECT r FROM Room r
              JOIN FETCH r.roomType roomType
              WHERE r.roomType.id = :roomTypeId
                AND r.status = com.hotel.backend.constant.RoomStatus.AVAILABLE
                AND r.cleaningStatus = com.hotel.backend.constant.CleaningStatus.CLEAN
                AND r.sellable = true
                AND r.decommissionedAt IS NULL
                AND NOT EXISTS (
                    SELECT 1 FROM ReservationRoom rr
                    JOIN rr.reservationRoomType rrt
                    JOIN rrt.reservation res
                    WHERE rr.room.id = r.id
                      AND res.id <> :reservationId
                      AND rr.status IN (
                          com.hotel.backend.constant.AssignStatus.ASSIGNED,
                          com.hotel.backend.constant.AssignStatus.CHECKED_IN
                      )
                      AND res.status NOT IN (
                          com.hotel.backend.constant.ReservationStatus.CANCELLED,
                          com.hotel.backend.constant.ReservationStatus.CHECKED_OUT
                      )
                      AND res.checkIn < :checkOut
                      AND COALESCE(res.inventoryProtectedUntil, res.checkOut) > :checkIn
                )
              ORDER BY r.floor ASC, r.roomName ASC
              """)
       List<Room> findAvailableRoomsForReservationRoomType(
              @Param("reservationId") Long reservationId,
              @Param("roomTypeId") Long roomTypeId,
              @Param("checkIn") java.time.LocalDateTime checkIn,
              @Param("checkOut") java.time.LocalDateTime checkOut);
       
}
