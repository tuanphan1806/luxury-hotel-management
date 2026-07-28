package com.hotel.backend.service;

import com.hotel.backend.config.PricingV2Properties;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * One validation boundary for customer-entered and operator-entered stay
 * windows. The cap protects availability and quote endpoints from producing
 * unbounded per-cycle pricing payloads while remaining configurable for
 * legitimate long-stay operations.
 */
@Service
@RequiredArgsConstructor
public class StayWindowValidationService {

    private final PricingV2Properties properties;

    public void validate(LocalDateTime checkIn, LocalDateTime checkOut) {
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) {
            throw new AppException(ErrorCode.RESERVATION_INVALID_DATE);
        }
        int maximumDays = properties.safeMaxStayDays();
        if (Duration.between(checkIn, checkOut)
                .compareTo(Duration.ofDays(maximumDays)) > 0) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "Kỳ lưu trú tối đa " + maximumDays
                            + " ngày; hãy chia đơn hoặc liên hệ quản lý");
        }
    }
}
