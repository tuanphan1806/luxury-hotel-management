package com.hotel.backend.service.chatbot;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Existing per-IP in-memory chatbot rate-limit policy, extracted unchanged
 * from the chatbot orchestration service.
 */
public final class InMemoryChatRateLimiter {

    private static final int MAX_REQUESTS_PER_WINDOW = 10;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(1);

    private final Map<String, RateLimitBucket> rateLimitBuckets = new ConcurrentHashMap<>();

    /**
     * Rate limit đơn giản theo IP trong bộ nhớ. Nếu deploy nhiều instance, nên thay bằng Redis/Bucket4j.
     */
    public boolean isRateLimited(String clientIp) {
        String key = (clientIp == null || clientIp.isBlank()) ? "unknown" : clientIp;
        Instant now = Instant.now();

        RateLimitBucket bucket = rateLimitBuckets.compute(key, (ignored, existing) -> {
            if (existing == null
                    || Duration.between(existing.windowStartedAt(), now).compareTo(RATE_LIMIT_WINDOW) >= 0) {
                return new RateLimitBucket(now, 1);
            }

            return new RateLimitBucket(existing.windowStartedAt(), existing.count() + 1);
        });

        cleanupOldRateLimitBuckets(now);

        return bucket.count() > MAX_REQUESTS_PER_WINDOW;
    }

    private void cleanupOldRateLimitBuckets(Instant now) {
        if (rateLimitBuckets.size() < 500) {
            return;
        }

        rateLimitBuckets.entrySet().removeIf(entry ->
                Duration.between(entry.getValue().windowStartedAt(), now)
                        .compareTo(RATE_LIMIT_WINDOW.multipliedBy(2)) > 0
        );
    }

    private record RateLimitBucket(Instant windowStartedAt, int count) {
    }
}
