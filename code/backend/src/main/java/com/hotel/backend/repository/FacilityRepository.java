package com.hotel.backend.repository;

import com.hotel.backend.entity.Facility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface FacilityRepository extends JpaRepository<Facility, Long> {

    /**
     * Lọc theo type (không phân biệt hoa thường), sắp xếp theo tên.
     */
    List<Facility> findByTypeIgnoreCaseOrderByFacilityNameAsc(String type);

    /**
     * Tìm kiếm theo tên — dùng cho thanh search.
     */
    List<Facility> findByFacilityNameContainingIgnoreCaseOrderByFacilityNameAsc(String keyword);

    /**
     * Lấy nhiều Facility theo tập ID — dùng khi gán facilities vào RoomType.
     */
    @Query("SELECT f FROM Facility f WHERE f.id IN :ids")
    Set<Facility> findAllByIdIn(@Param("ids") Set<Long> ids);

    boolean existsByFacilityNameIgnoreCase(String facilityName);

    Optional<Facility> findByFacilityNameIgnoreCase(String facilityName);

    boolean existsByFacilityNameIgnoreCaseAndIdNot(String facilityName, Long id);
}
