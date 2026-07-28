package com.hotel.backend.repository;

import com.hotel.backend.entity.BusinessDayCloseLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface BusinessDayCloseLockRepository
        extends JpaRepository<BusinessDayCloseLock, LocalDate> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from BusinessDayCloseLock item where item.businessDate = :businessDate")
    Optional<BusinessDayCloseLock> findByBusinessDateForUpdate(
            @Param("businessDate") LocalDate businessDate);
}
