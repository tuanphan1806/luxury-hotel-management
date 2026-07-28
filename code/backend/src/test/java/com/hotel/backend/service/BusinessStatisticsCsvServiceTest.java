package com.hotel.backend.service;

import com.hotel.backend.dto.response.BusinessStatisticsResponse;
import com.hotel.backend.exception.AppException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessStatisticsCsvServiceTest {

    private final BusinessStatisticsService statisticsService =
            mock(BusinessStatisticsService.class);
    private final BusinessStatisticsCsvService csvService =
            new BusinessStatisticsCsvService(statisticsService);

    @Test
    void ledgerExportHasUtf8BomAndNeutralizesSpreadsheetFormula() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 2);
        BusinessStatisticsResponse.LedgerEntry entry =
                new BusinessStatisticsResponse.LedgerEntry(
                        "PAYMENT:1",
                        "CASH_IN",
                        Instant.parse("2026-07-01T01:00:00Z"),
                        LocalDateTime.of(2026, 7, 1, 8, 0),
                        "RES-1",
                        "=HYPERLINK(\"https://invalid.example\")",
                        "SEPAY",
                        "SUCCESS",
                        BigDecimal.valueOf(100_000),
                        "IN",
                        "CANONICAL",
                        "Tiền khách thanh toán");
        when(statisticsService.ledger(
                from, to, null, null, null, null, 0, 100))
                .thenReturn(new BusinessStatisticsResponse.LedgerPage(
                        List.of(entry), 0, 100, 1, 1));

        BusinessStatisticsCsvService.ExportFile export = csvService.export(
                "ledger", from, to, "day", null, null, null);
        String csv = new String(export.content(), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("\uFEFF");
        assertThat(csv).contains("'=HYPERLINK");
        assertThat(export.truncated()).isFalse();
    }

    @Test
    void exportsReservationRevenueWithImmutableInvoiceBreakdown() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        BusinessStatisticsResponse.ReservationRevenueEntry entry =
                new BusinessStatisticsResponse.ReservationRevenueEntry(
                        LocalDate.of(2026, 7, 11),
                        LocalDate.of(2026, 8, 1),
                        1L,
                        "RES-1",
                        "CHECKED_OUT",
                        LocalDateTime.of(2026, 7, 10, 20, 0),
                        LocalDateTime.of(2026, 7, 11, 8, 0),
                        LocalDateTime.of(2026, 7, 10, 21, 0),
                        LocalDateTime.of(2026, 7, 11, 8, 0),
                        "INV-1",
                        Instant.parse("2026-07-11T02:00:00Z"),
                        LocalDateTime.of(2026, 7, 11, 9, 0),
                        "PAID",
                        "MOTEL_PACKAGE_V2",
                        BigDecimal.valueOf(170_000),
                        BigDecimal.ZERO,
                        BigDecimal.valueOf(50_000),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.valueOf(220_000),
                        BigDecimal.valueOf(250_000),
                        BigDecimal.valueOf(220_000),
                        BigDecimal.valueOf(30_000),
                        BigDecimal.valueOf(220_000),
                        "CANONICAL");
        when(statisticsService.reservationRevenue(
                from, to, "month", "RES-1", "CHECKED_OUT", 0, 100))
                .thenReturn(new BusinessStatisticsResponse.ReservationRevenuePage(
                        List.of(entry), 0, 100, 1, 1));

        String csv = new String(csvService.export(
                "reservations",
                from,
                to,
                "month",
                null,
                null,
                "CHECKED_OUT",
                "RES-1").content(), StandardCharsets.UTF_8);

        assertThat(csv)
                .contains("Doanh thu ghi nhận;Tiền thực nhận")
                .contains("RES-1;CHECKED_OUT;INV-1;PAID;MOTEL_PACKAGE_V2")
                .contains("220000;250000;220000;30000;220000;CANONICAL");
    }

    @Test
    void exportsProviderFilteredCashFlowAndRoomTypePerformance() {
        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to = LocalDate.of(2026, 7, 31);
        when(statisticsService.cashFlow(
                from, to, "month", "SEPAY"))
                .thenReturn(List.of(
                        new BusinessStatisticsResponse.CashFlowPoint(
                                from,
                                LocalDate.of(2026, 8, 1),
                                BigDecimal.valueOf(300_000),
                                BigDecimal.valueOf(250_000),
                                BigDecimal.valueOf(50_000),
                                BigDecimal.valueOf(20_000),
                                BigDecimal.valueOf(280_000),
                                BigDecimal.valueOf(30_000),
                                BigDecimal.valueOf(10_000),
                                BigDecimal.valueOf(270_000),
                                2,
                                1,
                                1,
                                1,
                                BigDecimal.valueOf(70_000),
                                1)));
        when(statisticsService.roomTypes(from, to))
                .thenReturn(List.of(
                        new BusinessStatisticsResponse.RoomTypePerformance(
                                1L,
                                "STANDARD",
                                "Phòng tiêu chuẩn",
                                3,
                                4,
                                BigDecimal.valueOf(24),
                                BigDecimal.valueOf(48),
                                BigDecimal.valueOf(50),
                                BigDecimal.valueOf(200_000),
                                BigDecimal.valueOf(50_000),
                                BigDecimal.valueOf(250_000),
                                BigDecimal.valueOf(125_000),
                                "ESTIMATED_CURRENT_INVENTORY")));

        String cashCsv = new String(csvService.export(
                "cash-flow", from, to, "month", null, "SEPAY", null)
                .content(), StandardCharsets.UTF_8);
        String roomTypeCsv = new String(csvService.export(
                "room-types", from, to, "month", null, null, null)
                .content(), StandardCharsets.UTF_8);

        assertThat(cashCsv)
                .contains("Tiền nhận chưa được chấp nhận")
                .contains("300000;250000;50000;20000;280000;30000;10000;270000;2;1;1;1");
        assertThat(roomTypeCsv)
                .contains("ADR;RevPAR")
                .contains("STANDARD;Phòng tiêu chuẩn;3;4");
    }

    @Test
    void rejectsUnknownReportType() {
        assertThatThrownBy(() -> csvService.export(
                "unknown", null, null, "day", null, null, null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("report chỉ nhận");
    }
}
