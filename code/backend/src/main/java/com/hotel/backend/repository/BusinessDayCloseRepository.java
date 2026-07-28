package com.hotel.backend.repository;

import com.hotel.backend.entity.BusinessDayClose;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface BusinessDayCloseRepository extends JpaRepository<BusinessDayClose, Long> {
    Optional<BusinessDayClose> findByBusinessDate(LocalDate businessDate);
    boolean existsByBusinessDate(LocalDate businessDate);
    Page<BusinessDayClose> findAllByOrderByBusinessDateDesc(Pageable pageable);
}
