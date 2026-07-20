package com.hotel.backend.scheduled;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.service.AuditNotificationOutboxStore;
import com.hotel.backend.service.BusinessMetricService;
import com.hotel.backend.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditAlertDeliverySchedulerTest {
    @Mock AuditNotificationOutboxStore outboxStore;
    @Mock EmailService emailService;
    @Mock BusinessMetricService businessMetrics;

    private AuditAlertDeliveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AuditAlertDeliveryScheduler(
                outboxStore, emailService, businessMetrics);
    }

    @Test
    void sendGridFailureMarksOutboxForRetryWithoutEscapingScheduler() {
        var payload = new ObjectMapper().createObjectNode()
                .put("action", "USER_ROLE_CHANGED")
                .put("riskLevel", "HIGH")
                .put("targetType", "USER")
                .put("targetId", "7");
        when(outboxStore.stuckIds(0, 0)).thenReturn(List.of());
        when(outboxStore.dueIds(0)).thenReturn(List.of(41L));
        when(outboxStore.claim(41L)).thenReturn(
                new AuditNotificationOutboxStore.Delivery(
                        41L, "admin@example.com", payload, 1));
        doThrow(new RuntimeException("SendGrid unavailable"))
                .when(emailService).send(anyString(), anyString(), anyString());

        scheduler.deliver();

        verify(outboxStore).markFailed(41L, "SendGrid unavailable");
        verify(outboxStore, never()).markSent(anyLong());
        verify(businessMetrics).increment(
                "hotel.audit.alert.delivery", "result", "failed");
        verify(businessMetrics).increment(
                "hotel.scheduler.failures", "job", "audit_alert_delivery");
    }
}
