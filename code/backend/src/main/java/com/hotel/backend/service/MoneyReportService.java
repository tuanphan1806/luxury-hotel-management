package com.hotel.backend.service;

import com.hotel.backend.dto.response.MoneyReportResponse;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.statistics.BusinessStatisticsQueryRepository;
import com.hotel.backend.statistics.StatisticsGranularity;
import com.hotel.backend.statistics.StatisticsPeriod;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Single read model for operational money reporting.
 *
 * <p>It never posts, adjusts, or closes accounting data. All values come from
 * completed reservation payments/refunds, making the same calculation usable
 * by the ADMIN report and cashier-shift window.</p>
 */
@Service
public class MoneyReportService {

    private final BusinessStatisticsQueryRepository queryRepository;
    private final Clock clock;

    @Autowired
    public MoneyReportService(BusinessStatisticsQueryRepository queryRepository) {
        this(queryRepository, Clock.systemUTC());
    }

    MoneyReportService(
            BusinessStatisticsQueryRepository queryRepository,
            Clock clock) {
        this.queryRepository = queryRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MoneyReportResponse.Report report(
            LocalDate requestedFrom,
            LocalDate requestedTo,
            String requestedGranularity) {
        StatisticsPeriod range = StatisticsPeriod.resolve(
                requestedFrom, requestedTo, clock);
        StatisticsGranularity granularity =
                StatisticsGranularity.parse(requestedGranularity);
        Map<LocalDate, BusinessStatisticsQueryRepository.MoneyFlowRow> rows =
                queryRepository.moneyFlow(range, granularity).stream()
                        .collect(Collectors.toMap(
                                BusinessStatisticsQueryRepository.MoneyFlowRow::period,
                                Function.identity(),
                                (left, right) -> right,
                                LinkedHashMap::new));

        List<MoneyReportResponse.Period> periods = new ArrayList<>();
        MoneyAccumulator total = new MoneyAccumulator();
        long unmatchedCount = 0L;
        BigDecimal unmatchedAmount = BigDecimal.ZERO;
        LocalDate bucket = granularity.bucketStart(range.from());
        LocalDate rangeEndExclusive = range.to().plusDays(1);
        while (!bucket.isAfter(range.to())) {
            BusinessStatisticsQueryRepository.MoneyFlowRow row = rows.get(bucket);
            MoneyReportResponse.Breakdown amounts = row == null
                    ? emptyBreakdown()
                    : breakdown(
                            row.cashIncome(),
                            row.transferIncome(),
                            row.cashRefund(),
                            row.transferRefund(),
                            row.paymentCount(),
                            row.refundCount());
            LocalDate bucketEndExclusive = granularity.nextBucket(bucket);
            periods.add(new MoneyReportResponse.Period(
                    bucket.isBefore(range.from()) ? range.from() : bucket,
                    bucketEndExclusive.isAfter(rangeEndExclusive)
                            ? rangeEndExclusive
                            : bucketEndExclusive,
                    amounts));
            total.add(amounts);
            if (row != null) {
                unmatchedCount += row.unmatchedTransferCount();
                unmatchedAmount = unmatchedAmount.add(
                        money(row.unmatchedTransferAmount()));
            }
            bucket = bucketEndExclusive;
        }

        return new MoneyReportResponse.Report(
                range.from(),
                range.to(),
                StatisticsPeriod.HOTEL_ZONE.getId(),
                granularity.name().toLowerCase(),
                total.toBreakdown(),
                List.copyOf(periods),
                unmatchedCount,
                unmatchedAmount,
                clock.instant());
    }

    @Transactional(readOnly = true)
    public MoneyReportResponse.Breakdown summarizeWindow(
            Instant fromUtc,
            Instant toUtc) {
        if (fromUtc == null || toUtc == null || !fromUtc.isBefore(toUtc)) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "Khoảng thời gian tổng hợp thu chi không hợp lệ");
        }
        BusinessStatisticsQueryRepository.MoneyWindowRow row =
                queryRepository.moneyWindow(fromUtc, toUtc);
        if (row == null) return emptyBreakdown();
        return breakdown(
                row.cashIncome(),
                row.transferIncome(),
                row.cashRefund(),
                row.transferRefund(),
                row.paymentCount(),
                row.refundCount());
    }

    @Transactional(readOnly = true)
    public MoneyReportResponse.ReservationMoneyPage reservationMoney(
            LocalDate requestedFrom,
            LocalDate requestedTo,
            String query,
            int requestedPage,
            int requestedSize) {
        StatisticsPeriod range = StatisticsPeriod.resolve(
                requestedFrom, requestedTo, clock);
        int page = Math.max(requestedPage, 0);
        int size = Math.min(Math.max(requestedSize, 1), 100);
        return queryRepository.reservationMoney(range, query, page, size);
    }

    private MoneyReportResponse.Breakdown breakdown(
            BigDecimal cashIncome,
            BigDecimal transferIncome,
            BigDecimal cashRefund,
            BigDecimal transferRefund,
            long paymentCount,
            long refundCount) {
        BigDecimal normalizedCashIncome = money(cashIncome);
        BigDecimal normalizedTransferIncome = money(transferIncome);
        BigDecimal normalizedCashRefund = money(cashRefund);
        BigDecimal normalizedTransferRefund = money(transferRefund);
        BigDecimal totalIncome = normalizedCashIncome
                .add(normalizedTransferIncome);
        BigDecimal totalRefund = normalizedCashRefund
                .add(normalizedTransferRefund);
        return new MoneyReportResponse.Breakdown(
                normalizedCashIncome,
                normalizedTransferIncome,
                totalIncome,
                normalizedCashRefund,
                normalizedTransferRefund,
                totalRefund,
                totalIncome.subtract(totalRefund),
                paymentCount,
                refundCount);
    }

    private MoneyReportResponse.Breakdown emptyBreakdown() {
        return breakdown(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0L,
                0L);
    }

    private BigDecimal money(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static final class MoneyAccumulator {
        private BigDecimal cashIncome = BigDecimal.ZERO;
        private BigDecimal transferIncome = BigDecimal.ZERO;
        private BigDecimal cashRefund = BigDecimal.ZERO;
        private BigDecimal transferRefund = BigDecimal.ZERO;
        private long paymentCount;
        private long refundCount;

        private void add(MoneyReportResponse.Breakdown value) {
            cashIncome = cashIncome.add(value.cashIncome());
            transferIncome = transferIncome.add(value.transferIncome());
            cashRefund = cashRefund.add(value.cashRefund());
            transferRefund = transferRefund.add(value.transferRefund());
            paymentCount += value.paymentCount();
            refundCount += value.refundCount();
        }

        private MoneyReportResponse.Breakdown toBreakdown() {
            BigDecimal totalIncome = cashIncome.add(transferIncome);
            BigDecimal totalRefund = cashRefund.add(transferRefund);
            return new MoneyReportResponse.Breakdown(
                    cashIncome,
                    transferIncome,
                    totalIncome,
                    cashRefund,
                    transferRefund,
                    totalRefund,
                    totalIncome.subtract(totalRefund),
                    paymentCount,
                    refundCount);
        }
    }
}
