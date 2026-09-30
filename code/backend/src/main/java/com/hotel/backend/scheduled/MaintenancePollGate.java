package com.hotel.backend.scheduled;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Skips empty-queue probes, never pending work. State is only a disposable hint. */
@Component
public class MaintenancePollGate {
    public enum Task { EMAIL, HOLDS, PRE_PAYMENT, PAYMENT }
    public record Permit(Task task, long generation, long window) { }
    private record State(long generation, long window, boolean pending) { }
    private final boolean enabled;
    private final long idleIntervalMs;
    private final Clock clock;
    private final Map<Task, State> states = new EnumMap<>(Task.class);
    private long generation;

    @Autowired
    public MaintenancePollGate(
            @Value("${app.maintenance.idle-polling-enabled:false}") boolean enabled,
            @Value("${app.maintenance.idle-poll-interval-ms:900000}") long idleIntervalMs) {
        this(enabled, idleIntervalMs, Clock.systemUTC());
    }

    MaintenancePollGate(boolean enabled, long idleIntervalMs, Clock clock) {
        this.enabled = enabled;
        this.idleIntervalMs = Math.max(600000L, Math.min(900000L, idleIntervalMs));
        this.clock = clock;
    }

    public synchronized void wake() { generation++; }

    public void wakeAfterCommit() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { wake(); }
            });
        } else {
            wake();
        }
    }

    public synchronized Optional<Permit> begin(Task task) {
        long window = Math.floorDiv(clock.millis(), idleIntervalMs);
        State state = states.get(task);
        if (enabled && state != null && !state.pending()
                && state.generation() == generation && state.window() == window) {
            return Optional.empty();
        }
        // Fixed windows group idle probes, leaving a shared quiet interval.
        return Optional.of(new Permit(task, generation, window));
    }

    public synchronized void complete(Permit permit, boolean pending) {
        // Preserve the pre-query generation so a concurrent commit/wake cannot be lost.
        states.put(permit.task(), new State(permit.generation(), permit.window(), pending));
    }
}
