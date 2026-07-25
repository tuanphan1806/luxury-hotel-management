package com.hotel.backend.service;

import com.hotel.backend.constant.AuditNotificationStatus;
import com.hotel.backend.constant.AuditRiskLevel;
import com.hotel.backend.constant.CheckoutReconciliationRequestStatus;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentProviderEventStatus;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.dto.response.BusinessMonitoringSummaryResponse;
import com.hotel.backend.entity.PaymentRefund;
import com.hotel.backend.entity.ReconciliationState;
import com.hotel.backend.entity.RoomHold;
import com.hotel.backend.repository.AuditNotificationOutboxRepository;
import com.hotel.backend.repository.CheckoutReconciliationRequestRepository;
import com.hotel.backend.repository.PaymentProviderEventRepository;
import com.hotel.backend.repository.PaymentRefundRepository;
import com.hotel.backend.repository.ReconciliationStateRepository;
import com.hotel.backend.repository.ReservationAuditLogRepository;
import com.hotel.backend.repository.RoomHoldRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessMonitoringServiceTest {

    @Mock private PaymentProviderEventRepository providerEventRepository;
    @Mock private PaymentRefundRepository refundRepository;
    @Mock private RoomHoldRepository roomHoldRepository;
    @Mock private CheckoutReconciliationRequestRepository reconciliationRequestRepository;
    @Mock private ReservationAuditLogRepository auditLogRepository;
    @Mock private AuditNotificationOutboxRepository outboxRepository;
    @Mock private ReconciliationStateRepository reconciliationStateRepository;
    @Mock private ReservationAuditService auditService;

    private BusinessMonitoringService service;

    @BeforeEach
    void setUp() {
        service = new BusinessMonitoringService(
                providerEventRepository,
                refundRepository,
                roomHoldRepository,
                reconciliationRequestRepository,
                auditLogRepository,
                outboxRepository,
                reconciliationStateRepository,
                auditService);
        ReflectionTestUtils.setField(service, "refundPendingThresholdMinutes", 60L);
        ReflectionTestUtils.setField(service, "reconciliationStaleThresholdMinutes", 15L);
        ReflectionTestUtils.setField(service, "checkoutPendingThresholdMinutes", 30L);
        ReflectionTestUtils.setField(service, "roomHoldOverdueGraceMinutes", 5L);
        ReflectionTestUtils.setField(service, "emailOutboxStaleThresholdMinutes", 15L);
        ReflectionTestUtils.setField(service, "webhookAuthenticationFailureThresholdCount", 3L);
        ReflectionTestUtils.setField(service, "webhookAuthenticationFailureWindowMinutes", 5L);
    }

    @Test
    void summaryAggregatesOperationalSignalsAndClassifiesHealthyReconciliation() {
        Instant now = Instant.now();
        PaymentRefund staleRefund = PaymentRefund.builder()
                .requestedAtUtc(now.minusSeconds(7_200))
                .build();
        PaymentRefund recentLegacyRefund = PaymentRefund.builder()
                .requestedAt(LocalDateTime.now().minusMinutes(10))
                .build();
        ReconciliationState state = ReconciliationState.builder()
                .provider(PaymentProvider.SEPAY)
                .merchantAccountId("merchant")
                .lastRunAtUtc(now.minusSeconds(120))
                .build();

        when(refundRepository.findOperationalQueue(any()))
                .thenReturn(List.of(staleRefund, recentLegacyRefund));
        when(reconciliationStateRepository.findByProviderOrderByLastRunAtUtcDesc(
                PaymentProvider.SEPAY)).thenReturn(List.of(state));
        when(roomHoldRepository.findExpiredActiveHolds(any(LocalDateTime.class)))
                .thenReturn(List.of(new RoomHold()));
        when(providerEventRepository.countByReceivedAtUtcBetween(
                any(Instant.class), any(Instant.class))).thenReturn(8L);
        when(providerEventRepository.countByStatus(
                PaymentProviderEventStatus.FAILED_RETRYABLE)).thenReturn(2L);
        when(providerEventRepository.countByStatus(
                PaymentProviderEventStatus.REVIEW_REQUIRED)).thenReturn(3L);
        when(providerEventRepository.countByStatusAndTransferTypeIgnoreCase(
                PaymentProviderEventStatus.REVIEW_REQUIRED, "in")).thenReturn(1L);
        when(providerEventRepository.countByStatusAndTransferTypeIgnoreCase(
                PaymentProviderEventStatus.REVIEW_REQUIRED, "out")).thenReturn(2L);
        when(reconciliationRequestRepository.countByStatus(
                CheckoutReconciliationRequestStatus.PENDING)).thenReturn(4L);
        when(reconciliationRequestRepository.countByStatusAndCreatedAtUtcBefore(
                eq(CheckoutReconciliationRequestStatus.PENDING),
                any(Instant.class))).thenReturn(2L);
        when(auditLogRepository.countByRiskLevelInAndOccurredAtUtcAfter(
                eq(List.of(AuditRiskLevel.HIGH, AuditRiskLevel.CRITICAL)),
                any(Instant.class))).thenReturn(5L);
        when(outboxRepository.countByStatus(AuditNotificationStatus.PENDING)).thenReturn(2L);
        when(outboxRepository.countByStatus(AuditNotificationStatus.PROCESSING)).thenReturn(1L);
        when(outboxRepository.countByStatus(AuditNotificationStatus.FAILED)).thenReturn(3L);
        when(outboxRepository.countByStatusInAndCreatedAtUtcBefore(
                eq(List.of(
                        AuditNotificationStatus.PENDING,
                        AuditNotificationStatus.PROCESSING)),
                any(Instant.class))).thenReturn(1L);

        BusinessMonitoringSummaryResponse summary = service.summary();

        assertThat(summary.getDurableSePayEventsToday()).isEqualTo(8L);
        assertThat(summary.getProviderEventsRetrying()).isEqualTo(2L);
        assertThat(summary.getPaymentEventsReviewRequired()).isEqualTo(3L);
        assertThat(summary.getUnmatchedIncomingTransfers()).isEqualTo(1L);
        assertThat(summary.getUnmatchedOutgoingRefundTransfers()).isEqualTo(2L);
        assertThat(summary.getOverdueActiveRoomHolds()).isEqualTo(1L);
        assertThat(summary.getStalePendingRefunds()).isEqualTo(1L);
        assertThat(summary.getPendingCheckoutExceptionRequests()).isEqualTo(4L);
        assertThat(summary.getStalePendingCheckoutExceptionRequests()).isEqualTo(2L);
        assertThat(summary.getHighRiskActionsLast24Hours()).isEqualTo(5L);
        assertThat(summary.getPendingEmailAlerts()).isEqualTo(3L);
        assertThat(summary.getFailedEmailAlerts()).isEqualTo(3L);
        assertThat(summary.getStaleEmailAlerts()).isEqualTo(1L);
        assertThat(summary.getSePayReconciliationStatus()).isEqualTo("HEALTHY");
        assertThat(summary.getSePayReconciliationLastRunAtUtc())
                .isEqualTo(state.getLastRunAtUtc());
    }

    @Test
    void summaryReportsNeverRunFailedAndStaleStatesWithoutMaskingProviderFailure() {
        when(refundRepository.findOperationalQueue(any())).thenReturn(List.of());
        when(roomHoldRepository.findExpiredActiveHolds(any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(reconciliationStateRepository.findByProviderOrderByLastRunAtUtcDesc(
                PaymentProvider.SEPAY)).thenReturn(List.of());

        assertThat(service.summary().getSePayReconciliationStatus())
                .isEqualTo("NEVER_RUN");

        ReconciliationState failed = ReconciliationState.builder()
                .provider(PaymentProvider.SEPAY)
                .merchantAccountId("merchant")
                .lastRunAtUtc(Instant.now().minusSeconds(3_600))
                .lastError("provider unavailable")
                .build();
        when(reconciliationStateRepository.findByProviderOrderByLastRunAtUtcDesc(
                PaymentProvider.SEPAY)).thenReturn(List.of(failed));

        assertThat(service.summary().getSePayReconciliationStatus())
                .isEqualTo("FAILED");

        failed.setLastError(null);
        assertThat(service.summary().getSePayReconciliationStatus())
                .isEqualTo("STALE");
    }

    @Test
    void webhookFailureCreatesOneDeduplicatedHighRiskAuditAtThreshold() {
        service.recordWebhookAuthenticationFailure();
        service.recordWebhookAuthenticationFailure();

        verify(auditService, never()).recordSystem(
                isNull(),
                eq("SEPAY_WEBHOOK"),
                eq("AUTHENTICATION"),
                eq(ReservationAuditAction.WEBHOOK_AUTHENTICATION_REJECTED),
                anyString(),
                isNull(),
                isNull(),
                anyMap(),
                isNull(),
                anyString());

        service.recordWebhookAuthenticationFailure();

        verify(auditService, times(1)).recordSystem(
                isNull(),
                eq("SEPAY_WEBHOOK"),
                eq("AUTHENTICATION"),
                eq(ReservationAuditAction.WEBHOOK_AUTHENTICATION_REJECTED),
                anyString(),
                isNull(),
                isNull(),
                anyMap(),
                isNull(),
                anyString());
    }
}
