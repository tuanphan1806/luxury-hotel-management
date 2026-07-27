package com.hotel.backend.service;

import com.hotel.backend.config.PricingV2Properties;
import com.hotel.backend.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StayWindowValidationServiceTest {

    private PricingV2Properties properties;
    private StayWindowValidationService service;
    private LocalDateTime checkIn;

    @BeforeEach
    void setUp() {
        properties = new PricingV2Properties();
        properties.setMaxStayDays(365);
        service = new StayWindowValidationService(properties);
        checkIn = LocalDateTime.of(2026, 7, 28, 14, 0);
    }

    @Test
    void exactConfiguredMaximumIsAccepted() {
        assertThatCode(() -> service.validate(
                checkIn, checkIn.plusDays(365)))
                .doesNotThrowAnyException();
    }

    @Test
    void durationBeyondConfiguredMaximumIsRejected() {
        assertThatThrownBy(() -> service.validate(
                checkIn, checkIn.plusDays(365).plusMinutes(1)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("tối đa 365 ngày");
    }

    @Test
    void invalidOrMissingWindowIsRejected() {
        assertThatThrownBy(() -> service.validate(checkIn, checkIn))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.validate(null, checkIn))
                .isInstanceOf(AppException.class);
    }

    @Test
    void unsafeConfigurationIsClampedToThreeYears() {
        properties.setMaxStayDays(Integer.MAX_VALUE);

        assertThatCode(() -> service.validate(
                checkIn, checkIn.plusDays(1095)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.validate(
                checkIn, checkIn.plusDays(1095).plusMinutes(1)))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("tối đa 1095 ngày");
    }
}
