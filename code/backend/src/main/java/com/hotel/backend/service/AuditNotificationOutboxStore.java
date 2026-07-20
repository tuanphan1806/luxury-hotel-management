package com.hotel.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.constant.AuditNotificationStatus;
import com.hotel.backend.entity.AuditNotificationOutbox;
import com.hotel.backend.repository.AuditNotificationOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditNotificationOutboxStore {
    private final AuditNotificationOutboxRepository repository;

    @Transactional(readOnly = true)
    public List<Long> dueIds(int batchSize) {
        return repository.findDueIds(
                EnumSet.of(AuditNotificationStatus.PENDING, AuditNotificationStatus.FAILED),
                Instant.now(), PageRequest.of(0, Math.max(1, Math.min(batchSize, 100))));
    }

    @Transactional(readOnly = true)
    public List<Long> stuckIds(int batchSize, long stuckMinutes) {
        return repository.findStuckProcessingIds(
                Instant.now().minus(Math.max(1, stuckMinutes), ChronoUnit.MINUTES),
                PageRequest.of(0, Math.max(1, Math.min(batchSize, 100))));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Delivery claim(Long id) {
        AuditNotificationOutbox outbox = repository.findByIdForUpdate(id).orElse(null);
        if (outbox == null
                || !EnumSet.of(AuditNotificationStatus.PENDING, AuditNotificationStatus.FAILED)
                .contains(outbox.getStatus())
                || outbox.getNextAttemptAtUtc().isAfter(Instant.now())) {
            return null;
        }
        Instant now = Instant.now();
        outbox.setStatus(AuditNotificationStatus.PROCESSING);
        outbox.setAttempts((outbox.getAttempts() != null ? outbox.getAttempts() : 0) + 1);
        outbox.setLastAttemptAtUtc(now);
        outbox.setUpdatedAtUtc(now);
        repository.save(outbox);
        return new Delivery(
                outbox.getId(),
                outbox.getRecipientEmail(),
                outbox.getPayloadJson() != null ? outbox.getPayloadJson().deepCopy() : null,
                outbox.getAttempts());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(Long id) {
        AuditNotificationOutbox outbox = repository.findByIdForUpdate(id).orElse(null);
        if (outbox == null || outbox.getStatus() != AuditNotificationStatus.PROCESSING) return;
        Instant now = Instant.now();
        outbox.setStatus(AuditNotificationStatus.SENT);
        outbox.setSentAtUtc(now);
        outbox.setLastError(null);
        outbox.setUpdatedAtUtc(now);
        repository.save(outbox);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long id, String error) {
        AuditNotificationOutbox outbox = repository.findByIdForUpdate(id).orElse(null);
        if (outbox == null || outbox.getStatus() != AuditNotificationStatus.PROCESSING) return;
        int attempts = outbox.getAttempts() != null ? outbox.getAttempts() : 1;
        long delaySeconds = Math.min(3600L, 30L << Math.min(6, Math.max(0, attempts - 1)));
        Instant now = Instant.now();
        outbox.setStatus(AuditNotificationStatus.FAILED);
        outbox.setNextAttemptAtUtc(now.plusSeconds(delaySeconds));
        outbox.setLastError(sanitizeError(error));
        outbox.setUpdatedAtUtc(now);
        repository.save(outbox);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requeueStuck(Long id) {
        AuditNotificationOutbox outbox = repository.findByIdForUpdate(id).orElse(null);
        if (outbox == null || outbox.getStatus() != AuditNotificationStatus.PROCESSING) return;
        outbox.setStatus(AuditNotificationStatus.FAILED);
        outbox.setNextAttemptAtUtc(Instant.now());
        outbox.setLastError("Recovered stale PROCESSING delivery");
        outbox.setUpdatedAtUtc(Instant.now());
        repository.save(outbox);
    }

    private String sanitizeError(String value) {
        if (value == null || value.isBlank()) return "Unknown email delivery failure";
        String sanitized = value.replaceAll("(?i)(authorization|token|secret)\\s*[:=]\\s*[^,;\\s]+",
                "$1=[REDACTED]");
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }

    public record Delivery(Long id, String recipient, JsonNode payload, int attempt) {}
}
