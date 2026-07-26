package com.hotel.backend.repository;

import com.hotel.backend.entity.AddOnService;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AddOnServiceRepository extends JpaRepository<AddOnService, Long> {

    List<AddOnService> findByActiveTrueOrderBySortOrderAscNameAsc();

    List<AddOnService> findAllByOrderBySortOrderAscNameAsc();

    Optional<AddOnService> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select service from AddOnService service where service.id = :id")
    Optional<AddOnService> findByIdForUpdate(@Param("id") Long id);
}
