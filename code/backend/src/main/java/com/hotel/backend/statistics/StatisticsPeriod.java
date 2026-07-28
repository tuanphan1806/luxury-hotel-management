package com.hotel.backend.statistics;

import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public record StatisticsPeriod(
        LocalDate from,
        LocalDate to,
        LocalDateTime fromLocal,
        LocalDateTime toLocalExclusive,
        Instant fromUtc,
        Instant toUtcExclusive) {

    public static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final long MAX_RANGE_DAYS = 1_827;

    public static StatisticsPeriod resolve(LocalDate requestedFrom,
                                           LocalDate requestedTo,
                                           Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(HOTEL_ZONE));
        LocalDate to = requestedTo != null ? requestedTo : today;
        LocalDate from = requestedFrom != null
                ? requestedFrom
                : to.minusDays(29);
        if (from.isAfter(to)) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "Ngày bắt đầu không được sau ngày kết thúc");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "Khoảng báo cáo tối đa là 5 năm");
        }
        LocalDateTime fromLocal = from.atStartOfDay();
        LocalDateTime toLocalExclusive = to.plusDays(1).atStartOfDay();
        return new StatisticsPeriod(
                from,
                to,
                fromLocal,
                toLocalExclusive,
                fromLocal.atZone(HOTEL_ZONE).toInstant(),
                toLocalExclusive.atZone(HOTEL_ZONE).toInstant());
    }

    public StatisticsPeriod previous() {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate previousTo = from.minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(days - 1);
        LocalDateTime previousFromLocal = previousFrom.atStartOfDay();
        LocalDateTime previousToLocalExclusive = previousTo.plusDays(1).atStartOfDay();
        return new StatisticsPeriod(
                previousFrom,
                previousTo,
                previousFromLocal,
                previousToLocalExclusive,
                previousFromLocal.atZone(HOTEL_ZONE).toInstant(),
                previousToLocalExclusive.atZone(HOTEL_ZONE).toInstant());
    }
}
