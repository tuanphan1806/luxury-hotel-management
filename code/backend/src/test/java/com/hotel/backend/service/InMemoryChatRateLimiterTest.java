package com.hotel.backend.service;

import com.hotel.backend.service.chatbot.InMemoryChatRateLimiter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryChatRateLimiterTest {

    @Test
    void allowsTenRequestsAndLimitsTheEleventhForTheSameClient() {
        InMemoryChatRateLimiter limiter = new InMemoryChatRateLimiter();

        for (int request = 0; request < 10; request++) {
            assertFalse(limiter.isRateLimited("same-client"));
        }

        assertTrue(limiter.isRateLimited("same-client"));
        assertFalse(limiter.isRateLimited("different-client"));
    }
}
