package com.hotel.backend.scheduled;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.service.AuditNotificationOutboxStore;
import com.hotel.backend.service.EmailService;
import com.hotel.backend.service.BusinessMetricService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUDIT-ALERT-DELIVERY")
public class AuditAlertDeliveryScheduler {
    private final AuditNotificationOutboxStore outboxStore;
    private final EmailService emailService;
    private final BusinessMetricService businessMetrics;

    @Value("${app.audit-alert.batch-size:25}")
    private int batchSize;

    @Value("${app.audit-alert.processing-timeout-minutes:10}")
    private long processingTimeoutMinutes;

    @Scheduled(
            fixedDelayString = "${app.audit-alert.delivery-interval-ms:30000}",
            initialDelayString = "${app.maintenance.startup-delay-ms:60000}")
    public void deliver() {
        for (Long id : outboxStore.stuckIds(batchSize, processingTimeoutMinutes)) {
            outboxStore.requeueStuck(id);
            businessMetrics.increment("hotel.audit.alert.delivery", "result", "requeued");
        }
        for (Long id : outboxStore.dueIds(batchSize)) {
            AuditNotificationOutboxStore.Delivery delivery = outboxStore.claim(id);
            if (delivery == null) continue;
            try {
                emailService.sendAuditAlert(
                        delivery.recipient(),
                        subject(delivery.payload()),
                        body(delivery.payload()));
                outboxStore.markSent(delivery.id());
                businessMetrics.increment("hotel.audit.alert.delivery", "result", "sent");
            } catch (RuntimeException exception) {
                outboxStore.markFailed(delivery.id(), exception.getMessage());
                businessMetrics.increment("hotel.audit.alert.delivery", "result", "failed");
                businessMetrics.increment(
                        "hotel.scheduler.failures", "job", "audit_alert_delivery");
                log.warn("Audit alert delivery failed outboxId={} attempt={}: {}",
                        delivery.id(), delivery.attempt(), exception.getMessage());
            }
        }
        businessMetrics.increment("hotel.scheduler.runs", "job", "audit_alert_delivery");
    }

    private String subject(JsonNode payload) {
        return "[Luxury Hotel] Cảnh báo thao tác rủi ro " + text(payload, "action", "UNKNOWN");
    }

    private String body(JsonNode payload) {
        return """
                Hệ thống ghi nhận một thao tác cần kiểm tra.

                Hành động: %s
                Mức rủi ro: %s
                Đối tượng: %s %s
                Reservation: %s
                Người thao tác: %s (%s)
                Thời gian UTC: %s
                Correlation ID: %s
                Chi tiết: %s

                Mở trang Audit Log trong dashboard để xem dữ liệu đã được định dạng.
                """.formatted(
                text(payload, "action", "UNKNOWN"),
                text(payload, "riskLevel", "HIGH"),
                text(payload, "targetType", "UNKNOWN"),
                text(payload, "targetId", ""),
                text(payload, "reservationCode", "Không có"),
                text(payload, "actorName", "Hệ thống"),
                text(payload, "actorRole", "SYSTEM"),
                text(payload, "occurredAtUtc", ""),
                text(payload, "correlationId", "Không có"),
                text(payload, "details", "Không có"));
    }

    private String text(JsonNode payload, String field, String fallback) {
        if (payload == null || !payload.hasNonNull(field)) return fallback;
        String value = payload.path(field).asText();
        return value == null || value.isBlank() ? fallback : value;
    }
}
