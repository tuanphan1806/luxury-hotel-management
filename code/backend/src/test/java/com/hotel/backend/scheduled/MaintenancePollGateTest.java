package com.hotel.backend.scheduled;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static com.hotel.backend.scheduled.MaintenancePollGate.Task.*;

class MaintenancePollGateTest {
    private final Clock clock = mock(Clock.class);
    private final MaintenancePollGate gate = new MaintenancePollGate(true, 900000, clock);

    @Test void emptyQueueSleepsUntilRecoveryWindowButPendingQueueKeepsNormalCadence() {
        gate.complete(gate.begin(EMAIL).orElseThrow(), false);
        assertThat(gate.begin(EMAIL)).isEmpty();
        assertThat(gate.begin(HOLDS)).isPresent();
        when(clock.millis()).thenReturn(900000L);
        gate.complete(gate.begin(EMAIL).orElseThrow(), true);
        assertThat(gate.begin(EMAIL)).isPresent();
    }

    @Test void requestCommittingDuringProbeIsNotLost() {
        var permit = gate.begin(PAYMENT).orElseThrow();
        gate.wake();
        gate.complete(permit, false);
        assertThat(gate.begin(PAYMENT)).isPresent();
    }

    @Test void unsuccessfulProbeIsRetriedAndRestartDoesNotInheritEmptyState() {
        assertThat(gate.begin(EMAIL)).isPresent();
        assertThat(gate.begin(EMAIL)).isPresent();
        gate.complete(gate.begin(EMAIL).orElseThrow(), false);
        assertThat(new MaintenancePollGate(true, 900000, clock).begin(EMAIL)).isPresent();
    }

    @Test void disabledOptimizationRetainsOldPollingBehavior() {
        var disabled = new MaintenancePollGate(false, 900000, clock);
        disabled.complete(disabled.begin(EMAIL).orElseThrow(), false);
        assertThat(disabled.begin(EMAIL)).isPresent();
    }

    @Test void transactionalSignalWakesOnlyAfterCommit() {
        gate.complete(gate.begin(EMAIL).orElseThrow(), false);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            gate.wakeAfterCommit();
            assertThat(gate.begin(EMAIL)).isEmpty();
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
            assertThat(gate.begin(EMAIL)).isPresent();
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test void rolledBackSignalDoesNotWakeEmptyQueue() {
        gate.complete(gate.begin(EMAIL).orElseThrow(), false);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            gate.wakeAfterCommit();
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(1));
            assertThat(gate.begin(EMAIL)).isEmpty();
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }
}
