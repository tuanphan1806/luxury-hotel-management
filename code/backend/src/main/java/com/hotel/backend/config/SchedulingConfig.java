package com.hotel.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Keeps background jobs enabled by default in every runtime environment while
 * allowing deterministic test suites (and emergency operations) to disable
 * them explicitly.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "app.scheduling.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SchedulingConfig {
}
