package com.hotel.backend.service;

import com.hotel.backend.constant.AuditNotificationStatus;
import com.hotel.backend.constant.AuditRiskLevel;
import com.hotel.backend.constant.CheckoutReconciliationRequestStatus;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentProviderEventStatus;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.dto.response.BusinessMonitoringSummaryResponse;
import com.hotel.backend.entity.PaymentRefund;
import com.hotel.backend.entity.ReconciliationState;
import com.hotel.backend.repository.AuditNotificationOutboxRepository;
import com.hotel.backend.repository.CheckoutReconciliationRequestRepository;
import com.hotel.backend.repository.PaymentProviderEventRepository;
import com.hotel.backend.repository.PaymentRefundRepository;
import com.hotel.backend.repository.ReconciliationStateRepository;
import com.hotel.backend.repository.ReservationAuditLogRepository;
import com.hotel.backend.repository.RoomHoldRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
@RequiredArgsConstructor
public class BusinessMonitoringService {
    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final PaymentProviderEventRepository providerEventRepository;
    private final PaymentRefundRepository refundRepository;
    private final RoomHoldRepository roomHoldRepository;
    private final CheckoutReconciliationRequestRepository reconciliationRequestRepository;
    private final ReservationAuditLogRepository auditLogRepository;
    private final AuditNotificationOutboxRepository outboxRepository;
    private final ReconciliationStateRepository reconciliationStateRepository;
    private final ReservationAuditService auditService;

    private final ConcurrentLinkedDeque<Instant> webhookAuthenticationFailures =
            new ConcurrentLinkedDeque<>();

    @Value("${app.monitoring.refund-pending-threshold-minutes:60}")
    private long refundPendingThresholdMinutes;

    @Value("${app.monitoring.reconciliation-stale-threshold-minutes:15}")
    private long reconciliationStaleThresholdMinutes;

    @Value("${app.monitoring.checkout-pending-threshold-minutes:30}")
    private long checkoutPendingThresholdMinutes;

    @Value("${app.monitoring.room-hold-overdue-grace-minutes:5}")
    private long roomHoldOverdueGraceMinutes;

    @Value("${app.monitoring.email-outbox-stale-threshold-minutes:15}")
    private long emailOutboxStaleThresholdMinutes;

    @Value("${app.monitoring.webhook-auth-failure-threshold-count:3}")
    private long webhookAuthenticationFailureThresholdCount;

    @Value("${app.monitoring.webhook-auth-failure-window-minutes:5}")
    private long webhookAuthenticationFailureWindowMinutes;

    public void recordWebhookAuthenticationFailure() {
        Instant now = Instant.now();
        webhookAuthenticationFailures.addLast(now);
        pruneWebhookAuthenticationFailures(now);
        long failureCount = webhookAuthenticationFailures.size();
        long threshold = Math.max(1L, webhookAuthenticationFailureThresholdCount);
        long window = Math.max(1L, webhookAuthenticationFailureWindowMinutes);
        if (failureCount < threshold) return;

        long bucket = now.getEpochSecond() / (window * 60L);
        auditService.recordSystem(
                null,
                "SEPAY_WEBHOOK",
                "AUTHENTICATION",
                ReservationAuditAction.WEBHOOK_AUTHENTICATION_REJECTED,
                "Webhook SePay bị từ chối xác thực nhiều lần trong cửa sổ giám sát",
                null,
                null,
                Map.of(
                        "failureCount", failureCount,
                        "thresholdCount", threshold,
                        "windowMinutes", window),
                null,
                "WEBHOOK_AUTH_REJECTED:" + bucket);
    }

