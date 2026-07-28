package com.hotel.backend.service;

import com.hotel.backend.dto.response.BusinessStatisticsResponse;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessStatisticsServiceTest {
    @Mock BusinessStatisticsQueryRepository queryRepository;

    private BusinessStatisticsService service;

    @BeforeEach
    void setUp() {
        service = new BusinessStatisticsService(
                queryRepository,
                Clock.fixed(
                        Instant.parse("2026-07-28T03:00:00Z"),
                        ZoneOffset.UTC));
    }

    @Test
    void revenueFillsMissingPeriodsAndKeepsLegacyMoneySeparate() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 3);
        when(queryRepository.revenue(
                any(StatisticsPeriod.class),
                eq(StatisticsGranularity.DAY)))
                .thenReturn(List.of(new BusinessStatisticsQueryRepository.RevenueRow(
                        LocalDate.of(2026, 7, 2),
                        bd(500_000),
                        bd(400_000),
                        bd(50_000),
                        bd(20_000),
                        bd(15_000),
                        bd(15_000),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        2,
                        bd(300_000),
                        bd(250_000),
                        bd(20_000),
                        bd(70_000),
                        1)));
        when(queryRepository.cashFlow(
                any(StatisticsPeriod.class),
                eq(StatisticsGranularity.DAY),
                isNull()))
                .thenReturn(List.of(cashFlowRow(
                        LocalDate.of(2026, 7, 2),
                        300_000, 250_000, 30_000, 20_000,
                        0, 2, 1, 1, 0, 70_000, 1)));

        List<BusinessStatisticsResponse.RevenuePoint> points =
                service.revenue(from, to, "day");

        assertThat(points).hasSize(3);
        assertThat(points.get(0).recognizedRevenue()).isZero();
        assertThat(points.get(1).netCashFlow()).isEqualByComparingTo("280000");
        assertThat(points.get(1).grossCashInflow()).isEqualByComparingTo("300000");
        assertThat(points.get(1).invoiceCount()).isEqualTo(2);
        assertThat(points.get(1).additionalFee()).isEqualByComparingTo("15000");
        assertThat(points.get(1).legacyUnreconciledPaymentAmount())
                .isEqualByComparingTo("70000");
    }

    @Test
    void cashFlowCanFilterProviderAndKeepsUnacceptedMoneyVisible() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 3);
        when(queryRepository.cashFlow(
                any(StatisticsPeriod.class),
                eq(StatisticsGranularity.DAY),
                eq("SEPAY")))
                .thenReturn(List.of(
                        new BusinessStatisticsQueryRepository.CashFlowRow(
                                LocalDate.of(2026, 7, 2),
                                bd(300_000),
                                bd(250_000),
                                bd(30_000),
                                bd(20_000),
                                bd(10_000),
                                2,
                                1,
                                1,
                                1,
                                bd(70_000),
                                1)));

        List<BusinessStatisticsResponse.CashFlowPoint> points =
                service.cashFlow(from, to, "day", "SEPAY");

        assertThat(points).hasSize(3);
        assertThat(points.get(0).netCashFlow()).isZero();
        assertThat(points.get(1).unacceptedReceivedAmount())
                .isEqualByComparingTo("50000");
        assertThat(points.get(1).netCashFlow())
                .isEqualByComparingTo("280000");
        assertThat(points.get(1).paymentCount()).isEqualTo(2);
        assertThat(points.get(1).refundCount()).isEqualTo(1);
        assertThat(points.get(1).unmatchedCashInEventCount()).isEqualTo(1);
        assertThat(points.get(1).netBankMovement())
                .isEqualByComparingTo("270000");
    }

    @Test
    void overviewComparesEqualPeriodsAndDoesNotHideLegacyPaymentsInGross() {
        when(queryRepository.revenue(
                any(StatisticsPeriod.class),
                eq(StatisticsGranularity.DAY)))
                .thenReturn(
                        List.of(revenueRow(100_000, 80_000, 10_000, 20_000, 1)),
                        List.of(revenueRow(50_000, 40_000, 5_000, 0, 0)));
        when(queryRepository.cashFlow(
                any(StatisticsPeriod.class),
                eq(StatisticsGranularity.DAY),
                isNull()))
                .thenReturn(
                        List.of(cashFlowRow(
                                LocalDate.of(2026, 7, 1),
                                80_000, 70_000, 10_000, 10_000,
                                0, 1, 1, 1, 0, 20_000, 1)),
                        List.of(cashFlowRow(
                                LocalDate.of(2026, 6, 30),
                                40_000, 40_000, 0, 5_000,
                                0, 1, 0, 1, 0, 0, 0)));
        when(queryRepository.bookings(
                any(StatisticsPeriod.class),
                eq(StatisticsGranularity.DAY)))
                .thenReturn(
                        List.of(bookingRow(4)),
                        List.of(bookingRow(2)));
        when(queryRepository.dailyOccupancy(any(StatisticsPeriod.class)))
                .thenReturn(
                        List.of(new BusinessStatisticsQueryRepository.DailyOccupancyRow(
                                LocalDate.of(2026, 7, 1),
                                bd(24), bd(48), bd(100_000))),
                        List.of(new BusinessStatisticsQueryRepository.DailyOccupancyRow(
                                LocalDate.of(2026, 6, 30),
                                bd(12), bd(48), bd(50_000))));
        when(queryRepository.currentBalances()).thenReturn(
                new BusinessStatisticsQueryRepository.CurrentBalances(
                        bd(30_000), bd(80_000), bd(10_000)));

        BusinessStatisticsResponse.Overview overview = service.overview(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 1));

        assertThat(overview.recognizedRevenue().changePercent())
                .isEqualByComparingTo("100");
        assertThat(overview.bookings().changePercent())
                .isEqualByComparingTo("100");
        assertThat(overview.grossCashInflow()).isEqualByComparingTo("80000");
        assertThat(overview.netCashFlow()).isEqualByComparingTo("70000");
        assertThat(overview.dataQuality().paymentCompleteness()).isEqualTo("PARTIAL");
        assertThat(overview.dataQuality().legacyUnreconciledPaymentAmount())
                .isEqualByComparingTo("20000");
        assertThat(overview.dataQuality().unmatchedCashInEventCount())
                .isEqualTo(1);
        assertThat(overview.customerDeposits()).isEqualByComparingTo("80000");
    }

    @Test
    void occupancyUsesHoursSoShortStaysDoNotConsumeAWholeRoomNight() {
        when(queryRepository.dailyOccupancy(any(StatisticsPeriod.class)))
                .thenReturn(List.of(
                        new BusinessStatisticsQueryRepository.DailyOccupancyRow(
                                LocalDate.of(2026, 7, 27),
                                bd(2), bd(24), bd(70_000)),
                        new BusinessStatisticsQueryRepository.DailyOccupancyRow(
                                LocalDate.of(2026, 7, 28),
                                bd(10), bd(24), bd(170_000))));

        List<BusinessStatisticsResponse.OccupancyPoint> points =
                service.occupancy(
                        LocalDate.of(2026, 7, 27),
                        LocalDate.of(2026, 7, 28),
                        "week");

        assertThat(points).hasSize(1);
        BusinessStatisticsResponse.OccupancyPoint point = points.get(0);
        assertThat(point.soldRoomHours()).isEqualByComparingTo("12.00");
        assertThat(point.roomNightEquivalents()).isEqualByComparingTo("0.5000");
        assertThat(point.occupancyRate()).isEqualByComparingTo("25.00");
        assertThat(point.adr()).isEqualByComparingTo("480000.00");
        assertThat(point.revPar()).isEqualByComparingTo("120000.00");
    }

    @Test
    void reservationRevenueUsesRequestedPeriodGranularityAndFilters() {
        BusinessStatisticsResponse.ReservationRevenuePage expected =
                new BusinessStatisticsResponse.ReservationRevenuePage(
                        List.of(), 0, 20, 0, 0);
        when(queryRepository.reservationRevenue(
                any(StatisticsPeriod.class),
                eq(StatisticsGranularity.MONTH),
                eq("RES-01"),
                eq("CHECKED_OUT"),
                eq(0),
                eq(20))).thenReturn(expected);

        BusinessStatisticsResponse.ReservationRevenuePage result =
                service.reservationRevenue(
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 31),
                        "month",
                        "RES-01",
                        "CHECKED_OUT",
                        0,
                        20);

        assertThat(result).isSameAs(expected);
    }

    private BusinessStatisticsQueryRepository.RevenueRow revenueRow(
            long recognized,
            long gross,
            long refund,
            long legacy,
            long legacyCount) {
        return new BusinessStatisticsQueryRepository.RevenueRow(
                LocalDate.of(2026, 7, 1),
                bd(recognized),
                bd(recognized),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                1,
                bd(gross),
                bd(gross),
                bd(refund),
                bd(legacy),
                legacyCount);
    }

    private BusinessStatisticsQueryRepository.BookingRow bookingRow(long total) {
        return new BusinessStatisticsQueryRepository.BookingRow(
                LocalDate.of(2026, 7, 1),
                total, 0, total, 0, 0, 0, 0, 0, 0);
    }

    private BusinessStatisticsQueryRepository.CashFlowRow cashFlowRow(
            LocalDate period,
            long gross,
            long accepted,
            long unmatchedIn,
            long refund,
            long unclassifiedOut,
            long paymentCount,
            long unmatchedInCount,
            long refundCount,
            long unclassifiedOutCount,
            long legacy,
            long legacyCount) {
        return new BusinessStatisticsQueryRepository.CashFlowRow(
                period,
                bd(gross),
                bd(accepted),
                bd(unmatchedIn),
                bd(refund),
                bd(unclassifiedOut),
                paymentCount,
                unmatchedInCount,
                refundCount,
                unclassifiedOutCount,
                bd(legacy),
                legacyCount);
    }

    private BigDecimal bd(long value) {
        return BigDecimal.valueOf(value);
    }
}
