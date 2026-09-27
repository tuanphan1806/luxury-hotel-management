package com.hotel.backend.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BrevoDeliveryEventServiceTest {
    @Mock AuditNotificationOutboxRepository repository;
    BrevoDeliveryEventService service;
    AuditNotificationOutbox row;
    String key = UUID.randomUUID().toString();
    long now = Instant.now().getEpochSecond();
    @BeforeEach void setup() {
        service = new BrevoDeliveryEventService(repository);
        row = AuditNotificationOutbox.builder().id(1L).status(AuditNotificationStatus.PROCESSING)
                .recipientEmail("guest@example.test").deliveryScope("brevo:test").idempotencyKey(key)
                .firstAttemptAtUtc(Instant.ofEpochSecond(now - 10)).acceptanceUncertain(true).build();
        lenient().when(repository.findByDeliveryKeyForUpdate(key)).thenReturn(Optional.of(row));
    }

    @Test void webhookBeforeHttpReceiptResolvesUncertaintyWithoutAnotherSend() {
        service.accept(event("delivered", now));
        assertThat(row.getStatus()).isEqualTo(AuditNotificationStatus.SENT);
        assertThat(row.isAcceptanceUncertain()).isFalse();
        assertThat(row.getDeliveryStatus()).isEqualTo("DELIVERED");
        assertThat(row.getProviderMessageId()).isEqualTo("provider-id");
    }

    @Test void duplicateOrOlderEventsDoNotRegressDelivery() {
        service.accept(event("delivered", now));
        service.accept(event("delivered", now));
        service.accept(event("request", now + 1));
        service.accept(event("hard_bounce", now - 1));
        assertThat(row.getDeliveryStatus()).isEqualTo("DELIVERED");
        verify(repository, times(1)).save(row);
    }

    @Test void hardBounceIsRetainedAndCannotBeOverwrittenByLateAccepted() {
        service.accept(event("hard_bounce", now));
        service.accept(event("request", now + 1));
        service.accept(event("delivered", now + 2));
        assertThat(row.getDeliveryStatus()).isEqualTo("HARD_BOUNCE");
        assertThat(row.getLastError()).contains("HARD_BOUNCE");
    }

    @Test void lateAcceptedCannotEraseTemporaryFailureButDeliveryCanResolveIt() {
        service.accept(event("soft_bounce", now));
        service.accept(event("request", now + 1));
        assertThat(row.getDeliveryStatus()).isEqualTo("SOFT_BOUNCE");
        service.accept(event("delivered", now + 2));
        assertThat(row.getDeliveryStatus()).isEqualTo("DELIVERED");
    }

    @Test void wrongRecipientOrMessageCannotModifyAnotherDelivery() {
        service.accept(event("delivered", now).put("email", "other@example.test"));
        assertThat(row.getStatus()).isEqualTo(AuditNotificationStatus.PROCESSING);
        row.setProviderMessageId("<original-id>");
        service.accept(event("delivered", now));
        verify(repository, never()).save(any());
    }

    @Test void untrackedMailIsIgnoredAndInvalidFutureTimeRejected() {
        service.accept(event("delivered", now).put("X-Mailin-custom", "unrelated"));
        verifyNoInteractions(repository);
        assertThatThrownBy(() -> service.accept(event("delivered", now + 3600))).isInstanceOf(IllegalArgumentException.class);
    }

    private ObjectNode event(String type, long timestamp) {
        return JsonNodeFactory.instance.objectNode().put("event", type).put("email", "guest@example.test")
                .put("message-id", "<provider-id>").put("ts_event", timestamp).put("X-Mailin-custom", "hotel-email-key:" + key);
    }
}
