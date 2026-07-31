package com.hotel.backend.repository;

import com.hotel.backend.entity.WorkShiftTemplate;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkShiftTemplateRepository extends JpaRepository<WorkShiftTemplate, Long> {
    List<WorkShiftTemplate> findAllByOrderBySortOrderAscStartTimeAscIdAsc();
    List<WorkShiftTemplate> findAllByActiveTrueOrderBySortOrderAscStartTimeAscIdAsc();
    boolean existsByCodeIgnoreCase(String code);
    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select template from WorkShiftTemplate template where template.id = :id")
    Optional<WorkShiftTemplate> findByIdForUpdate(@Param("id") Long id);
}
