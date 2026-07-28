package com.hotel.backend.statistics;

import com.hotel.backend.exception.AppException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatisticsPeriodTest {

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-28T20:00:00Z"),
            ZoneOffset.UTC);

    @Test
    void defaultsToThirtyInclusiveHotelBusinessDates() {
        StatisticsPeriod period = StatisticsPeriod.resolve(null, null, clock);

        assertThat(period.from()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(period.to()).isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(period.fromUtc()).isEqualTo(
                Instant.parse("2026-06-29T17:00:00Z"));
        assertThat(period.toUtcExclusive()).isEqualTo(
                Instant.parse("2026-07-29T17:00:00Z"));
    }

    @Test
    void previousPeriodHasExactlyTheSameNumberOfDays() {
        StatisticsPeriod current = StatisticsPeriod.resolve(
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 7, 26),
                clock);

        StatisticsPeriod previous = current.previous();

        assertThat(previous.from()).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(previous.to()).isEqualTo(LocalDate.of(2026, 7, 19));
    }

    @Test
    void rejectsReverseAndExcessiveRanges() {
        assertThatThrownBy(() -> StatisticsPeriod.resolve(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 7, 1),
                clock)).isInstanceOf(AppException.class);

        assertThatThrownBy(() -> StatisticsPeriod.resolve(
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2026, 1, 1),
                clock)).isInstanceOf(AppException.class);
    }

    @Test
    void weekStartsOnMondayAndMonthStartsOnFirstDay() {
        LocalDate wednesday = LocalDate.of(2026, 7, 29);

        assertThat(StatisticsGranularity.WEEK.bucketStart(wednesday))
                .isEqualTo(LocalDate.of(2026, 7, 27));
        assertThat(StatisticsGranularity.MONTH.bucketStart(wednesday))
                .isEqualTo(LocalDate.of(2026, 7, 1));
    }
}
