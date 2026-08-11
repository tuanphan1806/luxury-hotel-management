package com.hotel.backend.repository;

import com.hotel.backend.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Collection;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {

    boolean existsByUserIdAndReservationId(Long userId, Long reservationId);

    Optional<Review> findByUserIdAndReservationId(Long userId, Long reservationId);

    boolean existsByUserIdAndReservationIdAndRoomTypeId(Long userId, Long reservationId, Long roomTypeId);

    Optional<Review> findByUserIdAndReservationIdAndRoomTypeId(Long userId, Long reservationId, Long roomTypeId);

    @EntityGraph(attributePaths = {"user", "roomType", "reservation"})
    Page<Review> findByRoomTypeId(Long roomTypeId, Pageable pageable);

    List<Review> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("SELECT COALESCE(AVG(r.rating), 0.0) FROM Review r WHERE r.roomType.id = :roomTypeId")
    Double getAverageRatingByRoomType(@Param("roomTypeId") Long roomTypeId);

    @Query("SELECT COUNT(r) FROM Review r WHERE r.roomType.id = :roomTypeId")
    long countByRoomTypeId(@Param("roomTypeId") Long roomTypeId);

    /**
     * Trả thống kê của nhiều loại phòng trong một truy vấn, tránh gọi AVG/COUNT
     * riêng cho từng card ở trang danh sách phòng.
     */
    @Query("""
        SELECT r.roomType.id AS roomTypeId,
               AVG(r.rating) AS averageRating,
               COUNT(r.id) AS totalReviews
        FROM Review r
        WHERE r.roomType.id IN :roomTypeIds
        GROUP BY r.roomType.id
    """)
    List<RoomTypeRatingSummary> summarizeByRoomTypeIds(
            @Param("roomTypeIds") Collection<Long> roomTypeIds);

    interface RoomTypeRatingSummary {
        Long getRoomTypeId();
        Double getAverageRating();
        Long getTotalReviews();
    }

}
