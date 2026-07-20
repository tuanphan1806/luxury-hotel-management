package com.hotel.backend.service;

import com.hotel.backend.constant.IdempotencyStatus;
import com.hotel.backend.entity.IdempotencyRequest;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.IdempotencyRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class IdempotencyStore {

    private static final Duration TTL = Duration.ofHours(24);

    private final IdempotencyRequestRepository repository;

    /**
     * Claims a key inside the caller's business transaction. Keeping the claim,
     * the financial side effect and {@link #complete} in one transaction removes
     * the crash window where money was recorded but the key was still PROCESSING.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Decision begin(
            String requestKey,
            String operation,
            String actorScope,
            String requestHash) {
        IdempotencyRequest existing;
        try {
            existing = repository
                    .findForUpdate(requestKey, operation, actorScope)
                    .orElse(null);
        } catch (PessimisticLockingFailureException race) {
            throw new ClaimConflictException(race);
        }
        if (existing == null) {
            try {
                repository.saveAndFlush(IdempotencyRequest.builder()
                        .requestKey(requestKey)
                        .operation(operation)
                        .actorScope(actorScope)
                        .requestHash(requestHash)
                        .status(IdempotencyStatus.PROCESSING)
                        .expiresAtUtc(Instant.now().plus(TTL))
                        .build());
                return Decision.execute();
            } catch (DataIntegrityViolationException
                     | PessimisticLockingFailureException race) {
                // The current transaction is rollback-only after a unique-key
                // collision. Let the outer coordinator retry in a fresh
                // transaction so it can replay the winner's committed result.
                throw new ClaimConflictException(race);
            }
        }
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE,
                    "Idempotency key đã được dùng với payload khác");
        }
        if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
            return Decision.replay(existing.getResourceId());
        }
        if (existing.getStatus() == IdempotencyStatus.PROCESSING
                && existing.getExpiresAtUtc().isAfter(Instant.now())) {
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE,
                    "Yêu cầu cùng idempotency key đang được xử lý");
        }
        existing.setStatus(IdempotencyStatus.PROCESSING);
        existing.setResponseStatus(null);
        existing.setResponseJson(null);
        existing.setResourceType(null);
        existing.setResourceId(null);
        existing.setCompletedAtUtc(null);
        existing.setExpiresAtUtc(Instant.now().plus(TTL));
        repository.save(existing);
        return Decision.execute();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(
            String requestKey,
            String operation,
            String actorScope,
            String resourceType,
            String resourceId) {
        IdempotencyRequest request = repository
                .findForUpdate(requestKey, operation, actorScope)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        request.setStatus(IdempotencyStatus.COMPLETED);
        request.setResponseStatus(200);
        // Store only non-sensitive resource metadata. The replay response is
        // reconstructed from the authoritative ledger row.
        request.setResponseJson("{\"completed\":true}");
        request.setResourceType(resourceType);
        request.setResourceId(resourceId);
        request.setCompletedAtUtc(Instant.now());
        repository.save(request);
    }

    public record Decision(boolean replay, String resourceId) {
        static Decision execute() {
            return new Decision(false, null);
        }

        static Decision replay(String resourceId) {
            return new Decision(true, resourceId);
        }
    }

    static final class ClaimConflictException extends RuntimeException {
        ClaimConflictException(Throwable cause) {
            super(cause);
        }
    }
}
