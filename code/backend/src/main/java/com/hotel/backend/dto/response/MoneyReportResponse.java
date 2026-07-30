package com.hotel.backend.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Simple, operation-facing view of money received and refunded.
 *
 * <p>The report is calculated from completed reservation payments and refunds.
 * Unmatched provider events are deliberately excluded from revenue totals and
 * surfaced separately for reconciliation.</p>
 */
public final class MoneyReportResponse {
    private MoneyReportResponse() {
    }

    public record Breakdown(
            BigDecimal cashIncome,
            BigDecimal transferIncome,
            BigDecimal totalIncome,
            BigDecimal cashRefund,
            BigDecimal transferRefund,
            BigDecimal totalRefund,
            BigDecimal netRevenue,
            long paymentCount,
            long refundCount) {
    }

    public record Period(
            LocalDate period,
            LocalDate periodEndExclusive,
            Breakdown amounts) {
    }

    public record Report(
            LocalDate from,
            LocalDate to,
            String timezone,
            String granularity,
            Breakdown totals,
            List<Period> periods,
            long unmatchedTransferCount,
            BigDecimal unmatchedTransferAmount,
            Instant generatedAtUtc) {
    }

    public record ReservationMoney(
            Long reservationId,
            String reservationCode,
            String reservationStatus,
            Breakdown amounts,
            Instant lastMovementAtUtc) {
    }

    public record ReservationMoneyPage(
            List<ReservationMoney> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }
}
