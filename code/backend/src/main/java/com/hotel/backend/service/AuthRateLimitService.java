package com.hotel.backend.service;

import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthRateLimitService {
    private static final int MAX_TRACKED_KEYS = 50_000;
    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

    public void check(String key, int maximumAttempts, Duration window) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(window);
        if (attempts.size() > 10_000) {
            removeStaleKeys(now.minus(Duration.ofHours(24)));
        }
        if (attempts.size() >= MAX_TRACKED_KEYS && !attempts.containsKey(key)) {
            throw new AppException(ErrorCode.AUTH_RATE_LIMITED);
        }
        Deque<Instant> entries = attempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());

        synchronized (entries) {
            while (!entries.isEmpty() && entries.peekFirst().isBefore(cutoff)) {
                entries.removeFirst();
            }
            if (entries.size() >= maximumAttempts) {
                throw new AppException(ErrorCode.AUTH_RATE_LIMITED);
            }
            entries.addLast(now);
        }
    }

    public void reset(String key) {
        attempts.remove(key);
    }

    private void removeStaleKeys(Instant cutoff) {
        attempts.entrySet().removeIf(entry -> {
            Deque<Instant> entries = entry.getValue();
            synchronized (entries) {
                return entries.isEmpty() || entries.peekLast().isBefore(cutoff);
            }
        });
    }
}
