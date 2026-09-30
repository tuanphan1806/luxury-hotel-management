package com.hotel.backend.service;

import com.hotel.backend.constant.AuditNotificationStatus;
import com.hotel.backend.entity.AuditNotificationOutbox;
import com.hotel.backend.repository.AuditNotificationOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.hotel.backend.service.EmailDeliveryException.Kind.*;

@ExtendWith(MockitoExtension.class)
class AuditNotificationOutboxStoreTest {
    @Mock AuditNotificationOutboxRepository repository;
    @Mock EmailService emails;
    @Mock EmailOutboxCodec codec;
    AuditNotificationOutboxStore store;
    AuditNotificationOutbox row;
    final EmailService.QueuedEmail message = new EmailService.QueuedEmail("s@example.com", "s@example.com",
            "Hotel", "guest@example.com", "Booking", "Secret token", "<p>Secret token</p>", "booking_confirmation");

    @BeforeEach void setup() {
        store = new AuditNotificationOutboxStore(repository, emails, codec,
                new com.hotel.backend.scheduled.MaintenancePollGate(true, 900000));
        row = AuditNotificationOutbox.builder().id(1L).attempts(0).recipientEmail("guest@example.com")
                .reservationId(7L).encryptedMessage("encrypted").nextAttemptAtUtc(Instant.now().minusSeconds(1)).build();
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(row));
        lenient().when(codec.decode("encrypted")).thenReturn(message);
        lenient().when(emails.queuedDeliveryScope()).thenReturn("brevo:test-scope");
    }

    @Test void retryKeepsFrozenPayloadAndKeyAndPersistsReceipt() {
        var first = store.claim(1L);
        assertThat(store.claim(1L)).isNull();
        store.markFailed(first, AMBIGUOUS);
        assertThat(row.getStatus()).isEqualTo(AuditNotificationStatus.FAILED);
        row.setNextAttemptAtUtc(Instant.now().minusSeconds(1));
        var second = store.claim(1L);
        assertThat(second.key()).isEqualTo(first.key());
        assertThat(second.message()).isEqualTo(first.message());
        assertThat(second.previouslyUncertain()).isTrue();
        store.markSent(second, new EmailDeliveryGateway.Receipt("message-id", false));
        assertThat(row.getStatus()).isEqualTo(AuditNotificationStatus.SENT);
        assertThat(row.getProviderMessageId()).isEqualTo("message-id");
        assertThat(row.isAcceptanceUncertain()).isFalse();
    }

    @Test void permanentFailureStopsAndRateLimitBacksOff() {
        var first = store.claim(1L);
        store.markFailed(first, RETRYABLE);
        assertThat(row.getStatus()).isEqualTo(AuditNotificationStatus.FAILED);
        assertThat(row.isAcceptanceUncertain()).isFalse();
        assertThat(row.getNextAttemptAtUtc()).isAfter(Instant.now().plusSeconds(25));
        row.setNextAttemptAtUtc(Instant.now().minusSeconds(1));
        store.markFailed(store.claim(1L), PERMANENT);
        assertThat(row.getStatus()).isEqualTo(AuditNotificationStatus.PERMANENT_FAILURE);
        assertThat(store.claim(1L)).isNull();
    }

    @Test void ambiguityOutsideWindowIsRetainedForReviewWithoutSending() {
        var first = store.claim(1L);
        store.markFailed(first, AMBIGUOUS);
        row.setFirstAttemptAtUtc(Instant.now().minusSeconds(601));
        row.setNextAttemptAtUtc(Instant.now().minusSeconds(1));
        assertThat(store.claim(1L)).isNull();
        assertThat(row.getStatus()).isEqualTo(AuditNotificationStatus.REVIEW_REQUIRED);
    }

    @Test void providerChangeOrLackOfDedupNeverRetriesAnUncertainSend() {
        var first = store.claim(1L);
        when(emails.queuedDeliveryScope()).thenReturn(null);
        store.markFailed(first, AMBIGUOUS);
        assertThat(row.getStatus()).isEqualTo(AuditNotificationStatus.REVIEW_REQUIRED);
    }

    @Test void oldWorkerCannotOverwriteNewAttemptAndRecentClaimCannotBeRecovered() {
        var first = store.claim(1L);
        store.requeueStuck(1L, 2);
        assertThat(row.getStatus()).isEqualTo(AuditNotificationStatus.PROCESSING);
        row.setLastAttemptAtUtc(Instant.now().minusSeconds(121));
        store.requeueStuck(1L, 2);
        var second = store.claim(1L);
        assertThat(second.attempt()).isEqualTo(2);
        store.markSent(first, new EmailDeliveryGateway.Receipt("old", false));
        store.markFailed(first, PERMANENT);
        assertThat(row.getStatus()).isEqualTo(AuditNotificationStatus.PROCESSING);
        assertThat(row.getProviderMessageId()).isNull();
        store.markSent(second, new EmailDeliveryGateway.Receipt("new", false));
        assertThat(row.getProviderMessageId()).isEqualTo("new");
    }

    @Test void rateLimitAfterAmbiguityDoesNotEraseUncertainty() {
        var first = store.claim(1L);
        store.markFailed(first, AMBIGUOUS);
        row.setNextAttemptAtUtc(Instant.now().minusSeconds(1));
        store.markFailed(store.claim(1L), RETRYABLE);
        assertThat(row.isAcceptanceUncertain()).isTrue();
    }

    @Test void retriesHaveAHardLimit() {
        row.setAttempts(7);
        store.markFailed(store.claim(1L), RETRYABLE);
        assertThat(row.getStatus()).isEqualTo(AuditNotificationStatus.PERMANENT_FAILURE);
        assertThat(store.claim(1L)).isNull();
    }

    @Test void corruptSnapshotIsNotSentOrDiscarded() {
        when(codec.decode("encrypted")).thenThrow(new IllegalStateException("cipher unavailable"));
        assertThat(store.claim(1L)).isNull();
        assertThat(row.getStatus()).isEqualTo(AuditNotificationStatus.REVIEW_REQUIRED);
        assertThat(row.getEncryptedMessage()).isEqualTo("encrypted");
    }
}
