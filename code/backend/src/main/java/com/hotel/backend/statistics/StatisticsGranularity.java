package com.hotel.backend.statistics;

import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;

import java.time.LocalDate;
import java.util.Locale;

public enum StatisticsGranularity {
    DAY,
    WEEK,
    MONTH;

    public static StatisticsGranularity parse(String value) {
        try {
            return value == null
                    ? DAY
                    : valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "granularity chỉ nhận day, week hoặc month");
        }
    }

    public LocalDate bucketStart(LocalDate date) {
        return switch (this) {
            case DAY -> date;
            case WEEK -> date.minusDays(date.getDayOfWeek().getValue() - 1L);
            case MONTH -> date.withDayOfMonth(1);
        };
    }

    public LocalDate nextBucket(LocalDate bucketStart) {
        return switch (this) {
            case DAY -> bucketStart.plusDays(1);
            case WEEK -> bucketStart.plusWeeks(1);
            case MONTH -> bucketStart.plusMonths(1);
        };
    }

    public String postgresUnit() {
        return name().toLowerCase(Locale.ROOT);
    }
}
