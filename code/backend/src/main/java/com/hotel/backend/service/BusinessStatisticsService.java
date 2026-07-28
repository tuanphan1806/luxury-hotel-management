package com.hotel.backend.service;

import com.hotel.backend.dto.response.BusinessStatisticsResponse;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.statistics.BusinessStatisticsQueryRepository;
import com.hotel.backend.statistics.StatisticsGranularity;
import com.hotel.backend.statistics.StatisticsPeriod;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BusinessStatisticsService {
    private static final BigDecimal HOURS_PER_DAY = BigDecimal.valueOf(24);
    private static final String OCCUPANCY_QUALITY = "ESTIMATED_INVENTORY_HISTORY";

    private final BusinessStatisticsQueryRepository queryRepository;
    private final Clock clock;

    @Autowired
    public BusinessStatisticsService(
            BusinessStatisticsQueryRepository queryRepository) {
        this(queryRepository, Clock.systemUTC());
    }

    BusinessStatisticsService(
            BusinessStatisticsQueryRepository queryRepository,
            Clock clock) {
        this.queryRepository = queryRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BusinessStatisticsResponse.Overview overview(
            LocalDate requestedFrom,
            LocalDate requestedTo) {
        StatisticsPeriod period = period(requestedFrom, requestedTo);
        PeriodTotals current = totals(period);
        PeriodTotals previous = totals(period.previous());
        BusinessStatisticsQueryRepository.CurrentBalances balances =
                queryRepository.currentBalances();
        List<String> warnings = new ArrayList<>();
        if (current.legacyUnreconciledPaymentCount() > 0) {
            warnings.add("Có giao dịch cũ chưa có receivedAmount; số này được tách riêng, không cộng ngầm vào tiền thực nhận.");
        }
        if (current.occupancyRate().compareTo(BigDecimal.valueOf(100)) > 0) {
            warnings.add("Công suất vượt 100%; cần kiểm tra dữ liệu gán phòng, thời gian lưu trú hoặc tồn kho phòng trong kỳ.");
        }
        if (current.unmatchedCashInEventCount() > 0) {
            warnings.add("Có tiền vào SePay chưa ghép payment; khoản tiền vẫn được tính vào tiền thực nhận và tách riêng để ADMIN đối soát.");
        }
        if (current.unclassifiedCashOutEventCount() > 0) {
            warnings.add("Có tiền ra ngân hàng chưa ghép refund hoàn tất; khoản này được tách riêng và chưa được gọi là tiền hoàn.");
        }
        warnings.add("Công suất lịch sử dùng tồn kho phòng hiện có và mốc ngừng bán; dữ liệu bảo trì quá khứ chưa có snapshot nên được gắn nhãn ước tính.");
        return new BusinessStatisticsResponse.Overview(
                range(period),
                kpi(current.recognizedRevenue(), previous.recognizedRevenue()),
                kpi(BigDecimal.valueOf(current.bookings()),
                        BigDecimal.valueOf(previous.bookings())),
                kpi(current.occupancyRate(), previous.occupancyRate()),
                kpi(current.adr(), previous.adr()),
                kpi(current.revPar(), previous.revPar()),
                current.grossCashInflow(),
                current.acceptedCashInflow(),
                current.refundOutflow(),
                current.grossCashInflow().subtract(current.refundOutflow()),
                balances.outstandingReceivables(),
                balances.customerDeposits(),
                balances.refundPayable(),
                new BusinessStatisticsResponse.DataQuality(
                        current.legacyUnreconciledPaymentCount() == 0
                                && current.unmatchedCashInEventCount() == 0
                                && current.unclassifiedCashOutEventCount() == 0
                                ? "CANONICAL" : "PARTIAL",
                        OCCUPANCY_QUALITY,
                        current.legacyUnreconciledPaymentCount(),
                        current.legacyUnreconciledPaymentAmount(),
                        current.unmatchedCashInEventCount(),
                        current.unmatchedCashInAmount(),
                        current.unclassifiedCashOutEventCount(),
                        current.unclassifiedCashOutAmount(),
                        List.copyOf(warnings)),
                Instant.now(clock));
    }

    @Transactional(readOnly = true)
    public List<BusinessStatisticsResponse.RevenuePoint> revenue(
            LocalDate requestedFrom,
            LocalDate requestedTo,
            String requestedGranularity) {
        StatisticsPeriod period = period(requestedFrom, requestedTo);
        StatisticsGranularity granularity =
                StatisticsGranularity.parse(requestedGranularity);
        Map<LocalDate, BusinessStatisticsQueryRepository.RevenueRow> rows =
                queryRepository.revenue(period, granularity).stream()
                        .collect(Collectors.toMap(
                                BusinessStatisticsQueryRepository.RevenueRow::period,
                                Function.identity()));
        Map<LocalDate, BusinessStatisticsQueryRepository.CashFlowRow> cashRows =
                queryRepository.cashFlow(period, granularity, null).stream()
                        .collect(Collectors.toMap(
                                BusinessStatisticsQueryRepository.CashFlowRow::period,
                                Function.identity()));
        List<BusinessStatisticsResponse.RevenuePoint> points = new ArrayList<>();
        for (LocalDate bucket : buckets(period, granularity)) {
            BusinessStatisticsQueryRepository.RevenueRow row = rows.get(bucket);
            BusinessStatisticsQueryRepository.CashFlowRow cashRow =
                    cashRows.get(bucket);
            BigDecimal gross = cashRow != null
                    ? cashRow.grossCashInflow() : BigDecimal.ZERO;
            BigDecimal refund = cashRow != null
                    ? cashRow.refundOutflow() : BigDecimal.ZERO;
            points.add(new BusinessStatisticsResponse.RevenuePoint(
                    bucket,
                    granularity.nextBucket(bucket),
                    row != null ? row.recognizedRevenue() : BigDecimal.ZERO,
                    row != null ? row.roomRevenue() : BigDecimal.ZERO,
                    row != null ? row.addOnRevenue() : BigDecimal.ZERO,
                    row != null ? row.otherRevenue() : BigDecimal.ZERO,
                    row != null ? row.additionalFee() : BigDecimal.ZERO,
                    row != null ? row.lateCheckoutFee() : BigDecimal.ZERO,
                    row != null ? row.discountAmount() : BigDecimal.ZERO,
                    row != null ? row.taxAmount() : BigDecimal.ZERO,
                    row != null ? row.invoiceCount() : 0L,
                    gross,
                    cashRow != null
                            ? cashRow.acceptedCashInflow() : BigDecimal.ZERO,
                    refund,
                    gross.subtract(refund),
                    cashRow != null
                            ? cashRow.unmatchedCashInflow() : BigDecimal.ZERO,
                    cashRow != null ? cashRow.unmatchedCashInCount() : 0L,
                    cashRow != null
                            ? cashRow.legacyUnreconciledAmount() : BigDecimal.ZERO,
                    cashRow != null ? cashRow.legacyUnreconciledCount() : 0L));
        }
        return List.copyOf(points);
    }

    @Transactional(readOnly = true)
    public List<BusinessStatisticsResponse.CashFlowPoint> cashFlow(
            LocalDate requestedFrom,
            LocalDate requestedTo,
            String requestedGranularity,
            String provider) {
        StatisticsPeriod period = period(requestedFrom, requestedTo);
        StatisticsGranularity granularity =
                StatisticsGranularity.parse(requestedGranularity);
        Map<LocalDate, BusinessStatisticsQueryRepository.CashFlowRow> rows =
                queryRepository.cashFlow(period, granularity, provider).stream()
                        .collect(Collectors.toMap(
                                BusinessStatisticsQueryRepository.CashFlowRow::period,
                                Function.identity()));
        List<BusinessStatisticsResponse.CashFlowPoint> points =
                new ArrayList<>();
        for (LocalDate bucket : buckets(period, granularity)) {
            BusinessStatisticsQueryRepository.CashFlowRow row = rows.get(bucket);
            BigDecimal gross = row != null
                    ? row.grossCashInflow() : BigDecimal.ZERO;
            BigDecimal accepted = row != null
                    ? row.acceptedCashInflow() : BigDecimal.ZERO;
            BigDecimal refund = row != null
                    ? row.refundOutflow() : BigDecimal.ZERO;
            BigDecimal unclassifiedOut = row != null
                    ? row.unclassifiedCashOutflow() : BigDecimal.ZERO;
            points.add(new BusinessStatisticsResponse.CashFlowPoint(
                    bucket,
                    granularity.nextBucket(bucket),
                    gross,
                    accepted,
                    gross.subtract(accepted),
                    refund,
                    gross.subtract(refund),
                    row != null ? row.unmatchedCashInflow() : BigDecimal.ZERO,
                    unclassifiedOut,
                    gross.subtract(refund).subtract(unclassifiedOut),
                    row != null ? row.paymentCount() : 0L,
                    row != null ? row.unmatchedCashInCount() : 0L,
                    row != null ? row.refundCount() : 0L,
                    row != null ? row.unclassifiedCashOutCount() : 0L,
                    row != null
                            ? row.legacyUnreconciledAmount() : BigDecimal.ZERO,
                    row != null ? row.legacyUnreconciledCount() : 0L));
        }
        return List.copyOf(points);
    }

    @Transactional(readOnly = true)
    public List<BusinessStatisticsResponse.BookingPoint> bookings(
            LocalDate requestedFrom,
            LocalDate requestedTo,
            String requestedGranularity) {
        StatisticsPeriod period = period(requestedFrom, requestedTo);
        StatisticsGranularity granularity =
                StatisticsGranularity.parse(requestedGranularity);
        Map<LocalDate, BusinessStatisticsQueryRepository.BookingRow> rows =
                queryRepository.bookings(period, granularity).stream()
                        .collect(Collectors.toMap(
                                BusinessStatisticsQueryRepository.BookingRow::period,
                                Function.identity()));
        List<BusinessStatisticsResponse.BookingPoint> points = new ArrayList<>();
        for (LocalDate bucket : buckets(period, granularity)) {
            BusinessStatisticsQueryRepository.BookingRow row = rows.get(bucket);
            points.add(new BusinessStatisticsResponse.BookingPoint(
                    bucket,
                    granularity.nextBucket(bucket),
                    row != null ? row.total() : 0,
                    row != null ? row.paymentPending() : 0,
                    row != null ? row.draft() : 0,
                    row != null ? row.confirmed() : 0,
                    row != null ? row.cancellationPending() : 0,
                    row != null ? row.cancelled() : 0,
                    row != null ? row.checkedIn() : 0,
                    row != null ? row.checkedOut() : 0,
                    row != null ? row.noShow() : 0));
        }
        return List.copyOf(points);
    }

    @Transactional(readOnly = true)
    public List<BusinessStatisticsResponse.OccupancyPoint> occupancy(
            LocalDate requestedFrom,
            LocalDate requestedTo,
            String requestedGranularity) {
        StatisticsPeriod period = period(requestedFrom, requestedTo);
        StatisticsGranularity granularity =
                StatisticsGranularity.parse(requestedGranularity);
        Map<LocalDate, OccupancyAccumulator> grouped = new LinkedHashMap<>();
        for (LocalDate bucket : buckets(period, granularity)) {
            grouped.put(bucket, new OccupancyAccumulator());
        }
        for (BusinessStatisticsQueryRepository.DailyOccupancyRow row
                : queryRepository.dailyOccupancy(period)) {
            LocalDate bucket = granularity.bucketStart(row.day());
            grouped.computeIfAbsent(bucket, ignored -> new OccupancyAccumulator())
                    .add(row);
        }
        return grouped.entrySet().stream()
                .map(entry -> occupancyPoint(
                        entry.getKey(), granularity, entry.getValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BusinessStatisticsResponse.RoomTypePerformance> roomTypes(
            LocalDate requestedFrom,
            LocalDate requestedTo) {
        StatisticsPeriod period = period(requestedFrom, requestedTo);
        return queryRepository.roomTypePerformance(period).stream()
                .map(row -> {
                    BigDecimal soldNights = divide(row.soldHours(), HOURS_PER_DAY, 4);
                    BigDecimal occupancyRate = percent(
                            row.soldHours(), row.availableHours());
                    BigDecimal adr = divide(
                            row.recognizedRoomRevenue()
                                    .add(row.extraGuestRevenue()),
                            soldNights,
                            2);
                    BigDecimal availableNights = divide(
                            row.availableHours(), HOURS_PER_DAY, 4);
                    BigDecimal revPar = divide(
                            row.recognizedRoomRevenue()
                                    .add(row.extraGuestRevenue()),
                            availableNights,
                            2);
                    return new BusinessStatisticsResponse.RoomTypePerformance(
                            row.roomTypeId(),
                            row.roomTypeCode(),
                            row.roomTypeName(),
                            row.bookingCount(),
                            row.reservedQuantity(),
                            scale(row.soldHours(), 2),
                            scale(row.availableHours(), 2),
                            occupancyRate,
                            row.recognizedRoomRevenue(),
                            row.extraGuestRevenue(),
                            adr,
                            revPar,
                            OCCUPANCY_QUALITY);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public BusinessStatisticsResponse.ReservationRevenuePage reservationRevenue(
            LocalDate requestedFrom,
            LocalDate requestedTo,
            String requestedGranularity,
            String query,
            String reservationStatus,
            int page,
            int size) {
        validatePage(page, size);
        validateSearch(query);
        return queryRepository.reservationRevenue(
                period(requestedFrom, requestedTo),
                StatisticsGranularity.parse(requestedGranularity),
                query,
                reservationStatus,
                page,
                size);
    }

    @Transactional(readOnly = true)
    public BusinessStatisticsResponse.LedgerPage ledger(
            LocalDate requestedFrom,
            LocalDate requestedTo,
            String eventType,
            String provider,
            String status,
            int page,
            int size) {
        return ledger(
                requestedFrom,
                requestedTo,
                eventType,
                provider,
                status,
                null,
                page,
                size);
    }

    @Transactional(readOnly = true)
    public BusinessStatisticsResponse.LedgerPage ledger(
            LocalDate requestedFrom,
            LocalDate requestedTo,
            String eventType,
            String provider,
            String status,
            String query,
            int page,
            int size) {
        validatePage(page, size);
        validateSearch(query);
        return queryRepository.ledger(
                period(requestedFrom, requestedTo),
                eventType,
                provider,
                status,
                query,
                page,
                size);
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "page không được âm");
        }
        if (size < 1 || size > 100) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "size phải từ 1 đến 100");
        }
    }

    private void validateSearch(String query) {
        if (query != null && query.trim().length() > 100) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "Từ khóa tìm kiếm tối đa 100 ký tự");
        }
    }

    private StatisticsPeriod period(LocalDate from, LocalDate to) {
        return StatisticsPeriod.resolve(from, to, clock);
    }

    private PeriodTotals totals(StatisticsPeriod period) {
        List<BusinessStatisticsQueryRepository.RevenueRow> revenueRows =
                queryRepository.revenue(period, StatisticsGranularity.DAY);
        List<BusinessStatisticsQueryRepository.CashFlowRow> cashFlowRows =
                queryRepository.cashFlow(
                        period, StatisticsGranularity.DAY, null);
        List<BusinessStatisticsQueryRepository.BookingRow> bookingRows =
                queryRepository.bookings(period, StatisticsGranularity.DAY);
        List<BusinessStatisticsQueryRepository.DailyOccupancyRow> occupancyRows =
                queryRepository.dailyOccupancy(period);
        BigDecimal recognizedRevenue = sum(
                revenueRows,
                BusinessStatisticsQueryRepository.RevenueRow::recognizedRevenue);
        BigDecimal gross = sum(
                cashFlowRows,
                BusinessStatisticsQueryRepository.CashFlowRow::grossCashInflow);
        BigDecimal accepted = sum(
                cashFlowRows,
                BusinessStatisticsQueryRepository.CashFlowRow::acceptedCashInflow);
        BigDecimal refund = sum(
                cashFlowRows,
                BusinessStatisticsQueryRepository.CashFlowRow::refundOutflow);
        BigDecimal legacyAmount = sum(
                cashFlowRows,
                BusinessStatisticsQueryRepository.CashFlowRow::legacyUnreconciledAmount);
        long legacyCount = cashFlowRows.stream()
                .mapToLong(BusinessStatisticsQueryRepository.CashFlowRow::legacyUnreconciledCount)
                .sum();
        BigDecimal unmatchedCashIn = sum(
                cashFlowRows,
                BusinessStatisticsQueryRepository.CashFlowRow::unmatchedCashInflow);
        long unmatchedCashInCount = cashFlowRows.stream()
                .mapToLong(BusinessStatisticsQueryRepository.CashFlowRow::unmatchedCashInCount)
                .sum();
        BigDecimal unclassifiedCashOut = sum(
                cashFlowRows,
                BusinessStatisticsQueryRepository.CashFlowRow::unclassifiedCashOutflow);
        long unclassifiedCashOutCount = cashFlowRows.stream()
                .mapToLong(BusinessStatisticsQueryRepository.CashFlowRow::unclassifiedCashOutCount)
                .sum();
        long bookings = bookingRows.stream()
                .mapToLong(BusinessStatisticsQueryRepository.BookingRow::total)
                .sum();
        BigDecimal soldHours = sum(
                occupancyRows,
                BusinessStatisticsQueryRepository.DailyOccupancyRow::soldHours);
        BigDecimal availableHours = sum(
                occupancyRows,
                BusinessStatisticsQueryRepository.DailyOccupancyRow::availableHours);
        BigDecimal allocatedRoomRevenue = sum(
                occupancyRows,
                BusinessStatisticsQueryRepository.DailyOccupancyRow::allocatedRoomRevenue);
        BigDecimal soldNights = divide(soldHours, HOURS_PER_DAY, 4);
        BigDecimal availableNights = divide(availableHours, HOURS_PER_DAY, 4);
        return new PeriodTotals(
                recognizedRevenue,
                gross,
                accepted,
                refund,
                legacyAmount,
                legacyCount,
                unmatchedCashIn,
                unmatchedCashInCount,
                unclassifiedCashOut,
                unclassifiedCashOutCount,
                bookings,
                percent(soldHours, availableHours),
                divide(allocatedRoomRevenue, soldNights, 2),
                divide(allocatedRoomRevenue, availableNights, 2));
    }

    private BusinessStatisticsResponse.OccupancyPoint occupancyPoint(
            LocalDate bucket,
            StatisticsGranularity granularity,
            OccupancyAccumulator accumulator) {
        BigDecimal soldNights = divide(
                accumulator.soldHours, HOURS_PER_DAY, 4);
        BigDecimal availableNights = divide(
                accumulator.availableHours, HOURS_PER_DAY, 4);
        return new BusinessStatisticsResponse.OccupancyPoint(
                bucket,
                granularity.nextBucket(bucket),
                scale(accumulator.soldHours, 2),
                scale(accumulator.availableHours, 2),
                soldNights,
                availableNights,
                percent(accumulator.soldHours, accumulator.availableHours),
                scale(accumulator.allocatedRoomRevenue, 2),
                divide(accumulator.allocatedRoomRevenue, soldNights, 2),
                divide(accumulator.allocatedRoomRevenue, availableNights, 2),
                OCCUPANCY_QUALITY);
    }

    private List<LocalDate> buckets(
            StatisticsPeriod period,
            StatisticsGranularity granularity) {
        List<LocalDate> result = new ArrayList<>();
        LocalDate bucket = granularity.bucketStart(period.from());
        while (!bucket.isAfter(period.to())) {
            result.add(bucket);
            bucket = granularity.nextBucket(bucket);
        }
        return result;
    }

    private BusinessStatisticsResponse.Range range(StatisticsPeriod period) {
        return new BusinessStatisticsResponse.Range(
                period.from(),
                period.to(),
                StatisticsPeriod.HOTEL_ZONE.getId());
    }

    private BusinessStatisticsResponse.Kpi kpi(
            BigDecimal current,
            BigDecimal previous) {
        BigDecimal change = previous.signum() == 0
                ? current.signum() == 0 ? BigDecimal.ZERO : null
                : current.subtract(previous)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(previous.abs(), 2, RoundingMode.HALF_UP);
        return new BusinessStatisticsResponse.Kpi(
                scale(current, 2), scale(previous, 2), change);
    }

    private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal divide(
            BigDecimal numerator,
            BigDecimal denominator,
            int scale) {
        if (denominator == null || denominator.signum() == 0) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }
        return numerator.divide(denominator, scale, RoundingMode.HALF_UP);
    }

    private BigDecimal scale(BigDecimal value, int scale) {
        return (value != null ? value : BigDecimal.ZERO)
                .setScale(scale, RoundingMode.HALF_UP);
    }

    private <T> BigDecimal sum(
            List<T> values,
            Function<T, BigDecimal> mapper) {
        return values.stream()
                .map(mapper)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static final class OccupancyAccumulator {
        private BigDecimal soldHours = BigDecimal.ZERO;
        private BigDecimal availableHours = BigDecimal.ZERO;
        private BigDecimal allocatedRoomRevenue = BigDecimal.ZERO;

        private void add(
                BusinessStatisticsQueryRepository.DailyOccupancyRow row) {
            soldHours = soldHours.add(row.soldHours());
            availableHours = availableHours.add(row.availableHours());
            allocatedRoomRevenue = allocatedRoomRevenue
                    .add(row.allocatedRoomRevenue());
        }
    }

    private record PeriodTotals(
            BigDecimal recognizedRevenue,
            BigDecimal grossCashInflow,
            BigDecimal acceptedCashInflow,
            BigDecimal refundOutflow,
            BigDecimal legacyUnreconciledPaymentAmount,
            long legacyUnreconciledPaymentCount,
            BigDecimal unmatchedCashInAmount,
            long unmatchedCashInEventCount,
            BigDecimal unclassifiedCashOutAmount,
            long unclassifiedCashOutEventCount,
            long bookings,
            BigDecimal occupancyRate,
            BigDecimal adr,
            BigDecimal revPar) {
    }
}