    @Transactional(readOnly = true)
    public BusinessMonitoringSummaryResponse summary() {
        Instant now = Instant.now();
        Instant startOfUtcDay = now.truncatedTo(ChronoUnit.DAYS);
        List<RefundStatus> pendingRefundStatuses = List.of(
                RefundStatus.READY_FOR_MANUAL_TRANSFER,
                RefundStatus.REQUESTED,
                RefundStatus.PROCESSING,
                RefundStatus.MANUAL_REVIEW);
        Instant staleRefundCutoff = now.minus(
                Math.max(1L, refundPendingThresholdMinutes), ChronoUnit.MINUTES);
        Instant staleCheckoutCutoff = now.minus(
                Math.max(1L, checkoutPendingThresholdMinutes), ChronoUnit.MINUTES);
        Instant staleEmailCutoff = now.minus(
                Math.max(1L, emailOutboxStaleThresholdMinutes), ChronoUnit.MINUTES);
        pruneWebhookAuthenticationFailures(now);
        long stalePendingRefunds = refundRepository.findOperationalQueue(pendingRefundStatuses)
                .stream()
                .filter(refund -> requestedAtUtc(refund).isBefore(staleRefundCutoff))
                .count();

        ReconciliationState reconciliationState = reconciliationStateRepository
                .findByProviderOrderByLastRunAtUtcDesc(PaymentProvider.SEPAY)
                .stream().findFirst().orElse(null);

        return BusinessMonitoringSummaryResponse.builder()
                .generatedAtUtc(now)
                .durableSePayEventsToday(providerEventRepository
                        .countByReceivedAtUtcBetween(startOfUtcDay, now))
                .providerEventsRetrying(providerEventRepository
                        .countByStatus(PaymentProviderEventStatus.FAILED_RETRYABLE))
                .paymentEventsReviewRequired(providerEventRepository
                        .countByStatus(PaymentProviderEventStatus.REVIEW_REQUIRED))
                .unmatchedIncomingTransfers(providerEventRepository
                        .countByStatusAndTransferTypeIgnoreCase(
                                PaymentProviderEventStatus.REVIEW_REQUIRED, "in"))
                .unmatchedOutgoingRefundTransfers(providerEventRepository
                        .countByStatusAndTransferTypeIgnoreCase(
                                PaymentProviderEventStatus.REVIEW_REQUIRED, "out"))
                .overdueActiveRoomHolds(roomHoldRepository
                        .findExpiredActiveHolds(LocalDateTime.now(HOTEL_ZONE).minusMinutes(
                                Math.max(0L, roomHoldOverdueGraceMinutes))).size())
                .stalePendingRefunds(stalePendingRefunds)
                .pendingCheckoutExceptionRequests(reconciliationRequestRepository
                        .countByStatus(CheckoutReconciliationRequestStatus.PENDING))
                .stalePendingCheckoutExceptionRequests(reconciliationRequestRepository
                        .countByStatusAndCreatedAtUtcBefore(
                                CheckoutReconciliationRequestStatus.PENDING,
                                staleCheckoutCutoff))
                .highRiskActionsLast24Hours(auditLogRepository
                        .countByRiskLevelInAndOccurredAtUtcAfter(
                                List.of(AuditRiskLevel.HIGH, AuditRiskLevel.CRITICAL),
                                now.minus(24, ChronoUnit.HOURS)))
                .pendingEmailAlerts(
                        outboxRepository.countByStatus(AuditNotificationStatus.PENDING)
                                + outboxRepository.countByStatus(AuditNotificationStatus.PROCESSING))
                .failedEmailAlerts(outboxRepository.countByStatus(AuditNotificationStatus.FAILED))
                .staleEmailAlerts(outboxRepository.countByStatusInAndCreatedAtUtcBefore(
                        List.of(AuditNotificationStatus.PENDING, AuditNotificationStatus.PROCESSING),
                        staleEmailCutoff))
                .webhookAuthenticationFailuresInWindow(webhookAuthenticationFailures.size())
                .sePayReconciliationStatus(reconciliationStatus(reconciliationState, now))
                .sePayReconciliationLastRunAtUtc(
                        reconciliationState != null ? reconciliationState.getLastRunAtUtc() : null)
                .refundPendingThresholdMinutes(Math.max(1L, refundPendingThresholdMinutes))
                .reconciliationStaleThresholdMinutes(
                        Math.max(1L, reconciliationStaleThresholdMinutes))
                .checkoutPendingThresholdMinutes(Math.max(1L, checkoutPendingThresholdMinutes))
                .roomHoldOverdueGraceMinutes(Math.max(0L, roomHoldOverdueGraceMinutes))
                .emailOutboxStaleThresholdMinutes(Math.max(1L, emailOutboxStaleThresholdMinutes))
                .webhookAuthenticationFailureThresholdCount(
                        Math.max(1L, webhookAuthenticationFailureThresholdCount))
                .webhookAuthenticationFailureWindowMinutes(
                        Math.max(1L, webhookAuthenticationFailureWindowMinutes))
                .build();
    }

    private void pruneWebhookAuthenticationFailures(Instant now) {
        Instant cutoff = now.minus(
                Math.max(1L, webhookAuthenticationFailureWindowMinutes), ChronoUnit.MINUTES);
        while (true) {
            Instant oldest = webhookAuthenticationFailures.peekFirst();
            if (oldest == null || !oldest.isBefore(cutoff)) return;
            webhookAuthenticationFailures.pollFirst();
        }
    }

    private Instant requestedAtUtc(PaymentRefund refund) {
        if (refund.getRequestedAtUtc() != null) return refund.getRequestedAtUtc();
        if (refund.getRequestedAt() != null) {
            return refund.getRequestedAt().atZone(HOTEL_ZONE).toInstant();
        }
        if (refund.getCreatedAt() != null) {
            return refund.getCreatedAt().atZone(HOTEL_ZONE).toInstant();
        }
        return Instant.EPOCH;
    }

    private String reconciliationStatus(ReconciliationState state, Instant now) {
        if (state == null || state.getLastRunAtUtc() == null) return "NEVER_RUN";
        if (state.getLastError() != null && !state.getLastError().isBlank()) return "FAILED";
        if (state.getLastRunAtUtc().isBefore(now.minus(
                Math.max(1L, reconciliationStaleThresholdMinutes), ChronoUnit.MINUTES))) {
            return "STALE";
        }
        return "HEALTHY";
    }
}
