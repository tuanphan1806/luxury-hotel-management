package com.hotel.backend.repository;

import com.hotel.backend.entity.WorkShiftTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkShiftTemplateRepository extends JpaRepository<WorkShiftTemplate, Long> {
    List<WorkShiftTemplate> findAllByOrderBySortOrderAscStartTimeAscIdAsc();
    List<WorkShiftTemplate> findAllByActiveTrueOrderBySortOrderAscStartTimeAscIdAsc();
    boolean existsByCodeIgnoreCase(String code);
    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);
}
