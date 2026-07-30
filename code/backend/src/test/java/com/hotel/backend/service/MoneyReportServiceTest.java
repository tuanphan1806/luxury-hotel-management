package com.hotel.backend.service;

import com.hotel.backend.exception.AppException;
import com.hotel.backend.dto.response.MoneyReportResponse;
import com.hotel.backend.statistics.BusinessStatisticsQueryRepository;
import com.hotel.backend.statistics.StatisticsGranularity;
import com.hotel.backend.statistics.StatisticsPeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MoneyReportServiceTest {

    @Mock
    private BusinessStatisticsQueryRepository queryRepository;

    private MoneyReportService service;

    @BeforeEach
    void setUp() {
        service = new MoneyReportService(
                queryRepository,
                Clock.fixed(
                        Instant.parse("2026-07-30T03:00:00Z"),
                        ZoneOffset.UTC));
    }

    @Test
    void reportSeparatesPaymentAndRefundChannelsAndFillsMissingDays() {
        when(queryRepository.moneyFlow(
                any(StatisticsPeriod.class),
                eq(StatisticsGranularity.DAY)))
                .thenReturn(List.of(
                        new BusinessStatisticsQueryRepository.MoneyFlowRow(
                                LocalDate.of(2026, 7, 29),
                                bd(300_000),
                                bd(500_000),
                                bd(40_000),
                                bd(60_000),
                                4,
                                2,
                                1,
                                bd(2_000))));

        var report = service.report(
                LocalDate.of(2026, 7, 28),
                LocalDate.of(2026, 7, 30),
                "day");

        assertThat(report.periods()).hasSize(3);
        assertThat(report.periods().get(0).amounts().totalIncome()).isZero();
        assertThat(report.periods().get(1).amounts().totalIncome())
                .isEqualByComparingTo("800000");
        assertThat(report.periods().get(1).amounts().totalRefund())
                .isEqualByComparingTo("100000");
        assertThat(report.totals().netRevenue())
                .isEqualByComparingTo("700000");
        assertThat(report.totals().paymentCount()).isEqualTo(4);
        assertThat(report.totals().refundCount()).isEqualTo(2);
        assertThat(report.unmatchedTransferCount()).isEqualTo(1);
        assertThat(report.unmatchedTransferAmount())
                .isEqualByComparingTo("2000");
        assertThat(report.totals().transferIncome())
                .isEqualByComparingTo("500000");
    }

    @Test
    void exactWindowReturnsCanonicalTotals() {
        Instant from = Instant.parse("2026-07-30T01:00:00Z");
        Instant to = Instant.parse("2026-07-30T09:00:00Z");
        when(queryRepository.moneyWindow(from, to))
                .thenReturn(new BusinessStatisticsQueryRepository.MoneyWindowRow(
                        bd(120_000),
                        bd(80_000),
                        bd(20_000),
                        bd(10_000),
                        3,
                        2));

        var totals = service.summarizeWindow(from, to);

        assertThat(totals.totalIncome()).isEqualByComparingTo("200000");
        assertThat(totals.totalRefund()).isEqualByComparingTo("30000");
        assertThat(totals.netRevenue()).isEqualByComparingTo("170000");
    }

    @Test
    void weeklyReportClampsPartialBucketLabelsToRequestedRange() {
        when(queryRepository.moneyFlow(
                any(StatisticsPeriod.class),
                eq(StatisticsGranularity.WEEK)))
                .thenReturn(List.of());

        var report = service.report(
                LocalDate.of(2026, 7, 29),
                LocalDate.of(2026, 8, 4),
                "week");

        assertThat(report.periods()).hasSize(2);
        assertThat(report.periods().get(0).period())
                .isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(report.periods().get(0).periodEndExclusive())
                .isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(report.periods().get(1).period())
                .isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(report.periods().get(1).periodEndExclusive())
                .isEqualTo(LocalDate.of(2026, 8, 5));
    }

    @Test
    void monthlyReportClampsPartialBucketLabelsToRequestedRange() {
        when(queryRepository.moneyFlow(
                any(StatisticsPeriod.class),
                eq(StatisticsGranularity.MONTH)))
                .thenReturn(List.of());

        var report = service.report(
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 8, 10),
                "month");

        assertThat(report.periods()).hasSize(2);
        assertThat(report.periods().get(0).period())
                .isEqualTo(LocalDate.of(2026, 7, 20));
        assertThat(report.periods().get(0).periodEndExclusive())
                .isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(report.periods().get(1).period())
                .isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(report.periods().get(1).periodEndExclusive())
                .isEqualTo(LocalDate.of(2026, 8, 11));
    }

    @Test
    void exactWindowRejectsEmptyOrReversedRange() {
        Instant instant = Instant.parse("2026-07-30T01:00:00Z");

        assertThatThrownBy(() -> service.summarizeWindow(instant, instant))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.summarizeWindow(
                instant, instant.minusSeconds(1)))
                .isInstanceOf(AppException.class);
    }

    @Test
    void reservationMoneyNormalizesPaginationAndUsesMoneyMovementPeriod() {
        var expected = new MoneyReportResponse.ReservationMoneyPage(
                List.of(), 0, 100, 0, 0);
        when(queryRepository.reservationMoney(
                any(StatisticsPeriod.class),
                eq("res-01"),
                eq(0),
                eq(100))).thenReturn(expected);

        var result = service.reservationMoney(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 30),
                "res-01",
                -3,
                500);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void reservationMoneyUsesPeriodReadModelWhenGranularityIsProvided() {
        var expected = new MoneyReportResponse.ReservationMoneyPage(
                List.of(), 0, 100, 0, 0);
        when(queryRepository.reservationMoneyByPeriod(
                any(StatisticsPeriod.class),
                eq(StatisticsGranularity.WEEK),
                eq("res-01"),
                eq(0),
                eq(100))).thenReturn(expected);

        var result = service.reservationMoney(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 30),
                "res-01",
                -3,
                500,
                "week");

        assertThat(result).isSameAs(expected);
    }

    private BigDecimal bd(long value) {
        return BigDecimal.valueOf(value);
    }
}
