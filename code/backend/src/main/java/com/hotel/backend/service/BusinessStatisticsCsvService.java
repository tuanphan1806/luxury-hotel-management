package com.hotel.backend.service;

import com.hotel.backend.dto.response.BusinessStatisticsResponse;
import com.hotel.backend.dto.response.MoneyReportResponse;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class BusinessStatisticsCsvService {
    private static final int MAX_LEDGER_EXPORT_ROWS = 10_000;
    private static final int MAX_RESERVATION_EXPORT_ROWS = 10_000;

    private final BusinessStatisticsService statisticsService;
    private final MoneyReportService moneyReportService;

    public ExportFile export(
            String requestedReport,
            LocalDate from,
            LocalDate to,
            String granularity,
            String eventType,
            String provider,
            String status) {
        return export(
                requestedReport,
                from,
                to,
                granularity,
                eventType,
                provider,
                status,
                null);
    }

    public ExportFile export(
            String requestedReport,
            LocalDate from,
            LocalDate to,
            String granularity,
            String eventType,
            String provider,
            String status,
            String query) {
        String report = requestedReport == null
                ? "revenue"
                : requestedReport.trim().toLowerCase(Locale.ROOT);
        return switch (report) {
            case "money" -> money(from, to, granularity, query);
            case "revenue" -> revenue(from, to, granularity);
            case "cash-flow" -> cashFlow(
                    from, to, granularity, provider);
            case "bookings" -> bookings(from, to, granularity);
            case "occupancy" -> occupancy(from, to, granularity);
            case "room-types" -> roomTypes(from, to);
            case "reservations" -> reservations(
                    from, to, granularity, query, status);
            case "ledger" -> ledger(
                    from, to, eventType, provider, status, query);
            default -> throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "report chỉ nhận money, revenue, cash-flow, bookings, occupancy, room-types, reservations hoặc ledger");
        };
    }

    private ExportFile money(
            LocalDate from,
            LocalDate to,
            String granularity,
            String query) {
        MoneyReportResponse.Report report =
                moneyReportService.report(from, to, granularity);
        List<String> lines = new ArrayList<>();
        lines.add("Loại dòng;Kỳ hoặc mã đơn;Trạng thái đơn;Phát sinh gần nhất;"
                + "Thu tiền mặt;Thu chuyển khoản;Tổng thu;"
                + "Hoàn tiền mặt;Hoàn chuyển khoản;Tổng hoàn;"
                + "Doanh thu thực nhận;Số giao dịch thu;Số khoản hoàn");
        lines.add(moneyRow(
                "TỔNG CỘNG",
                report.from() + " - " + report.to(),
                null,
                null,
                report.totals()));
        for (MoneyReportResponse.Period period : report.periods()) {
            lines.add(moneyRow(
                    "THEO KỲ",
                    period.period() + " - "
                            + period.periodEndExclusive().minusDays(1),
                    null,
                    null,
                    period.amounts()));
        }

        int page = 0;
        long exported = 0;
        long total;
        do {
            MoneyReportResponse.ReservationMoneyPage result =
                    moneyReportService.reservationMoney(
                            from, to, query, page, 100);
            total = result.totalElements();
            for (MoneyReportResponse.ReservationMoney reservation
                    : result.content()) {
                if (exported >= MAX_RESERVATION_EXPORT_ROWS) break;
                lines.add(moneyRow(
                        "THEO ĐƠN",
                        reservation.reservationCode(),
                        reservation.reservationStatus(),
                        reservation.lastMovementAtUtc(),
                        reservation.amounts()));
                exported++;
            }
            page++;
        } while (exported < total
                && exported < MAX_RESERVATION_EXPORT_ROWS);

        return file(
                "bao-cao-thu-chi",
                report.from(),
                report.to(),
                lines,
                exported < total);
    }

    private String moneyRow(
            String rowType,
            Object periodOrReservation,
            String reservationStatus,
            Object lastMovement,
            MoneyReportResponse.Breakdown amounts) {
        return row(
                rowType,
                periodOrReservation,
                reservationStatus,
                lastMovement,
                amounts.cashIncome(),
                amounts.transferIncome(),
                amounts.totalIncome(),
                amounts.cashRefund(),
                amounts.transferRefund(),
                amounts.totalRefund(),
                amounts.netRevenue(),
                amounts.paymentCount(),
                amounts.refundCount());
    }

    private ExportFile revenue(
            LocalDate from,
            LocalDate to,
            String granularity) {
        List<String> lines = new ArrayList<>();
        lines.add("Kỳ;Doanh thu ghi nhận;Doanh thu phòng;Dịch vụ thêm;Phụ phí;Phí trả muộn;Doanh thu khác;Giảm giá;Thuế;Số hóa đơn;Tiền thực nhận;Tiền được chấp nhận;Tiền hoàn;Dòng tiền thuần;Tiền vào chưa ghép payment;Số event tiền vào chưa ghép;Tiền legacy chưa đối soát");
        for (BusinessStatisticsResponse.RevenuePoint point
                : statisticsService.revenue(from, to, granularity)) {
            lines.add(row(
                    point.period(),
                    point.recognizedRevenue(),
                    point.roomRevenue(),
                    point.addOnServiceRevenue(),
                    point.additionalFee(),
                    point.lateCheckoutFee(),
                    point.otherRevenue(),
                    point.discountAmount(),
                    point.taxAmount(),
                    point.invoiceCount(),
                    point.grossCashInflow(),
                    point.acceptedCashInflow(),
                    point.refundOutflow(),
                    point.netCashFlow(),
                    point.unmatchedCashInflow(),
                    point.unmatchedCashInEventCount(),
                    point.legacyUnreconciledPaymentAmount()));
        }
        return file("revenue", from, to, lines, false);
    }

    private ExportFile cashFlow(
            LocalDate from,
            LocalDate to,
            String granularity,
            String provider) {
        List<String> lines = new ArrayList<>();
        lines.add("Kỳ;Tiền thực nhận;Tiền được chấp nhận;Tiền nhận chưa được chấp nhận;Tiền hoàn;Dòng tiền thuần;Tiền vào chưa ghép payment;Tiền ra chưa ghép refund;Dòng tiền ngân hàng sau khoản chưa phân loại;Số giao dịch thu;Số event tiền vào chưa ghép;Số giao dịch hoàn;Số event tiền ra chưa ghép;Tiền legacy chưa đối soát;Số giao dịch legacy");
        for (BusinessStatisticsResponse.CashFlowPoint point
                : statisticsService.cashFlow(
                        from, to, granularity, provider)) {
            lines.add(row(
                    point.period(),
                    point.grossCashInflow(),
                    point.acceptedPaymentAmount(),
                    point.unacceptedReceivedAmount(),
                    point.refundOutflow(),
                    point.netCashFlow(),
                    point.unmatchedCashInflow(),
                    point.unclassifiedCashOutflow(),
                    point.netBankMovement(),
                    point.paymentCount(),
                    point.unmatchedCashInEventCount(),
                    point.refundCount(),
                    point.unclassifiedCashOutEventCount(),
                    point.legacyUnreconciledPaymentAmount(),
                    point.legacyUnreconciledPaymentCount()));
        }
        return file("cash-flow", from, to, lines, false);
    }

    private ExportFile bookings(
            LocalDate from,
            LocalDate to,
            String granularity) {
        List<String> lines = new ArrayList<>();
        lines.add("Kỳ;Tổng đơn;Chờ thanh toán;Chờ xác nhận;Đã xác nhận;Chờ hủy;Đã hủy;Đang ở;Đã trả phòng;Không đến");
        for (BusinessStatisticsResponse.BookingPoint point
                : statisticsService.bookings(from, to, granularity)) {
            lines.add(row(
                    point.period(),
                    point.total(),
                    point.paymentPending(),
                    point.draft(),
                    point.confirmed(),
                    point.cancellationPending(),
                    point.cancelled(),
                    point.checkedIn(),
                    point.checkedOut(),
                    point.noShow()));
        }
        return file("bookings", from, to, lines, false);
    }

    private ExportFile occupancy(
            LocalDate from,
            LocalDate to,
            String granularity) {
        List<String> lines = new ArrayList<>();
        lines.add("Kỳ;Giờ phòng đã bán;Giờ phòng khả dụng;Đêm phòng quy đổi;Công suất %;Doanh thu phòng phân bổ;ADR;RevPAR;Chất lượng dữ liệu");
        for (BusinessStatisticsResponse.OccupancyPoint point
                : statisticsService.occupancy(from, to, granularity)) {
            lines.add(row(
                    point.period(),
                    point.soldRoomHours(),
                    point.availableRoomHours(),
                    point.roomNightEquivalents(),
                    point.occupancyRate(),
                    point.allocatedRoomRevenue(),
                    point.adr(),
                    point.revPar(),
                    point.dataQuality()));
        }
        return file("occupancy", from, to, lines, false);
    }

    private ExportFile roomTypes(LocalDate from, LocalDate to) {
        List<String> lines = new ArrayList<>();
        lines.add("Mã loại phòng;Tên loại phòng;Số đơn;Lượt phòng;Giờ phòng đã bán;Giờ phòng khả dụng;Công suất %;Doanh thu phòng;Phụ thu khách;ADR;RevPAR;Chất lượng dữ liệu");
        for (BusinessStatisticsResponse.RoomTypePerformance roomType
                : statisticsService.roomTypes(from, to)) {
            lines.add(row(
                    roomType.roomTypeCode(),
                    roomType.roomTypeName(),
                    roomType.bookingCount(),
                    roomType.reservedRoomQuantity(),
                    roomType.soldRoomHours(),
                    roomType.availableRoomHours(),
                    roomType.occupancyRate(),
                    roomType.recognizedRoomRevenue(),
                    roomType.extraGuestRevenue(),
                    roomType.adr(),
                    roomType.revPar(),
                    roomType.dataQuality()));
        }
        return file("room-types", from, to, lines, false);
    }

    private ExportFile reservations(
            LocalDate from,
            LocalDate to,
            String granularity,
            String query,
            String status) {
        List<String> lines = new ArrayList<>();
        lines.add("Kỳ;Thời gian xuất hóa đơn;Mã đơn;Trạng thái đơn;Mã hóa đơn;Trạng thái quyết toán;Phiên bản giá;Nhận dự kiến;Trả dự kiến;Nhận thực tế;Trả thực tế;Tiền phòng;Phụ thu khách;Dịch vụ thêm;Phụ phí;Phí trả muộn;Doanh thu khác;Giảm giá;Thuế;Doanh thu ghi nhận;Tiền thực nhận;Tiền được chấp nhận;Tiền hoàn;Dòng tiền ròng;Chất lượng dữ liệu");
        int page = 0;
        long exported = 0;
        long total;
        do {
            BusinessStatisticsResponse.ReservationRevenuePage result =
                    statisticsService.reservationRevenue(
                            from,
                            to,
                            granularity,
                            query,
                            status,
                            page,
                            100);
            total = result.totalElements();
            for (BusinessStatisticsResponse.ReservationRevenueEntry entry
                    : result.content()) {
                if (exported >= MAX_RESERVATION_EXPORT_ROWS) break;
                lines.add(row(
                        entry.period(),
                        entry.issuedAtLocal(),
                        entry.reservationCode(),
                        entry.reservationStatus(),
                        entry.invoiceNumber(),
                        entry.settlementStatus(),
                        entry.pricingVersion(),
                        entry.plannedCheckIn(),
                        entry.plannedCheckOut(),
                        entry.actualCheckIn(),
                        entry.actualCheckOut(),
                        entry.roomCharge(),
                        entry.extraGuestCharge(),
                        entry.addOnServiceAmount(),
                        entry.additionalFee(),
                        entry.lateCheckoutFee(),
                        entry.otherRevenue(),
                        entry.discountAmount(),
                        entry.taxAmount(),
                        entry.recognizedRevenue(),
                        entry.grossCashInflow(),
                        entry.acceptedCashInflow(),
                        entry.refundOutflow(),
                        entry.netCashFlow(),
                        entry.dataQuality()));
                exported++;
            }
            page++;
        } while (exported < total
                && exported < MAX_RESERVATION_EXPORT_ROWS);
        return file("reservations", from, to, lines, exported < total);
    }

    private ExportFile ledger(
            LocalDate from,
            LocalDate to,
            String eventType,
            String provider,
            String status,
            String query) {
        List<String> lines = new ArrayList<>();
        lines.add("Thời gian;Loại sự kiện;Mã đơn;Tham chiếu;Kênh;Trạng thái;Chiều;Số tiền;Chất lượng dữ liệu;Diễn giải");
        int page = 0;
        long exported = 0;
        long total;
        do {
            BusinessStatisticsResponse.LedgerPage result =
                    statisticsService.ledger(
                            from,
                            to,
                            eventType,
                            provider,
                            status,
                            query,
                            page,
                            100);
            total = result.totalElements();
            for (BusinessStatisticsResponse.LedgerEntry entry
                    : result.content()) {
                if (exported >= MAX_LEDGER_EXPORT_ROWS) break;
                lines.add(row(
                        entry.occurredAtLocal(),
                        entry.eventType(),
                        entry.reservationCode(),
                        entry.reference(),
                        entry.provider(),
                        entry.status(),
                        entry.direction(),
                        entry.amount(),
                        entry.dataQuality(),
                        entry.description()));
                exported++;
            }
            page++;
        } while (exported < total && exported < MAX_LEDGER_EXPORT_ROWS);
        return file("ledger", from, to, lines, exported < total);
    }

    private ExportFile file(
            String report,
            LocalDate from,
            LocalDate to,
            List<String> lines,
            boolean truncated) {
        String csv = "\uFEFF" + String.join("\r\n", lines) + "\r\n";
        String suffix = (from != null ? from : "default")
                + "_" + (to != null ? to : "today");
        return new ExportFile(
                ("luxury-hotel-" + report + "-" + suffix + ".csv")
                        .replace(' ', '-'),
                csv.getBytes(StandardCharsets.UTF_8),
                truncated);
    }

    private String row(Object... values) {
        List<String> cells = new ArrayList<>(values.length);
        for (Object value : values) cells.add(cell(value));
        return String.join(";", cells);
    }

    private String cell(Object value) {
        if (value == null) return "";
        String text = value instanceof BigDecimal decimal
                ? decimal.stripTrailingZeros().toPlainString()
                : String.valueOf(value);
        String trimmed = text.stripLeading();
        if (!(value instanceof Number)
                && !trimmed.isEmpty()
                && "=+-@".indexOf(trimmed.charAt(0)) >= 0) {
            // Prevent spreadsheet formula execution from provider/user text.
            text = "'" + text;
        }
        if (text.contains(";") || text.contains("\"")
                || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    public record ExportFile(
            String fileName,
            byte[] content,
            boolean truncated) {
    }
}
