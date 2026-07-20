package com.hotel.backend.service;

import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Owns the transaction boundary for one idempotent command.
 *
 * <p>The idempotency claim, the domain mutation and the completion metadata are
 * committed or rolled back together. This is deliberately a separate Spring
 * bean so the transactional proxy is active when {@link IdempotencyService}
 * coordinates a retry after a concurrent unique-key race.</p>
 */
@Service
@RequiredArgsConstructor
public class IdempotencyTransactionExecutor {

    private final IdempotencyStore store;

    @Transactional
    public <T> T execute(
            String requestKey,
            String operation,
            String actorScope,
            String requestHash,
            String resourceType,
            Supplier<T> action,
            Function<T, String> resourceId,
            Function<String, T> replay) {
        IdempotencyStore.Decision decision = store.begin(
                requestKey, operation, actorScope, requestHash);
        if (decision.replay()) {
            if (decision.resourceId() == null || decision.resourceId().isBlank()) {
                throw new AppException(ErrorCode.DUPLICATE_RESOURCE,
                        "Yêu cầu đã hoàn tất nhưng thiếu resource để replay");
            }
            return replay.apply(decision.resourceId());
        }

        T result = action.get();
        String id = resourceId.apply(result);
        if (id == null || id.isBlank()) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Kết quả idempotency thiếu resource id");
        }
        store.complete(requestKey, operation, actorScope, resourceType, id);
        return result;
    }
}
