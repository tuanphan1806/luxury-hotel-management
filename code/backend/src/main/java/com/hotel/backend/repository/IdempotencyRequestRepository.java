package com.hotel.backend.repository;

import com.hotel.backend.entity.IdempotencyRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface IdempotencyRequestRepository
        extends JpaRepository<IdempotencyRequest, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT request
        FROM IdempotencyRequest request
        WHERE request.requestKey = :requestKey
          AND request.operation = :operation
          AND request.actorScope = :actorScope
    """)
    Optional<IdempotencyRequest> findForUpdate(
            @Param("requestKey") String requestKey,
            @Param("operation") String operation,
            @Param("actorScope") String actorScope);
}
