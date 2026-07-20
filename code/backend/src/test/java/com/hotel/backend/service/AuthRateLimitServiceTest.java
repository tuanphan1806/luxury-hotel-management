package com.hotel.backend.service;

import com.hotel.backend.exception.AppException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthRateLimitServiceTest {

    @Test
    void blocksRequestsAfterConfiguredLimit() {
        AuthRateLimitService service = new AuthRateLimitService();

        service.check("login:client:user", 2, Duration.ofMinutes(1));
        service.check("login:client:user", 2, Duration.ofMinutes(1));

        assertThatThrownBy(() -> service.check("login:client:user", 2, Duration.ofMinutes(1)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("quá nhiều");
    }

    @Test
    void resetAllowsAValidLoginToStartANewWindow() {
        AuthRateLimitService service = new AuthRateLimitService();
        service.check("login:client:user", 1, Duration.ofMinutes(1));

        service.reset("login:client:user");

        service.check("login:client:user", 1, Duration.ofMinutes(1));
    }
}
