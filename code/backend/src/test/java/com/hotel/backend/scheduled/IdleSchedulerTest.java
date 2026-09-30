package com.hotel.backend.scheduled;

import com.hotel.backend.repository.*;
import com.hotel.backend.service.*;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.hotel.backend.constant.PaymentStatus.PENDING;
import static com.hotel.backend.constant.HoldStatus.ACTIVE;
import static com.hotel.backend.constant.ReservationStatus.PAYMENT_PENDING;

class IdleSchedulerTest {
    private final MaintenancePollGate gate = new MaintenancePollGate(true, 900000, mock(Clock.class));
    private final AuditNotificationOutboxStore store = mock(AuditNotificationOutboxStore.class);
    private final EmailService email = mock(EmailService.class);
    private final BusinessMetricService metrics = mock(BusinessMetricService.class);
    private final RoomHoldRepository holds = mock(RoomHoldRepository.class);
    private final PaymentTransactionRepository payments = mock(PaymentTransactionRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final PaymentSessionExpiryService expiry = mock(PaymentSessionExpiryService.class);

    @Test void emptyEmailQueueDoesNotHitDatabaseAgainUntilWake() {
        var scheduler = new AuditAlertDeliveryScheduler(store, email, metrics, gate);
        scheduler.deliver();
        clearInvocations(store, metrics);
        scheduler.deliver();
        verifyNoInteractions(store, metrics, email);
        gate.wake();
        scheduler.deliver();
        verify(store).dueIds(anyInt());
    }

    @Test void futureRetryOrProcessingRowPreventsBackoffEvenWhenNothingDue() {
        when(store.hasPendingWork()).thenReturn(true);
        var scheduler = new AuditAlertDeliveryScheduler(store, email, metrics, gate);
        scheduler.deliver();
        scheduler.deliver();
        verify(store, times(2)).stuckIds(anyInt(), anyLong());
        verify(store, times(2)).dueIds(anyInt());
    }

    @Test void databaseFailureDoesNotCacheAnEmptyQueue() {
        when(store.hasPendingWork()).thenThrow(new IllegalStateException("DB unavailable"));
        var scheduler = new AuditAlertDeliveryScheduler(store, email, metrics, gate);
        org.assertj.core.api.Assertions.assertThatThrownBy(scheduler::deliver).isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(scheduler::deliver).isInstanceOf(IllegalStateException.class);
        verify(store, times(2)).dueIds(anyInt());
    }

    @Test void emptyPaymentAndHoldQueuesSkipQueriesButNewRequestWakesAllTasks() {
        var holdScheduler = new RoomHoldExpiryScheduler(holds, expiry, metrics, gate);
        var paymentScheduler = new PaymentMaintenanceScheduler(payments, expiry, reservations, metrics, gate);
        holdScheduler.expireHolds();
        paymentScheduler.expirePrePaymentReservations();
        paymentScheduler.expirePendingTransactions();
        clearInvocations(holds, payments, reservations, metrics);
        holdScheduler.expireHolds();
        paymentScheduler.expirePrePaymentReservations();
        paymentScheduler.expirePendingTransactions();
        verifyNoInteractions(holds, payments, reservations, metrics, expiry);
        gate.wake();
        holdScheduler.expireHolds();
        paymentScheduler.expirePrePaymentReservations();
        paymentScheduler.expirePendingTransactions();
        verify(holds).findReservationIdsWithExpiredActiveHolds(any());
        verify(payments).findExpiredPendingIds(eq(PENDING), any(), any());
        verify(reservations).findStalePrePaymentSessionIds(any(), any());
    }

    @Test void unexpiredPaymentAndHoldWorkRetainsOriginalCadence() {
        when(holds.existsByStatus(ACTIVE)).thenReturn(true);
        when(payments.existsByStatus(PENDING)).thenReturn(true);
        when(reservations.existsByStatus(PAYMENT_PENDING)).thenReturn(true);
        var holdScheduler = new RoomHoldExpiryScheduler(holds, expiry, metrics, gate);
        var paymentScheduler = new PaymentMaintenanceScheduler(payments, expiry, reservations, metrics, gate);
        for (int i = 0; i < 2; i++) {
            holdScheduler.expireHolds();
            paymentScheduler.expirePrePaymentReservations();
            paymentScheduler.expirePendingTransactions();
        }
        verify(holds, times(2)).findReservationIdsWithExpiredActiveHolds(any());
        verify(payments, times(2)).findExpiredPendingIds(eq(PENDING), any(), any());
        verify(reservations, times(2)).findStalePrePaymentSessionIds(any(), any());
    }
}
