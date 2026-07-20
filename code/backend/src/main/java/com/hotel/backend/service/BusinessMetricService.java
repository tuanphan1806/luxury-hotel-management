package com.hotel.backend.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;

/**
 * Low-cardinality business metrics only. Identifiers such as reservation,
 * payment, refund, account or actor are deliberately kept out of metric tags.
 */
@Service
@RequiredArgsConstructor
public class BusinessMetricService {
    private final MeterRegistry meterRegistry;

    public void increment(String name, String... tags) {
        Counter.builder(name).tags(tags).register(meterRegistry).increment();
    }

    public void recordDuration(String name, long startedAtNanos, String... tags) {
        long elapsed = Math.max(0L, System.nanoTime() - startedAtNanos);
        Timer.builder(name).tags(tags).register(meterRegistry)
                .record(Duration.ofNanos(elapsed));
    }

    public String outcomeTag(Object value) {
        String normalized = value == null ? "unknown"
                : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_]{1,64}")) return "other";
        return normalized;
    }
}
