package com.hotel.backend.service;

import com.hotel.backend.constant.AuditNotificationStatus;
import com.hotel.backend.entity.AuditNotificationOutbox;
import com.hotel.backend.event.GuestBookingCreatedEvent;
import com.hotel.backend.repository.AuditNotificationOutboxRepository;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditNotificationOutboxStore {
    // Below even the older documented Brevo 15-minute deduplication window.
    private static final long SAFE_RETRY_SECONDS = 600;
    private static final int MAX_ATTEMPTS = 8;
    private final AuditNotificationOutboxRepository repository;
    private final EmailService emailService;
    private final EmailOutboxCodec codec;
    private final com.hotel.backend.scheduled.MaintenancePollGate pollGate;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueBooking(GuestBookingCreatedEvent event) {
        // The reservation aggregate is locked by the publisher; DB uniqueness is the final guard.
        if (repository.existsByReservationId(event.reservationId())) return;
        Instant now = Instant.now();
        repository.save(AuditNotificationOutbox.builder()
                .reservationId(event.reservationId()).notificationType("BOOKING_CONFIRMATION")
                .recipientEmail(event.email()).payloadJson(JsonNodeFactory.instance.objectNode())
                .encryptedMessage(codec.encode(emailService.prepareGuestBooking(
                        event.email(), event.reservationId(), event.guestToken())))
                .nextAttemptAtUtc(now).createdAtUtc(now).updatedAtUtc(now).build());
        pollGate.wakeAfterCommit();
    }

    @Transactional(readOnly = true)
    public List<Long> dueIds(int batchSize) {
        return repository.findDueIds(EnumSet.of(AuditNotificationStatus.PENDING, AuditNotificationStatus.FAILED),
                Instant.now(), page(batchSize));
    }

    @Transactional(readOnly = true)
    public List<Long> stuckIds(int batchSize, long stuckMinutes) {
        return repository.findStuckProcessingIds(Instant.now().minus(Math.max(2, stuckMinutes), ChronoUnit.MINUTES),
                page(batchSize));
    }

    private PageRequest page(int size) { return PageRequest.of(0, Math.max(1, Math.min(size, 100))); }

    @Transactional(readOnly = true)
    public boolean hasPendingWork() {
        // Include future retries and in-flight attempts, not only currently due messages.
        return repository.existsByStatusIn(EnumSet.of(AuditNotificationStatus.PENDING,
                AuditNotificationStatus.FAILED, AuditNotificationStatus.PROCESSING));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Delivery claim(Long id) {
        AuditNotificationOutbox row = repository.findByIdForUpdate(id).orElse(null);
        Instant now = Instant.now();
        if (row == null || !EnumSet.of(AuditNotificationStatus.PENDING, AuditNotificationStatus.FAILED)
                .contains(row.getStatus()) || row.getNextAttemptAtUtc().isAfter(now)) return null;
        if (row.getAttempts() >= MAX_ATTEMPTS) {
            stop(row, row.isAcceptanceUncertain(), "Email attempt limit reached", now);
            return null;
        }
        if (row.isAcceptanceUncertain() && !canRetryUncertain(row, now)) {
            stop(row, true, "Acceptance unknown; provider reconciliation required", now);
            return null;
        }
        EmailService.QueuedEmail message;
        try {
            if (row.getEncryptedMessage() == null) {
                if (row.getReservationId() != null) throw new IllegalStateException("Missing booking snapshot");
                row.setEncryptedMessage(codec.encode(emailService.prepareAuditAlert(row.getRecipientEmail(),
                        AuditEmailContent.subject(row.getPayloadJson()), AuditEmailContent.body(row.getPayloadJson()))));
            }
            message = codec.decode(row.getEncryptedMessage());
            if (!row.getRecipientEmail().equals(message.to())) throw new IllegalStateException("Recipient mismatch");
        } catch (RuntimeException exception) {
            stop(row, true, "Cannot read email snapshot; operator review required", now);
            return null;
        }
        // A definitely rejected request may start a fresh key after the safe window/provider changes.
        if (row.getIdempotencyKey() == null || (!row.isAcceptanceUncertain()
                && (!withinWindow(row, now) || !Objects.equals(row.getDeliveryScope(), emailService.queuedDeliveryScope())))) {
            row.setIdempotencyKey(UUID.randomUUID().toString());
            row.setFirstAttemptAtUtc(now);
            row.setDeliveryScope(emailService.queuedDeliveryScope());
        }
        boolean previouslyUncertain = row.isAcceptanceUncertain();
        row.setStatus(AuditNotificationStatus.PROCESSING);
        row.setAttempts(row.getAttempts() + 1);
        row.setLastAttemptAtUtc(now);
        row.setUpdatedAtUtc(now);
        // Commit uncertainty BEFORE network I/O, including crashes before markSent.
        row.setAcceptanceUncertain(true);
        repository.save(row);
        return new Delivery(row.getId(), row.getAttempts(), row.getIdempotencyKey(), previouslyUncertain, message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(Delivery delivery, EmailDeliveryGateway.Receipt receipt) {
        AuditNotificationOutbox row = processing(delivery);
        if (row == null) return;
        Instant now = Instant.now();
        row.setStatus(AuditNotificationStatus.SENT);
        row.setSentAtUtc(now);
        row.setProviderMessageId(receipt.messageId());
        row.setDeliveryStatus("ACCEPTED");
        row.setAcceptanceUncertain(false);
        row.setLastError(receipt.deduplicated() ? "Provider confirmed duplicate key; original message id unavailable" : null);
        row.setUpdatedAtUtc(now);
        repository.save(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Delivery delivery, EmailDeliveryException.Kind kind) {
        AuditNotificationOutbox row = processing(delivery);
        if (row == null) return;
        Instant now = Instant.now();
        row.setAcceptanceUncertain(delivery.previouslyUncertain() || kind == EmailDeliveryException.Kind.AMBIGUOUS);
        if (kind == EmailDeliveryException.Kind.PERMANENT || row.getAttempts() >= MAX_ATTEMPTS) {
            stop(row, row.isAcceptanceUncertain(), "Email rejected or attempt limit reached", now);
            return;
        }
        long delay = Math.min(3600L, 30L << Math.min(7, Math.max(0, row.getAttempts() - 1)));
        Instant next = now.plusSeconds(delay);
        if (row.isAcceptanceUncertain() && !canRetryUncertain(row, next)) {
            stop(row, true, "Acceptance unknown; provider reconciliation required", now);
            return;
        }
        row.setStatus(AuditNotificationStatus.FAILED);
        row.setNextAttemptAtUtc(next);
        row.setLastError(kind == EmailDeliveryException.Kind.RETRYABLE ? "Provider rate limited delivery" : "Provider acceptance unknown");
        row.setUpdatedAtUtc(now);
        repository.save(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requeueStuck(Long id, long stuckMinutes) {
        AuditNotificationOutbox row = repository.findByIdForUpdate(id).orElse(null);
        Instant now = Instant.now();
        if (row == null || row.getStatus() != AuditNotificationStatus.PROCESSING
                || row.getLastAttemptAtUtc() == null
                || row.getLastAttemptAtUtc().isAfter(now.minus(Math.max(2, stuckMinutes), ChronoUnit.MINUTES))) return;
        row.setAcceptanceUncertain(true);
        if (row.getAttempts() >= MAX_ATTEMPTS || !canRetryUncertain(row, now)) {
            stop(row, true, "Stale attempt; provider reconciliation required", now);
            return;
        }
        row.setStatus(AuditNotificationStatus.FAILED);
        row.setNextAttemptAtUtc(now);
        row.setLastError("Recovering stale delivery with the same provider key");
        row.setUpdatedAtUtc(now);
        repository.save(row);
    }

    private AuditNotificationOutbox processing(Delivery delivery) {
        AuditNotificationOutbox row = repository.findByIdForUpdate(delivery.id()).orElse(null);
        return row != null && row.getStatus() == AuditNotificationStatus.PROCESSING
                && row.getAttempts() == delivery.attempt() ? row : null;
    }

    private boolean canRetryUncertain(AuditNotificationOutbox row, Instant at) {
        return row.getIdempotencyKey() != null && row.getDeliveryScope() != null
                && Objects.equals(row.getDeliveryScope(), emailService.queuedDeliveryScope()) && withinWindow(row, at);
    }

    private boolean withinWindow(AuditNotificationOutbox row, Instant at) {
        return row.getFirstAttemptAtUtc() != null && at.isBefore(row.getFirstAttemptAtUtc().plusSeconds(SAFE_RETRY_SECONDS));
    }

    private void stop(AuditNotificationOutbox row, boolean review, String reason, Instant now) {
        row.setStatus(review ? AuditNotificationStatus.REVIEW_REQUIRED : AuditNotificationStatus.PERMANENT_FAILURE);
        row.setLastError(reason);
        row.setUpdatedAtUtc(now);
        repository.save(row);
    }

    public record Delivery(Long id, int attempt, String key, boolean previouslyUncertain,
            EmailService.QueuedEmail message) {
        @Override public String toString() { return "EmailDelivery[id=" + id + ", attempt=" + attempt + "]"; }
    }
}
