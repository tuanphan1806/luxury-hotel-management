package com.hotel.backend.service;

import com.hotel.backend.config.SePayConfig;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.entity.ReconciliationState;
import com.hotel.backend.repository.ReconciliationStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReconciliationStateService {

    private final ReconciliationStateRepository repository;
    private final SePayConfig sePayConfig;

    @Transactional(readOnly = true)
    public Instant queryFromUtc(PaymentProvider provider, String merchantAccountId) {
        Optional<ReconciliationState> state = repository
                .findByProviderAndMerchantAccountId(provider, merchantAccountId);
        Instant lastOccurredAt = state
                .map(ReconciliationState::getLastSuccessfulOccurredAtUtc)
                .orElse(null);
        if (lastOccurredAt == null) {
            return Instant.now().minus(Duration.ofHours(
                    Math.max(1, sePayConfig.getReconciliationLookbackHours())));
        }
        return lastOccurredAt.minus(Duration.ofMinutes(
                Math.max(0, sePayConfig.getReconciliationOverlapMinutes())));
    }

    /** Cursor advances after durable ingestion, regardless of terminal outcome. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDurableEvent(
            PaymentProvider provider,
            String merchantAccountId,
            String providerCursor,
            Instant providerOccurredAtUtc) {
        ReconciliationState state = lockOrCreate(provider, merchantAccountId);
        if (providerOccurredAtUtc != null
                && (state.getLastSuccessfulOccurredAtUtc() == null
                || !providerOccurredAtUtc.isBefore(state.getLastSuccessfulOccurredAtUtc()))) {
            state.setLastSuccessfulOccurredAtUtc(providerOccurredAtUtc);
            state.setCursorValue(providerCursor);
        } else if (state.getCursorValue() == null && providerCursor != null) {
            state.setCursorValue(providerCursor);
        }
        state.setLastRunAtUtc(Instant.now());
        state.setLastError(null);
        repository.save(state);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRunSuccess(PaymentProvider provider, String merchantAccountId) {
        ReconciliationState state = lockOrCreate(provider, merchantAccountId);
        state.setLastRunAtUtc(Instant.now());
        state.setLastError(null);
        repository.save(state);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRunFailure(
            PaymentProvider provider,
            String merchantAccountId,
            RuntimeException failure) {
        ReconciliationState state = lockOrCreate(provider, merchantAccountId);
        state.setLastRunAtUtc(Instant.now());
        state.setLastError(truncate(failure != null ? failure.getMessage() : "unknown failure", 2000));
        repository.save(state);
    }

    private ReconciliationState lockOrCreate(
            PaymentProvider provider,
            String merchantAccountId) {
        return repository.findForUpdate(provider, merchantAccountId)
                .orElseGet(() -> ReconciliationState.builder()
                        .provider(provider)
                        .merchantAccountId(merchantAccountId)
                        .build());
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}
