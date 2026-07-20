package com.hotel.backend.service;

import com.hotel.backend.constant.PaymentProviderEventStatus;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.entity.PaymentProviderEvent;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.PaymentProviderEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persists the provider event in an independent transaction before financial
 * side effects begin. A processing rollback therefore leaves a durable event
 * that reconciliation can retry instead of losing the bank receipt.
 */
@Service
@RequiredArgsConstructor
public class PaymentProviderEventService {

    private static final List<PaymentProviderEventStatus> TERMINAL_STATUSES = List.of(
            PaymentProviderEventStatus.PROCESSED,
            PaymentProviderEventStatus.IGNORED,
            PaymentProviderEventStatus.REVIEW_REQUIRED
    );

    private final PaymentProviderEventRepository repository;

    @Transactional(readOnly = true)
    public Optional<Instant> earliestDueRetryOccurredAt(PaymentProvider provider) {
        return repository.findEarliestDueRetryOccurredAt(
                provider, PaymentProviderEventStatus.FAILED_RETRYABLE, Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentProviderEvent ingest(PaymentProviderEvent candidate) {
        PaymentProviderEvent existing = findExistingInternal(candidate).orElse(null);
        if (existing == null) {
            return repository.saveAndFlush(candidate);
        }
        if (TERMINAL_STATUSES.contains(existing.getStatus())
                || existing.getStatus() == PaymentProviderEventStatus.PROCESSING) {
            return existing;
        }
        copyLatestEvidence(candidate, existing);
        return repository.saveAndFlush(existing);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<PaymentProviderEvent> findExisting(PaymentProviderEvent candidate) {
        return findExistingInternal(candidate);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentProviderEvent startProcessing(PaymentProviderEvent snapshot) {
        if (snapshot == null || snapshot.getId() == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Sự kiện provider chưa được lưu durable");
        }
        PaymentProviderEvent event = repository.findByIdForUpdate(snapshot.getId())
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy sự kiện provider"));
        if (TERMINAL_STATUSES.contains(event.getStatus())
                || event.getStatus() == PaymentProviderEventStatus.PROCESSING) {
            return event;
        }
        event.setStatus(PaymentProviderEventStatus.PROCESSING);
        event.setProcessingAttempts(value(event.getProcessingAttempts()) + 1);
        event.setLastAttemptAtUtc(Instant.now());
        event.setNextRetryAtUtc(null);
        event.setLastError(null);
        return repository.saveAndFlush(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRetryable(String eventId, RuntimeException failure) {
        if (eventId == null) return;
        PaymentProviderEvent event = repository.findByIdForUpdate(eventId).orElse(null);
        if (event == null || TERMINAL_STATUSES.contains(event.getStatus())) return;

        int attempts = Math.max(1, value(event.getProcessingAttempts()));
        long retryDelaySeconds = Math.min(3_600L, 30L << Math.min(6, attempts - 1));
        event.setStatus(PaymentProviderEventStatus.FAILED_RETRYABLE);
        event.setNextRetryAtUtc(Instant.now().plusSeconds(retryDelaySeconds));
        event.setLastError(safeMessage(failure));
        repository.saveAndFlush(event);
    }

    private Optional<PaymentProviderEvent> findExistingInternal(PaymentProviderEvent candidate) {
        Optional<PaymentProviderEvent> byDedup = repository.findByProviderAndDedupKey(
                candidate.getProvider(), candidate.getDedupKey());
        if (byDedup.isPresent()) return byDedup;

        if (hasText(candidate.getProviderReference())) {
            Optional<PaymentProviderEvent> byReference =
                    repository.findByProviderAndProviderReference(
                            candidate.getProvider(), candidate.getProviderReference());
            if (byReference.isPresent()) return byReference;
        }
        return repository.findByProviderAndProviderEventId(
                candidate.getProvider(), candidate.getProviderEventId());
    }

    private void copyLatestEvidence(
            PaymentProviderEvent candidate,
            PaymentProviderEvent existing) {
        existing.setProviderTxnId(candidate.getProviderTxnId());
        existing.setMerchantAccountId(candidate.getMerchantAccountId());
        existing.setDedupKey(candidate.getDedupKey());
        existing.setPayloadHash(candidate.getPayloadHash());
        existing.setTransferType(candidate.getTransferType());
        existing.setAccountNumberMasked(candidate.getAccountNumberMasked());
        existing.setAmount(candidate.getAmount());
        existing.setPaymentCode(candidate.getPaymentCode());
        existing.setProviderOccurredAt(candidate.getProviderOccurredAt());
        existing.setProviderOccurredAtUtc(candidate.getProviderOccurredAtUtc());
        if (existing.getReceivedAtUtc() == null) {
            existing.setReceivedAtUtc(candidate.getReceivedAtUtc());
        }
    }

    private int value(Integer value) {
        return value != null ? value : 0;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safeMessage(Throwable failure) {
        String message = failure != null ? failure.getMessage() : null;
        if (message == null || message.isBlank()) {
            message = failure != null ? failure.getClass().getSimpleName() : "Unknown failure";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
