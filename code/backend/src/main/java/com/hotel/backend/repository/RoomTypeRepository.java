package com.hotel.backend.repository;

import com.hotel.backend.entity.RoomType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RoomTypeRepository extends JpaRepository<RoomType, Long> {

    /**
     * Lấy tất cả room types kèm facilities.
     * JOIN FETCH + DISTINCT tránh N+1 và duplicate rows khi một room type có nhiều facility.
     */
    @Query("SELECT DISTINCT rt FROM RoomType rt LEFT JOIN FETCH rt.facilities ORDER BY rt.id ASC")
    List<RoomType> findAllWithFacilities();

    @Query("""
        SELECT DISTINCT rt FROM RoomType rt
        LEFT JOIN FETCH rt.facilities
        WHERE rt.active = true
        ORDER BY rt.id ASC
    """)
    List<RoomType> findAllActiveWithFacilities();

    /**
     * Lấy một room type kèm facilities theo ID.
     */
    @Query("SELECT rt FROM RoomType rt LEFT JOIN FETCH rt.facilities WHERE rt.id = :id")
    Optional<RoomType> findByIdWithFacilities(@Param("id") Long id);

    @Query("""
        SELECT rt FROM RoomType rt
        LEFT JOIN FETCH rt.facilities
        WHERE rt.id = :id AND rt.active = true
    """)
    Optional<RoomType> findActiveByIdWithFacilities(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rt FROM RoomType rt WHERE rt.id = :id")
    Optional<RoomType> findByIdForUpdate(@Param("id") Long id);

    /**
     * Kiểm tra trùng tên khi tạo mới.
     */
    boolean existsByTypeNameIgnoreCase(String typeName);

    /**
     * Kiểm tra trùng tên khi cập nhật (loại trừ chính bản ghi đang sửa).
     */
    boolean existsByTypeNameIgnoreCaseAndIdNot(String typeName, Long id);

    boolean existsByCode(String code);

    Optional<RoomType> findByCode(String code);

    Optional<RoomType> findByTypeName(String typeName);
 
    // Advance bookings use the calendar. A walk-in needs rooms ready for handover now.
    String SELLABLE_INVENTORY = """
        r.status <> 'MAINTENANCE' AND r.sellable = true AND r.decommissionedAt IS NULL
        AND (:immediate = false OR r.status <> 'AVAILABLE' OR r.cleaningStatus = 'CLEAN')
        """;
    @Query("""
        SELECT COUNT(r) FROM Room r
        WHERE r.roomType.id = :roomTypeId AND
        """ + SELLABLE_INVENTORY)
    int countAvailableRoomsByType(@Param("roomTypeId") Long roomTypeId,
                                  @Param("immediate") boolean immediate);

    /**
     * Batch variant for the public availability screen. A missing room type
     * in the result means zero sellable rooms.
     */
    @Query("""
        SELECT r.roomType.id AS roomTypeId, COUNT(r) AS quantity
        FROM Room r
        WHERE
        """ + SELLABLE_INVENTORY + " GROUP BY r.roomType.id")
    List<RoomTypeQuantityProjection> countAvailableRoomsGroupedByType(@Param("immediate") boolean immediate);
 
}
