package com.hotel.backend.controller;

import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.BusinessStatisticsResponse;
import com.hotel.backend.dto.response.MoneyReportResponse;
import com.hotel.backend.service.BusinessStatisticsCsvService;
import com.hotel.backend.service.BusinessStatisticsService;
import com.hotel.backend.service.MoneyReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin/statistics")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class BusinessStatisticsController {
    private final BusinessStatisticsService statisticsService;
    private final BusinessStatisticsCsvService csvService;
    private final MoneyReportService moneyReportService;

    @GetMapping("/money")
    public ApiResponse<MoneyReportResponse.Report> money(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "day") String granularity) {
        return ApiResponse.success(
                moneyReportService.report(from, to, granularity));
    }

    @GetMapping("/money/reservations")
    public ApiResponse<MoneyReportResponse.ReservationMoneyPage>
    reservationMoney(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(moneyReportService.reservationMoney(
                from, to, query, page, size, granularity));
    }

    @GetMapping("/overview")
    public ApiResponse<BusinessStatisticsResponse.Overview> overview(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(statisticsService.overview(from, to));
    }

    @GetMapping("/revenue")
    public ApiResponse<List<BusinessStatisticsResponse.RevenuePoint>> revenue(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "day") String granularity) {
        return ApiResponse.success(
                statisticsService.revenue(from, to, granularity));
    }

    @GetMapping("/cash-flow")
    public ApiResponse<List<BusinessStatisticsResponse.CashFlowPoint>> cashFlow(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "day") String granularity,
            @RequestParam(required = false) String provider) {
        return ApiResponse.success(
                statisticsService.cashFlow(
                        from, to, granularity, provider));
    }

    @GetMapping("/bookings")
    public ApiResponse<List<BusinessStatisticsResponse.BookingPoint>> bookings(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "day") String granularity) {
        return ApiResponse.success(
                statisticsService.bookings(from, to, granularity));
    }

    @GetMapping("/occupancy")
    public ApiResponse<List<BusinessStatisticsResponse.OccupancyPoint>> occupancy(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "day") String granularity) {
        return ApiResponse.success(
                statisticsService.occupancy(from, to, granularity));
    }

    @GetMapping("/room-types")
    public ApiResponse<List<BusinessStatisticsResponse.RoomTypePerformance>>
    roomTypes(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(statisticsService.roomTypes(from, to));
    }

    @GetMapping("/reservations")
    public ApiResponse<BusinessStatisticsResponse.ReservationRevenuePage>
    reservations(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "day") String granularity,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(statisticsService.reservationRevenue(
                from,
                to,
                granularity,
                query,
                status,
                page,
                size));
    }

    @GetMapping("/ledger")
    public ApiResponse<BusinessStatisticsResponse.LedgerPage> ledger(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.success(statisticsService.ledger(
                from,
                to,
                eventType,
                provider,
                status,
                query,
                page,
                size));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue = "revenue") String report,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "day") String granularity,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "q") String query) {
        BusinessStatisticsCsvService.ExportFile export = csvService.export(
                report,
                from,
                to,
                granularity,
                eventType,
                provider,
                status,
                query);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(export.fileName(), StandardCharsets.UTF_8)
                .build());
        headers.set("X-Export-Truncated", Boolean.toString(export.truncated()));
        return ResponseEntity.ok()
                .headers(headers)
                .body(export.content());
    }
}
