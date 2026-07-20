package com.hotel.backend.repository;

import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.entity.ReconciliationState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface ReconciliationStateRepository
        extends JpaRepository<ReconciliationState, Long> {

    Optional<ReconciliationState> findByProviderAndMerchantAccountId(
            PaymentProvider provider,
            String merchantAccountId);

    List<ReconciliationState> findByProviderOrderByLastRunAtUtcDesc(PaymentProvider provider);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT state
            FROM ReconciliationState state
            WHERE state.provider = :provider
              AND state.merchantAccountId = :merchantAccountId
            """)
    Optional<ReconciliationState> findForUpdate(
            @Param("provider") PaymentProvider provider,
            @Param("merchantAccountId") String merchantAccountId);
}
