package com.hotel.backend.repository;

import com.hotel.backend.entity.RoomType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    /**
     * Lấy một room type kèm facilities theo ID.
     */
    @Query("SELECT rt FROM RoomType rt LEFT JOIN FETCH rt.facilities WHERE rt.id = :id")
    Optional<RoomType> findByIdWithFacilities(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rt FROM RoomType rt WHERE rt.id = :id")
    Optional<RoomType> findByIdForUpdate(@Param("id") Long id);

    /**
     * Lọc theo khoảng giá — dùng cho API filter.
     */
    @Query("""
        SELECT DISTINCT rt FROM RoomType rt
        LEFT JOIN FETCH rt.facilities
        WHERE rt.price BETWEEN :minPrice AND :maxPrice
        ORDER BY rt.price ASC
    """)
    List<RoomType> findByPriceBetweenOrderByPriceAsc(
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice);

    /**
     * Kiểm tra trùng tên khi tạo mới.
     */
    boolean existsByTypeNameIgnoreCase(String typeName);

    /**
     * Kiểm tra trùng tên khi cập nhật (loại trừ chính bản ghi đang sửa).
     */
    boolean existsByTypeNameIgnoreCaseAndIdNot(String typeName, Long id);

    Optional<RoomType> findByTypeName(String typeName);
 
    // Tổng số phòng của 1 room type (dùng cho availability check)
    @Query("""
        SELECT COUNT(r) FROM Room r
            WHERE r.roomType.id = :roomTypeId
            AND r.status != 'MAINTENANCE'
            AND r.sellable = true
            AND r.decommissionedAt IS NULL
    """)
    int countAvailableRoomsByType(@Param("roomTypeId") Long roomTypeId);
 
    // Lấy tất cả room type còn phòng trống trong khoảng ngày
    @Query("""
        SELECT rt FROM RoomType rt
        WHERE (
            SELECT COUNT(r) FROM Room r
            WHERE r.roomType = rt
            AND r.status != 'MAINTENANCE'
            AND r.sellable = true
            AND r.decommissionedAt IS NULL
        ) > (
            SELECT COALESCE(SUM(rrt.quantity), 0)
            FROM ReservationRoomType rrt
            JOIN rrt.reservation res
            WHERE rrt.roomType = rt
            AND res.status IN ('DRAFT', 'CANCELLATION_PENDING', 'CONFIRMED', 'CHECKED_IN')
            AND res.checkIn  < :checkOut
            AND res.checkOut > :checkIn
        )
    """)
    List<RoomType> findAvailableRoomTypes(
        @Param("checkIn")  LocalDateTime checkIn,
        @Param("checkOut") LocalDateTime checkOut
    );
}
