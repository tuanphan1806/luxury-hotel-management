package com.hotel.backend.scheduled;

import com.hotel.backend.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditAlertDeliverySchedulerTest {
    @Mock AuditNotificationOutboxStore outboxStore;
    @Mock EmailService emailService;
    @Mock BusinessMetricService businessMetrics;
    AuditAlertDeliveryScheduler scheduler;
    final AuditNotificationOutboxStore.Delivery delivery =
            new AuditNotificationOutboxStore.Delivery(41L, 1, "stable-key", false, null);

    @BeforeEach void setup() {
        scheduler = new AuditAlertDeliveryScheduler(outboxStore, emailService, businessMetrics);
        when(outboxStore.dueIds(0)).thenReturn(List.of(41L));
        when(outboxStore.claim(41L)).thenReturn(delivery);
    }

    @Test void timeoutIsUncertainRatherThanDefinitelyRejected() throws Exception {
        when(emailService.sendQueued(null, "stable-key")).thenThrow(
                new EmailDeliveryException(EmailDeliveryException.Kind.AMBIGUOUS, "timeout"));
        scheduler.deliver();
        verify(outboxStore).markFailed(delivery, EmailDeliveryException.Kind.AMBIGUOUS);
        verify(outboxStore, never()).markSent(any(), any());
    }

    @Test void persistenceFailureAfterAcceptanceDoesNotBecomeAnotherSendFailure() throws Exception {
        var receipt = new EmailDeliveryGateway.Receipt("provider-message-id", false);
        when(emailService.sendQueued(null, "stable-key")).thenReturn(receipt);
        doThrow(new IllegalStateException("DB unavailable")).when(outboxStore).markSent(delivery, receipt);
        scheduler.deliver();
        verify(outboxStore, never()).markFailed(any(), any());
        verify(emailService, times(1)).sendQueued(null, "stable-key");
    }

    @Test void permanentRejectionIsClassifiedForTheStore() throws Exception {
        when(emailService.sendQueued(null, "stable-key")).thenThrow(
                new EmailDeliveryException(EmailDeliveryException.Kind.PERMANENT, "unauthorized"));
        scheduler.deliver();
        verify(outboxStore).markFailed(delivery, EmailDeliveryException.Kind.PERMANENT);
    }
}
