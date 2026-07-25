package com.hotel.backend.scheduled;

import com.hotel.backend.config.SePayConfig;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.dto.response.SePayApiTransaction;
import com.hotel.backend.entity.CustomerProfile;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.CustomerProfileRepository;
import com.hotel.backend.repository.InvalidatedTokenRepository;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.repository.RoomHoldRepository;
import com.hotel.backend.repository.UserRepository;
import com.hotel.backend.repository.UserTokenRepository;
import com.hotel.backend.service.BusinessMetricService;
import com.hotel.backend.service.MediaAssetService;
import com.hotel.backend.service.PaymentProviderEventService;
import com.hotel.backend.service.PaymentSessionExpiryService;
import com.hotel.backend.service.ReconciliationStateService;
import com.hotel.backend.service.SePayApiClient;
import com.hotel.backend.service.SePayService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchedulerRegressionTest {

    @Mock private MediaAssetService mediaAssetService;
    @Mock private PaymentTransactionRepository paymentTransactionRepository;
    @Mock private PaymentSessionExpiryService paymentSessionExpiryService;
    @Mock private ReservationRepository reservationRepository;
    @Mock private RoomHoldRepository roomHoldRepository;
    @Mock private BusinessMetricService businessMetrics;
    @Mock private SePayConfig sePayConfig;
    @Mock private SePayApiClient sePayApiClient;
    @Mock private SePayService sePayService;
    @Mock private ReconciliationStateService reconciliationStateService;
    @Mock private PaymentProviderEventService paymentProviderEventService;
    @Mock private InvalidatedTokenRepository invalidatedTokenRepository;
    @Mock private UserTokenRepository userTokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private CustomerProfileRepository customerProfileRepository;

    @Test
    void mediaCleanupClampsInvalidTtlsBeforeDelegating() {
        MediaAssetCleanupScheduler scheduler = new MediaAssetCleanupScheduler(mediaAssetService);
        ReflectionTestUtils.setField(scheduler, "temporaryTtlHours", 0L);
        ReflectionTestUtils.setField(scheduler, "orphanedTtlHours", -4L);
        ReflectionTestUtils.setField(scheduler, "cleanupBatchSize", 75);
        when(mediaAssetService.cleanupExpired(
                Duration.ofHours(1), Duration.ofHours(1), 75)).thenReturn(2);

        scheduler.cleanupExpiredMedia();

        verify(mediaAssetService).cleanupExpired(
                Duration.ofHours(1), Duration.ofHours(1), 75);
    }

    @Test
    void paymentMaintenanceIsolatesOnePrePaymentFailureAndCompletesTheRun() {
        PaymentMaintenanceScheduler scheduler = paymentMaintenanceScheduler();
        ReflectionTestUtils.setField(scheduler, "prePaymentSessionMinutes", 0);
        when(reservationRepository.findStalePrePaymentSessionIds(
                any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(11L, 12L));
        when(paymentSessionExpiryService.expirePrePaymentReservation(11L))
                .thenReturn(true);
        when(paymentSessionExpiryService.expirePrePaymentReservation(12L))
                .thenThrow(new IllegalStateException("locked"));

        scheduler.expirePrePaymentReservations();

        verify(businessMetrics).increment(
                "hotel.payment.session.timeout", "type", "pre_payment");
        verify(businessMetrics).increment(
                "hotel.scheduler.failures", "job", "pre_payment_expiry");
        verify(businessMetrics).increment(
                "hotel.scheduler.runs", "job", "pre_payment_expiry");
    }

    @Test
    void paymentMaintenanceProcessesEveryPendingTransactionCandidate() {
        PaymentMaintenanceScheduler scheduler = paymentMaintenanceScheduler();
        when(paymentTransactionRepository.findExpiredPendingIds(
                eq(PaymentStatus.PENDING),
                any(LocalDateTime.class),
                any(LocalDateTime.class)))
                .thenReturn(List.of("PAY-1", "PAY-2"));
        when(paymentSessionExpiryService.timeout("PAY-1")).thenReturn(true);
        when(paymentSessionExpiryService.timeout("PAY-2")).thenReturn(false);

        scheduler.expirePendingTransactions();

        verify(paymentSessionExpiryService).timeout("PAY-1");
        verify(paymentSessionExpiryService).timeout("PAY-2");
        verify(businessMetrics).increment(
                "hotel.payment.session.timeout", "type", "payment_transaction");
        verify(businessMetrics).increment(
                "hotel.scheduler.runs", "job", "payment_expiry");
    }

    @Test
    void roomHoldExpiryIsolatesOneAggregateFailureAndCompletesTheRun() {
        RoomHoldExpiryScheduler scheduler = new RoomHoldExpiryScheduler(
                roomHoldRepository, paymentSessionExpiryService, businessMetrics);
        when(roomHoldRepository.findReservationIdsWithExpiredActiveHolds(
                any(LocalDateTime.class))).thenReturn(List.of(21L, 22L));
        when(paymentSessionExpiryService.timeoutDepositReservation(21L))
                .thenReturn(true);
        when(paymentSessionExpiryService.timeoutDepositReservation(22L))
                .thenThrow(new IllegalStateException("concurrent payment"));

        scheduler.expireHolds();

        verify(businessMetrics).increment("hotel.room.hold.auto.expired");
        verify(businessMetrics).increment(
                "hotel.scheduler.failures", "job", "room_hold_expiry");
        verify(businessMetrics).increment(
                "hotel.scheduler.runs", "job", "room_hold_expiry");
    }

    @Test
    void reconciliationDoesNothingWhenProviderPollingIsDisabled() {
        when(sePayConfig.isReconciliationEnabled()).thenReturn(false);
        SePayReconciliationScheduler scheduler = reconciliationScheduler();

        scheduler.reconcileRecentIncomingTransactions();

        verifyNoInteractions(
                sePayApiClient,
                sePayService,
                reconciliationStateService,
                paymentProviderEventService,
                businessMetrics);
    }

    @Test
    void reconciliationProcessesTransactionsInProviderTimeOrderAndAdvancesCursor() {
        Instant fromUtc = Instant.parse("2026-07-24T01:00:00Z");
        SePayApiTransaction later = transaction("later", "2026-07-24 08:02:00");
        SePayApiTransaction earlier = transaction("earlier", "2026-07-24 08:01:00");

        when(sePayConfig.isReconciliationEnabled()).thenReturn(true);
        when(sePayConfig.getApiAccessToken()).thenReturn("configured");
        when(sePayConfig.getApiBaseUrl()).thenReturn("https://provider.example");
        when(sePayService.configuredMerchantAccountId()).thenReturn("merchant");
        when(reconciliationStateService.queryFromUtc(
                PaymentProvider.SEPAY, "merchant")).thenReturn(fromUtc);
        when(paymentProviderEventService.earliestDueRetryOccurredAt(
                PaymentProvider.SEPAY)).thenReturn(Optional.empty());
        when(sePayApiClient.listTransactions(sePayConfig, fromUtc))
                .thenReturn(List.of(later, earlier));
        when(sePayService.providerOccurredAtUtc(later))
                .thenReturn(Instant.parse("2026-07-24T01:02:00Z"));
        when(sePayService.providerOccurredAtUtc(earlier))
                .thenReturn(Instant.parse("2026-07-24T01:01:00Z"));

        reconciliationScheduler().reconcileRecentIncomingTransactions();

        InOrder order = inOrder(sePayService);
        order.verify(sePayService).reconcile(earlier);
        order.verify(sePayService).reconcile(later);
        verify(reconciliationStateService).recordDurableEvent(
                PaymentProvider.SEPAY,
                "merchant",
                "earlier",
                Instant.parse("2026-07-24T01:01:00Z"));
        verify(reconciliationStateService).recordDurableEvent(
                PaymentProvider.SEPAY,
                "merchant",
                "later",
                Instant.parse("2026-07-24T01:02:00Z"));
        verify(reconciliationStateService).recordRunSuccess(
                PaymentProvider.SEPAY, "merchant");
        verify(businessMetrics).increment(
                "hotel.scheduler.runs", "job", "sepay_reconciliation");
    }

    @Test
    void reconciliationRecordsProviderFailureWithoutEscapingScheduler() {
        Instant fromUtc = Instant.parse("2026-07-24T01:00:00Z");
        RuntimeException failure = new RuntimeException("provider unavailable");
        when(sePayConfig.isReconciliationEnabled()).thenReturn(true);
        when(sePayConfig.getApiAccessToken()).thenReturn("configured");
        when(sePayConfig.getApiBaseUrl()).thenReturn("https://provider.example");
        when(sePayService.configuredMerchantAccountId()).thenReturn("merchant");
        when(reconciliationStateService.queryFromUtc(
                PaymentProvider.SEPAY, "merchant")).thenReturn(fromUtc);
        when(paymentProviderEventService.earliestDueRetryOccurredAt(
                PaymentProvider.SEPAY)).thenReturn(Optional.empty());
        when(sePayApiClient.listTransactions(sePayConfig, fromUtc))
                .thenThrow(failure);

        reconciliationScheduler().reconcileRecentIncomingTransactions();

        verify(reconciliationStateService).recordRunFailure(
                PaymentProvider.SEPAY, "merchant", failure);
        verify(reconciliationStateService, never()).recordRunSuccess(
                PaymentProvider.SEPAY, "merchant");
        verify(businessMetrics).increment(
                "hotel.scheduler.failures", "job", "sepay_reconciliation");
    }

    @Test
    void tokenCleanupRemovesBothInvalidatedTokensAndExpiredSessions() {
        TokenCleanupJob scheduler = new TokenCleanupJob(
                invalidatedTokenRepository, userTokenRepository);

        scheduler.cleanExpiredTokens();

        verify(invalidatedTokenRepository).deleteAllByExpiryTimeBefore(any(Date.class));
        verify(userTokenRepository).deleteAllByRefreshTokenExpiresAtBefore(any(Date.class));
    }

    @Test
    void verificationCleanupDeactivatesExpiredUserAndRemovesOnlyEmptyProfile() {
        User user = User.builder()
                .status(UserStatus.PENDING_VERIFICATION)
                .verificationCode("hash")
                .verificationExpiresAt(LocalDateTime.now().minusHours(2))
                .securityVersion(4L)
                .build();
        user.setId(31L);
        CustomerProfile profile = CustomerProfile.builder()
                .fullName("QA User")
                .linkedUser(user)
                .build();
        UserVerificationCleanupScheduler scheduler =
                new UserVerificationCleanupScheduler(
                        userRepository, customerProfileRepository);
        ReflectionTestUtils.setField(scheduler, "verificationTimeoutHours", 48L);
        when(userRepository.findByStatusAndEmailVerifiedFalseAndCreatedAtBefore(
                eq(UserStatus.PENDING_VERIFICATION),
                any(LocalDateTime.class))).thenReturn(List.of(user));
        when(customerProfileRepository.findWithoutReservationsByLinkedUserId(31L))
                .thenReturn(Optional.of(profile));

        scheduler.expireUnverifiedUsers();

        verify(customerProfileRepository).delete(profile);
        verify(userRepository).saveAll(List.of(user));
        org.assertj.core.api.Assertions.assertThat(user.getStatus())
                .isEqualTo(UserStatus.INACTIVE);
        org.assertj.core.api.Assertions.assertThat(user.getVerificationCode()).isNull();
        org.assertj.core.api.Assertions.assertThat(user.getVerificationExpiresAt()).isNull();
        org.assertj.core.api.Assertions.assertThat(user.getSecurityVersion()).isEqualTo(5L);
    }

    private PaymentMaintenanceScheduler paymentMaintenanceScheduler() {
        return new PaymentMaintenanceScheduler(
                paymentTransactionRepository,
                paymentSessionExpiryService,
                reservationRepository,
                businessMetrics);
    }

    private SePayReconciliationScheduler reconciliationScheduler() {
        return new SePayReconciliationScheduler(
                sePayConfig,
                sePayApiClient,
                sePayService,
                reconciliationStateService,
                paymentProviderEventService,
                businessMetrics);
    }

    private SePayApiTransaction transaction(String id, String occurredAt) {
        return new SePayApiTransaction(
                id,
                occurredAt,
                "masked",
                "in",
                BigDecimal.TEN,
                BigDecimal.ZERO,
                BigDecimal.TEN,
                "PAY",
                "REF-" + id,
                null,
                "TPBank");
    }
}
