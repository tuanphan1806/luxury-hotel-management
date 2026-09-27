package com.hotel.backend.scheduled;

import com.hotel.backend.service.AuditNotificationOutboxStore;
import com.hotel.backend.service.EmailDeliveryException;
import com.hotel.backend.service.EmailDeliveryGateway;
import com.hotel.backend.service.EmailService;
import com.hotel.backend.service.BusinessMetricService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "EMAIL-OUTBOX-DELIVERY")
public class AuditAlertDeliveryScheduler {
    private final AuditNotificationOutboxStore outboxStore;
    private final EmailService emailService;
    private final BusinessMetricService businessMetrics;

    @Value("${app.audit-alert.batch-size:25}")
    private int batchSize;
    @Value("${app.audit-alert.processing-timeout-minutes:2}")
    private long processingTimeoutMinutes;

    @Scheduled(fixedDelayString = "${app.audit-alert.delivery-interval-ms:30000}",
            initialDelayString = "${app.maintenance.startup-delay-ms:60000}")
    public void deliver() {
        for (Long id : outboxStore.stuckIds(batchSize, processingTimeoutMinutes)) {
            try { outboxStore.requeueStuck(id, processingTimeoutMinutes); }
            catch (RuntimeException exception) { log.warn("Email recovery could not persist outboxId={}", id); }
        }
        for (Long id : outboxStore.dueIds(batchSize)) {
            try { deliverOne(id); }
            catch (RuntimeException exception) {
                // A DB failure after acceptance leaves PROCESSING intact for bounded recovery.
                log.warn("Email state could not persist outboxId={}", id);
                businessMetrics.increment("hotel.scheduler.failures", "job", "audit_alert_delivery");
            }
        }
        businessMetrics.increment("hotel.scheduler.runs", "job", "audit_alert_delivery");
    }

    private void deliverOne(Long id) {
        var delivery = outboxStore.claim(id);
        if (delivery == null) return;
        EmailDeliveryGateway.Receipt receipt;
        try {
            receipt = emailService.sendQueued(delivery.message(), delivery.key());
        } catch (IOException | RuntimeException exception) {
            var kind = exception instanceof EmailDeliveryException failure ? failure.kind()
                    : EmailDeliveryException.Kind.AMBIGUOUS;
            outboxStore.markFailed(delivery, kind);
            businessMetrics.increment("hotel.audit.alert.delivery", "result", "failed");
            businessMetrics.increment("hotel.scheduler.failures", "job", "audit_alert_delivery");
            log.warn("Email delivery failed outboxId={} attempt={} kind={}", id, delivery.attempt(), kind);
            return;
        }
        // Never convert a persistence failure into a definitely rejected send.
        outboxStore.markSent(delivery, receipt);
        businessMetrics.increment("hotel.audit.alert.delivery", "result", "sent");
    }
}
