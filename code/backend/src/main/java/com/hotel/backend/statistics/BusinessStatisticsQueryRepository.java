package com.hotel.backend.statistics;

import com.hotel.backend.dto.response.BusinessStatisticsResponse;
import com.hotel.backend.dto.response.MoneyReportResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Repository
@RequiredArgsConstructor
public class BusinessStatisticsQueryRepository {
    private static final String HOTEL_TIMEZONE = "Asia/Ho_Chi_Minh";
    private static final String PAID_STATUSES =
            "('SUCCESS', 'REFUND_PENDING', 'REFUNDED')";

    private final NamedParameterJdbcTemplate jdbc;

    public List<RevenueRow> revenue(StatisticsPeriod period,
                                    StatisticsGranularity granularity) {
        String unit = granularity.postgresUnit();
        String invoiceBucket = bucketUtc("invoice.issued_at_utc", unit);
        String paymentBucket = bucketUtc("payment.paid_at_utc", unit);
        String refundBucket = bucketUtc("refund.completed_at_utc", unit);
        String sql = """
                WITH invoice_totals AS (
                    SELECT %s AS bucket,
                           SUM(invoice.total_amount) AS recognized_revenue,
                           SUM(COALESCE(invoice.actual_room_charge,
                                        invoice.room_charge, 0)
                               + COALESCE(invoice.extra_guest_charge, 0)) AS room_revenue,
                           SUM(COALESCE(invoice.add_on_service_amount, 0)) AS add_on_revenue,
                           SUM(COALESCE(invoice.additional_fee, 0)) AS additional_fee,
                           SUM(COALESCE(invoice.late_checkout_fee, 0)) AS late_checkout_fee,
                           SUM(invoice.total_amount
                               - COALESCE(invoice.actual_room_charge,
                                          invoice.room_charge, 0)
                               - COALESCE(invoice.extra_guest_charge, 0)
                               - COALESCE(invoice.add_on_service_amount, 0)
                               - COALESCE(invoice.additional_fee, 0)
                               - COALESCE(invoice.late_checkout_fee, 0)
                               - COALESCE(invoice.tax_amount, 0)
                               + COALESCE(invoice.discount_amount, 0)) AS other_revenue,
                           SUM(COALESCE(invoice.discount_amount, 0)) AS discount_amount,
                           SUM(COALESCE(invoice.tax_amount, 0)) AS tax_amount,
                           COUNT(*) AS invoice_count
                    FROM reservation_invoices invoice
                    WHERE invoice.issued_at_utc >= :fromUtc
                      AND invoice.issued_at_utc < :toUtc
                    GROUP BY 1
                ), payment_totals AS (
                    SELECT %s AS bucket,
                           SUM(payment.received_amount)
                               FILTER (WHERE payment.received_amount IS NOT NULL)
                               AS gross_cash_inflow,
                           SUM(payment.accepted_amount)
                               FILTER (WHERE payment.accepted_amount IS NOT NULL)
                               AS accepted_cash_inflow,
                           SUM(payment.amount)
                               FILTER (WHERE payment.received_amount IS NULL)
                               AS legacy_unreconciled_amount,
                           COUNT(*)
                               FILTER (WHERE payment.received_amount IS NULL)
                               AS legacy_unreconciled_count
                    FROM payment_transactions payment
                    WHERE payment.paid_at_utc >= :fromUtc
                      AND payment.paid_at_utc < :toUtc
                      AND payment.status IN %s
                    GROUP BY 1
                ), refund_totals AS (
                    SELECT %s AS bucket,
                           SUM(COALESCE(refund.actual_refund_amount,
                                        refund.requested_amount,
                                        refund.amount, 0)) AS refund_outflow
                    FROM payment_refunds refund
                    WHERE refund.completed_at_utc >= :fromUtc
                      AND refund.completed_at_utc < :toUtc
                      AND refund.status = 'SUCCEEDED'
                    GROUP BY 1
                ), period_keys AS (
                    SELECT bucket FROM invoice_totals
                    UNION SELECT bucket FROM payment_totals
                    UNION SELECT bucket FROM refund_totals
                )
                SELECT period_keys.bucket,
                       COALESCE(invoice_totals.recognized_revenue, 0)
                           AS recognized_revenue,
                       COALESCE(invoice_totals.room_revenue, 0) AS room_revenue,
                       COALESCE(invoice_totals.add_on_revenue, 0) AS add_on_revenue,
                       COALESCE(invoice_totals.other_revenue, 0) AS other_revenue,
                       COALESCE(invoice_totals.additional_fee, 0) AS additional_fee,
                       COALESCE(invoice_totals.late_checkout_fee, 0)
                           AS late_checkout_fee,
                       COALESCE(invoice_totals.discount_amount, 0) AS discount_amount,
                       COALESCE(invoice_totals.tax_amount, 0) AS tax_amount,
                       COALESCE(invoice_totals.invoice_count, 0) AS invoice_count,
                       COALESCE(payment_totals.gross_cash_inflow, 0)
                           AS gross_cash_inflow,
                       COALESCE(payment_totals.accepted_cash_inflow, 0)
                           AS accepted_cash_inflow,
                       COALESCE(refund_totals.refund_outflow, 0) AS refund_outflow,
                       COALESCE(payment_totals.legacy_unreconciled_amount, 0)
                           AS legacy_unreconciled_amount,
                       COALESCE(payment_totals.legacy_unreconciled_count, 0)
                           AS legacy_unreconciled_count
                FROM period_keys
                LEFT JOIN invoice_totals USING (bucket)
                LEFT JOIN payment_totals USING (bucket)
                LEFT JOIN refund_totals USING (bucket)
                ORDER BY period_keys.bucket
                """.formatted(
                invoiceBucket,
                paymentBucket,
                PAID_STATUSES,
                refundBucket);
        return jdbc.query(sql, utcParameters(period), (resultSet, rowNum) ->
                new RevenueRow(
                        localDate(resultSet, "bucket"),
                        decimal(resultSet, "recognized_revenue"),
                        decimal(resultSet, "room_revenue"),
                        decimal(resultSet, "add_on_revenue"),
                        decimal(resultSet, "other_revenue"),
                        decimal(resultSet, "additional_fee"),
                        decimal(resultSet, "late_checkout_fee"),
                        decimal(resultSet, "discount_amount"),
                        decimal(resultSet, "tax_amount"),
                        resultSet.getLong("invoice_count"),
                        decimal(resultSet, "gross_cash_inflow"),
                        decimal(resultSet, "accepted_cash_inflow"),
                        decimal(resultSet, "refund_outflow"),
                        decimal(resultSet, "legacy_unreconciled_amount"),
                        resultSet.getLong("legacy_unreconciled_count")));
    }

    public List<CashFlowRow> cashFlow(
            StatisticsPeriod period,
            StatisticsGranularity granularity,
            String provider) {
        String unit = granularity.postgresUnit();
        String paymentBucket = bucketUtc("payment.paid_at_utc", unit);
        String refundBucket = bucketUtc("refund.completed_at_utc", unit);
        String providerEventBucket = bucketUtc(
                "COALESCE(provider_event.provider_occurred_at_utc, "
                        + "provider_event.received_at_utc)", unit);
        String sql = """
                WITH payment_totals AS (
                    SELECT %s AS bucket,
                           SUM(payment.received_amount)
                               FILTER (WHERE payment.received_amount IS NOT NULL)
                               AS gross_cash_inflow,
                           SUM(payment.accepted_amount)
                               FILTER (WHERE payment.accepted_amount IS NOT NULL)
                               AS accepted_cash_inflow,
                           SUM(payment.amount)
                               FILTER (WHERE payment.received_amount IS NULL)
                               AS legacy_unreconciled_amount,
                           COUNT(*) AS payment_count,
                           COUNT(*) FILTER (WHERE payment.received_amount IS NULL)
                               AS legacy_unreconciled_count
                    FROM payment_transactions payment
                    WHERE payment.paid_at_utc >= :fromUtc
                      AND payment.paid_at_utc < :toUtc
                      AND payment.status IN %s
                      AND (:provider IS NULL OR UPPER(payment.provider) = :provider)
                    GROUP BY 1
                ), unmatched_in_totals AS (
                    SELECT %s AS bucket,
                           SUM(provider_event.amount) AS unmatched_cash_inflow,
                           COUNT(*) AS unmatched_cash_in_count
                    FROM payment_provider_events provider_event
                    WHERE COALESCE(provider_event.provider_occurred_at_utc,
                                   provider_event.received_at_utc) >= :fromUtc
                      AND COALESCE(provider_event.provider_occurred_at_utc,
                                   provider_event.received_at_utc) < :toUtc
                      AND LOWER(provider_event.transfer_type) = 'in'
                      AND provider_event.amount > 0
                      AND provider_event.payment_transaction_id IS NULL
                      AND provider_event.status IN (
                          'RECEIVED', 'PROCESSING',
                          'FAILED_RETRYABLE', 'REVIEW_REQUIRED')
                      AND (:provider IS NULL
                           OR UPPER(provider_event.provider) = :provider)
                    GROUP BY 1
                ), refund_totals AS (
                    SELECT %s AS bucket,
                           SUM(COALESCE(refund.actual_refund_amount,
                                        refund.requested_amount,
                                        refund.amount, 0)) AS refund_outflow,
                           COUNT(*) AS refund_count
                    FROM payment_refunds refund
                    WHERE refund.completed_at_utc >= :fromUtc
                      AND refund.completed_at_utc < :toUtc
                      AND refund.status = 'SUCCEEDED'
                      AND (:provider IS NULL OR UPPER(refund.provider) = :provider)
                    GROUP BY 1
                ), unclassified_out_totals AS (
                    SELECT %s AS bucket,
                           SUM(provider_event.amount)
                               AS unclassified_cash_outflow,
                           COUNT(*) AS unclassified_cash_out_count
                    FROM payment_provider_events provider_event
                    WHERE COALESCE(provider_event.provider_occurred_at_utc,
                                   provider_event.received_at_utc) >= :fromUtc
                      AND COALESCE(provider_event.provider_occurred_at_utc,
                                   provider_event.received_at_utc) < :toUtc
                      AND LOWER(provider_event.transfer_type) = 'out'
                      AND provider_event.amount > 0
                      AND provider_event.status IN (
                          'RECEIVED', 'PROCESSING',
                          'FAILED_RETRYABLE', 'REVIEW_REQUIRED')
                      AND (:provider IS NULL
                           OR UPPER(provider_event.provider) = :provider)
                      AND NOT EXISTS (
                          SELECT 1
                          FROM payment_refunds matched_refund
                          WHERE matched_refund.completion_provider_event_id
                                = provider_event.id
                            AND matched_refund.status = 'SUCCEEDED'
                      )
                    GROUP BY 1
                ), period_keys AS (
                    SELECT bucket FROM payment_totals
                    UNION SELECT bucket FROM unmatched_in_totals
                    UNION SELECT bucket FROM refund_totals
                    UNION SELECT bucket FROM unclassified_out_totals
                )
                SELECT period_keys.bucket,
                       COALESCE(payment_totals.gross_cash_inflow, 0)
                           + COALESCE(unmatched_in_totals.unmatched_cash_inflow, 0)
                           AS gross_cash_inflow,
                       COALESCE(payment_totals.accepted_cash_inflow, 0)
                           AS accepted_cash_inflow,
                       COALESCE(unmatched_in_totals.unmatched_cash_inflow, 0)
                           AS unmatched_cash_inflow,
                       COALESCE(refund_totals.refund_outflow, 0) AS refund_outflow,
                       COALESCE(unclassified_out_totals.unclassified_cash_outflow, 0)
                           AS unclassified_cash_outflow,
                       COALESCE(payment_totals.payment_count, 0) AS payment_count,
                       COALESCE(unmatched_in_totals.unmatched_cash_in_count, 0)
                           AS unmatched_cash_in_count,
                       COALESCE(refund_totals.refund_count, 0) AS refund_count,
                       COALESCE(unclassified_out_totals.unclassified_cash_out_count, 0)
                           AS unclassified_cash_out_count,
                       COALESCE(payment_totals.legacy_unreconciled_amount, 0)
                           AS legacy_unreconciled_amount,
                       COALESCE(payment_totals.legacy_unreconciled_count, 0)
                           AS legacy_unreconciled_count
                FROM period_keys
                LEFT JOIN payment_totals USING (bucket)
                LEFT JOIN unmatched_in_totals USING (bucket)
                LEFT JOIN refund_totals USING (bucket)
                LEFT JOIN unclassified_out_totals USING (bucket)
                ORDER BY period_keys.bucket
                """.formatted(
                paymentBucket,
                PAID_STATUSES,
                providerEventBucket,
                refundBucket,
                providerEventBucket);
        MapSqlParameterSource parameters = utcParameters(period)
                .addValue("provider", normalizeFilter(provider), Types.VARCHAR);
        return jdbc.query(sql, parameters, (resultSet, rowNum) ->
                new CashFlowRow(
                        localDate(resultSet, "bucket"),
                        decimal(resultSet, "gross_cash_inflow"),
                        decimal(resultSet, "accepted_cash_inflow"),
                        decimal(resultSet, "unmatched_cash_inflow"),
                        decimal(resultSet, "refund_outflow"),
                        decimal(resultSet, "unclassified_cash_outflow"),
                        resultSet.getLong("payment_count"),
                        resultSet.getLong("unmatched_cash_in_count"),
                        resultSet.getLong("refund_count"),
                        resultSet.getLong("unclassified_cash_out_count"),
                        decimal(resultSet, "legacy_unreconciled_amount"),
                        resultSet.getLong("legacy_unreconciled_count")));
    }

    /**
     * Canonical money movements linked to reservations, split by collection
     * and refund channel. Unmatched SePay events are reported separately and
     * never included in revenue totals.
     */
    public List<MoneyFlowRow> moneyFlow(
            StatisticsPeriod period,
            StatisticsGranularity granularity) {
        String unit = granularity.postgresUnit();
        String paymentBucket = bucketUtc("payment.paid_at_utc", unit);
        String refundBucket = bucketUtc("refund.completed_at_utc", unit);
        String providerEventBucket = bucketUtc(
                "COALESCE(provider_event.provider_occurred_at_utc, "
                        + "provider_event.received_at_utc)", unit);
        String sql = """
                WITH payment_totals AS (
                    SELECT %s AS bucket,
                           SUM(COALESCE(payment.received_amount,
                                        payment.amount, 0))
                               FILTER (WHERE payment.provider = 'CASH')
                               AS cash_income,
                           SUM(COALESCE(payment.received_amount,
                                        payment.amount, 0))
                               FILTER (WHERE payment.provider = 'SEPAY')
                               AS transfer_income,
                           COUNT(*) AS payment_count
                    FROM payment_transactions payment
                    WHERE payment.paid_at_utc >= :fromUtc
                      AND payment.paid_at_utc < :toUtc
                      AND payment.status IN %s
                    GROUP BY 1
                ), refund_totals AS (
                    SELECT %s AS bucket,
                           SUM(COALESCE(refund.actual_refund_amount,
                                        refund.requested_amount,
                                        refund.amount, 0))
                               FILTER (
                                   WHERE refund.channel = 'CASH_AT_COUNTER')
                               AS cash_refund,
                           SUM(COALESCE(refund.actual_refund_amount,
                                        refund.requested_amount,
                                        refund.amount, 0))
                               FILTER (
                                   WHERE refund.channel =
                                         'MANUAL_BANK_TRANSFER')
                               AS transfer_refund,
                           COUNT(*) AS refund_count
                    FROM payment_refunds refund
                    WHERE refund.completed_at_utc >= :fromUtc
                      AND refund.completed_at_utc < :toUtc
                      AND refund.status = 'SUCCEEDED'
                    GROUP BY 1
                ), unmatched_totals AS (
                    SELECT %s AS bucket,
                           COUNT(*) AS unmatched_count,
                           SUM(provider_event.amount) AS unmatched_amount
                    FROM payment_provider_events provider_event
                    WHERE COALESCE(provider_event.provider_occurred_at_utc,
                                   provider_event.received_at_utc) >= :fromUtc
                      AND COALESCE(provider_event.provider_occurred_at_utc,
                                   provider_event.received_at_utc) < :toUtc
                      AND LOWER(provider_event.transfer_type) = 'in'
                      AND provider_event.amount > 0
                      AND provider_event.payment_transaction_id IS NULL
                      AND provider_event.status IN (
                          'RECEIVED', 'PROCESSING',
                          'FAILED_RETRYABLE', 'REVIEW_REQUIRED')
                    GROUP BY 1
                ), period_keys AS (
                    SELECT bucket FROM payment_totals
                    UNION SELECT bucket FROM refund_totals
                    UNION SELECT bucket FROM unmatched_totals
                )
                SELECT period_keys.bucket,
                       COALESCE(payment_totals.cash_income, 0)
                           AS cash_income,
                       COALESCE(payment_totals.transfer_income, 0)
                           AS transfer_income,
                       COALESCE(refund_totals.cash_refund, 0)
                           AS cash_refund,
                       COALESCE(refund_totals.transfer_refund, 0)
                           AS transfer_refund,
                       COALESCE(payment_totals.payment_count, 0)
                           AS payment_count,
                       COALESCE(refund_totals.refund_count, 0)
                           AS refund_count,
                       COALESCE(unmatched_totals.unmatched_count, 0)
                           AS unmatched_count,
                       COALESCE(unmatched_totals.unmatched_amount, 0)
                           AS unmatched_amount
                FROM period_keys
                LEFT JOIN payment_totals USING (bucket)
                LEFT JOIN refund_totals USING (bucket)
                LEFT JOIN unmatched_totals USING (bucket)
                ORDER BY period_keys.bucket
                """.formatted(
                paymentBucket,
                PAID_STATUSES,
                refundBucket,
                providerEventBucket);
        return jdbc.query(sql, utcParameters(period), (resultSet, rowNum) ->
                new MoneyFlowRow(
                        localDate(resultSet, "bucket"),
                        decimal(resultSet, "cash_income"),
                        decimal(resultSet, "transfer_income"),
                        decimal(resultSet, "cash_refund"),
                        decimal(resultSet, "transfer_refund"),
                        resultSet.getLong("payment_count"),
                        resultSet.getLong("refund_count"),
                        resultSet.getLong("unmatched_count"),
                        decimal(resultSet, "unmatched_amount")));
    }

    /**
     * Money movements inside an exact UTC interval. Used by cashier shifts;
     * the interval is a reporting window and does not re-post any transaction.
     */
    public MoneyWindowRow moneyWindow(Instant fromUtc, Instant toUtc) {
        String sql = """
                WITH payment_totals AS (
                    SELECT COALESCE(SUM(COALESCE(payment.received_amount,
                                                 payment.amount, 0))
                                      FILTER (
                                          WHERE payment.provider = 'CASH'), 0)
                               AS cash_income,
                           COALESCE(SUM(COALESCE(payment.received_amount,
                                                 payment.amount, 0))
                                      FILTER (
                                          WHERE payment.provider = 'SEPAY'), 0)
                               AS transfer_income,
                           COUNT(*) AS payment_count
                    FROM payment_transactions payment
                    WHERE payment.paid_at_utc >= :fromUtc
                      AND payment.paid_at_utc < :toUtc
                      AND payment.status IN %s
                ), refund_totals AS (
                    SELECT COALESCE(SUM(COALESCE(
                                           refund.actual_refund_amount,
                                           refund.requested_amount,
                                           refund.amount, 0))
                                      FILTER (
                                          WHERE refund.channel =
                                                'CASH_AT_COUNTER'), 0)
                               AS cash_refund,
                           COALESCE(SUM(COALESCE(
                                           refund.actual_refund_amount,
                                           refund.requested_amount,
                                           refund.amount, 0))
                                      FILTER (
                                          WHERE refund.channel =
                                                'MANUAL_BANK_TRANSFER'), 0)
                               AS transfer_refund,
                           COUNT(*) AS refund_count
                    FROM payment_refunds refund
                    WHERE refund.completed_at_utc >= :fromUtc
                      AND refund.completed_at_utc < :toUtc
                      AND refund.status = 'SUCCEEDED'
                )
                SELECT payment_totals.cash_income,
                       payment_totals.transfer_income,
                       refund_totals.cash_refund,
                       refund_totals.transfer_refund,
                       payment_totals.payment_count,
                       refund_totals.refund_count
                FROM payment_totals
                CROSS JOIN refund_totals
                """.formatted(PAID_STATUSES);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("fromUtc", Timestamp.from(fromUtc))
                .addValue("toUtc", Timestamp.from(toUtc));
        return jdbc.queryForObject(sql, parameters, (resultSet, rowNum) ->
                new MoneyWindowRow(
                        decimal(resultSet, "cash_income"),
                        decimal(resultSet, "transfer_income"),
                        decimal(resultSet, "cash_refund"),
                        decimal(resultSet, "transfer_refund"),
                        resultSet.getLong("payment_count"),
                        resultSet.getLong("refund_count")));
    }

    /**
     * Reservation-level drill-down for the same payment/refund interval used
     * by {@link #moneyFlow}. It deliberately filters by money movement time,
     * not invoice issue time, so the page reconciles with the summary above.
     */
    public MoneyReportResponse.ReservationMoneyPage reservationMoney(
            StatisticsPeriod period,
            String query,
            int page,
            int size) {
        String aggregates = """
                WITH payment_totals AS (
                    SELECT payment.reservation_id,
                           SUM(COALESCE(payment.received_amount,
                                        payment.amount, 0))
                               FILTER (WHERE payment.provider = 'CASH')
                               AS cash_income,
                           SUM(COALESCE(payment.received_amount,
                                        payment.amount, 0))
                               FILTER (WHERE payment.provider = 'SEPAY')
                               AS transfer_income,
                           COUNT(*) AS payment_count,
                           MAX(payment.paid_at_utc) AS last_payment_at_utc
                    FROM payment_transactions payment
                    WHERE payment.paid_at_utc >= :fromUtc
                      AND payment.paid_at_utc < :toUtc
                      AND payment.status IN %s
                    GROUP BY payment.reservation_id
                ), refund_totals AS (
                    SELECT COALESCE(
                               refund.reservation_id,
                               refund_payment.reservation_id)
                               AS reservation_id,
                           SUM(COALESCE(
                                   refund.actual_refund_amount,
                                   refund.requested_amount,
                                   refund.amount, 0))
                               FILTER (
                                   WHERE refund.channel = 'CASH_AT_COUNTER')
                               AS cash_refund,
                           SUM(COALESCE(
                                   refund.actual_refund_amount,
                                   refund.requested_amount,
                                   refund.amount, 0))
                               FILTER (
                                   WHERE refund.channel =
                                         'MANUAL_BANK_TRANSFER')
                               AS transfer_refund,
                           COUNT(*) AS refund_count,
                           MAX(refund.completed_at_utc)
                               AS last_refund_at_utc
                    FROM payment_refunds refund
                    LEFT JOIN payment_transactions refund_payment
                      ON refund_payment.id = refund.payment_transaction_id
                    WHERE refund.completed_at_utc >= :fromUtc
                      AND refund.completed_at_utc < :toUtc
                      AND refund.status = 'SUCCEEDED'
                    GROUP BY COALESCE(
                        refund.reservation_id,
                        refund_payment.reservation_id)
                ), money_by_reservation AS (
                    SELECT COALESCE(
                               payment_totals.reservation_id,
                               refund_totals.reservation_id)
                               AS reservation_id,
                           COALESCE(payment_totals.cash_income, 0)
                               AS cash_income,
                           COALESCE(payment_totals.transfer_income, 0)
                               AS transfer_income,
                           COALESCE(refund_totals.cash_refund, 0)
                               AS cash_refund,
                           COALESCE(refund_totals.transfer_refund, 0)
                               AS transfer_refund,
                           COALESCE(payment_totals.payment_count, 0)
                               AS payment_count,
                           COALESCE(refund_totals.refund_count, 0)
                               AS refund_count,
                           COALESCE(
                               GREATEST(
                                   payment_totals.last_payment_at_utc,
                                   refund_totals.last_refund_at_utc),
                               payment_totals.last_payment_at_utc,
                               refund_totals.last_refund_at_utc)
                               AS last_movement_at_utc
                    FROM payment_totals
                    FULL OUTER JOIN refund_totals
                      ON refund_totals.reservation_id =
                         payment_totals.reservation_id
                    WHERE COALESCE(
                              payment_totals.reservation_id,
                              refund_totals.reservation_id) IS NOT NULL
                )
                """.formatted(PAID_STATUSES);
        String filters = """
                WHERE (:query IS NULL
                       OR POSITION(
                           :query IN UPPER(reservation.reservation_code)) > 0)
                """;
        MapSqlParameterSource parameters = utcParameters(period)
                .addValue("query", normalizeFilter(query), Types.VARCHAR)
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        String dataSql = """
                %s
                SELECT reservation.id AS reservation_id,
                       reservation.reservation_code,
                       reservation.status AS reservation_status,
                       money.cash_income,
                       money.transfer_income,
                       money.cash_refund,
                       money.transfer_refund,
                       money.payment_count,
                       money.refund_count,
                       money.last_movement_at_utc
                FROM money_by_reservation money
                JOIN reservations reservation
                  ON reservation.id = money.reservation_id
                %s
                ORDER BY money.last_movement_at_utc DESC,
                         reservation.id DESC
                LIMIT :limit OFFSET :offset
                """.formatted(aggregates, filters);
        List<MoneyReportResponse.ReservationMoney> content = jdbc.query(
                dataSql,
                parameters,
                (resultSet, rowNum) -> {
                    BigDecimal cashIncome =
                            decimal(resultSet, "cash_income");
                    BigDecimal transferIncome =
                            decimal(resultSet, "transfer_income");
                    BigDecimal cashRefund =
                            decimal(resultSet, "cash_refund");
                    BigDecimal transferRefund =
                            decimal(resultSet, "transfer_refund");
                    BigDecimal totalIncome =
                            cashIncome.add(transferIncome);
                    BigDecimal totalRefund =
                            cashRefund.add(transferRefund);
                    return new MoneyReportResponse.ReservationMoney(
                            resultSet.getLong("reservation_id"),
                            resultSet.getString("reservation_code"),
                            resultSet.getString("reservation_status"),
                            new MoneyReportResponse.Breakdown(
                                    cashIncome,
                                    transferIncome,
                                    totalIncome,
                                    cashRefund,
                                    transferRefund,
                                    totalRefund,
                                    totalIncome.subtract(totalRefund),
                                    resultSet.getLong("payment_count"),
                                    resultSet.getLong("refund_count")),
                            instant(resultSet, "last_movement_at_utc"));
                });
        String countSql = """
                %s
                SELECT COUNT(*)
                FROM money_by_reservation money
                JOIN reservations reservation
                  ON reservation.id = money.reservation_id
                %s
                """.formatted(aggregates, filters);
        Long total = jdbc.queryForObject(
                countSql, parameters, Long.class);
        long totalElements = total != null ? total : 0L;
        int totalPages = totalElements == 0
                ? 0
                : (int) ((totalElements + size - 1L) / size);
        return new MoneyReportResponse.ReservationMoneyPage(
                content, page, size, totalElements, totalPages);
    }

    public List<BookingRow> bookings(StatisticsPeriod period,
                                     StatisticsGranularity granularity) {
        String bucket = bucketLocal("reservation.created_at",
                granularity.postgresUnit());
        String sql = """
                SELECT %s AS bucket,
                       COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE status = 'PAYMENT_PENDING')
                           AS payment_pending,
                       COUNT(*) FILTER (WHERE status = 'DRAFT') AS draft,
                       COUNT(*) FILTER (WHERE status = 'CONFIRMED') AS confirmed,
                       COUNT(*) FILTER (WHERE status = 'CANCELLATION_PENDING')
                           AS cancellation_pending,
                       COUNT(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
                       COUNT(*) FILTER (WHERE status = 'CHECKED_IN') AS checked_in,
                       COUNT(*) FILTER (WHERE status = 'CHECKED_OUT') AS checked_out,
                       COUNT(*) FILTER (WHERE status = 'NO_SHOW') AS no_show
                FROM reservations reservation
                WHERE reservation.created_at >= :fromLocal
                  AND reservation.created_at < :toLocal
                GROUP BY 1
                ORDER BY 1
                """.formatted(bucket);
        return jdbc.query(sql, localParameters(period), (resultSet, rowNum) ->
                new BookingRow(
                        localDate(resultSet, "bucket"),
                        resultSet.getLong("total"),
                        resultSet.getLong("payment_pending"),
                        resultSet.getLong("draft"),
                        resultSet.getLong("confirmed"),
                        resultSet.getLong("cancellation_pending"),
                        resultSet.getLong("cancelled"),
                        resultSet.getLong("checked_in"),
                        resultSet.getLong("checked_out"),
                        resultSet.getLong("no_show")));
    }

    public List<DailyOccupancyRow> dailyOccupancy(StatisticsPeriod period) {
        String sql = """
                WITH days AS (
                    SELECT generate_series(CAST(:fromDate AS date),
                                           CAST(:toDate AS date),
                                           INTERVAL '1 day')::date AS day
                ), sold_intervals AS (
                    SELECT reservation.id,
                           room_line.quantity,
                           CASE
                               WHEN reservation.status IN ('CHECKED_IN', 'CHECKED_OUT')
                                    AND reservation.actual_check_in IS NOT NULL
                                   THEN reservation.actual_check_in
                               ELSE reservation.check_in
                           END AS stay_start,
                           CASE
                               WHEN reservation.status = 'CHECKED_OUT'
                                    AND reservation.actual_check_out IS NOT NULL
                                   THEN reservation.actual_check_out
                               WHEN reservation.status = 'CHECKED_IN'
                                   THEN GREATEST(
                                       reservation.check_out,
                                       CURRENT_TIMESTAMP AT TIME ZONE '%s')
                               ELSE reservation.check_out
                           END AS stay_end
                    FROM reservations reservation
                    JOIN reservation_room_types room_line
                      ON room_line.reservation_id = reservation.id
                    WHERE reservation.status IN
                          ('CONFIRMED', 'CHECKED_IN', 'CHECKED_OUT')
                      AND (CASE
                               WHEN reservation.status IN ('CHECKED_IN', 'CHECKED_OUT')
                                    AND reservation.actual_check_in IS NOT NULL
                                   THEN reservation.actual_check_in
                               ELSE reservation.check_in
                           END) < :toLocal
                      AND (CASE
                               WHEN reservation.status = 'CHECKED_OUT'
                                    AND reservation.actual_check_out IS NOT NULL
                                   THEN reservation.actual_check_out
                               WHEN reservation.status = 'CHECKED_IN'
                                   THEN GREATEST(
                                       reservation.check_out,
                                       CURRENT_TIMESTAMP AT TIME ZONE '%s')
                               ELSE reservation.check_out
                           END) > :fromLocal
                ), sold AS (
                    SELECT days.day,
                           SUM(EXTRACT(EPOCH FROM (
                               LEAST(sold_intervals.stay_end,
                                     days.day + INTERVAL '1 day')
                               - GREATEST(sold_intervals.stay_start,
                                          days.day::timestamp)
                           )) / 3600.0 * sold_intervals.quantity) AS sold_hours
                    FROM days
                    JOIN sold_intervals
                      ON sold_intervals.stay_start < days.day + INTERVAL '1 day'
                     AND sold_intervals.stay_end > days.day::timestamp
                    GROUP BY days.day
                ), available AS (
                    SELECT days.day,
                           COUNT(room.id) * 24.0 AS available_hours
                    FROM days
                    LEFT JOIN rooms room
                      ON (room.created_at IS NULL
                          OR room.created_at < days.day + INTERVAL '1 day')
                     AND (room.decommissioned_at IS NULL
                          OR room.decommissioned_at > days.day::timestamp)
                     AND (room.sellable = TRUE
                          OR (room.decommissioned_at IS NOT NULL
                              AND room.decommissioned_at > days.day::timestamp))
                     AND NOT (
                         days.day >= (CURRENT_TIMESTAMP AT TIME ZONE '%s')::date
                         AND room.status = 'MAINTENANCE')
                    GROUP BY days.day
                ), revenue_intervals AS (
                    SELECT invoice.id,
                           COALESCE(invoice.actual_room_charge,
                                    invoice.room_charge, 0)
                               + COALESCE(invoice.extra_guest_charge, 0)
                               AS room_revenue,
                           COALESCE(reservation.actual_check_in,
                                    reservation.check_in) AS stay_start,
                           COALESCE(reservation.actual_check_out,
                                    reservation.check_out) AS stay_end
                    FROM reservation_invoices invoice
                    JOIN reservations reservation
                      ON reservation.id = invoice.reservation_id
                    WHERE COALESCE(reservation.actual_check_in,
                                   reservation.check_in) < :toLocal
                      AND COALESCE(reservation.actual_check_out,
                                   reservation.check_out) > :fromLocal
                      AND COALESCE(reservation.actual_check_out,
                                   reservation.check_out)
                          > COALESCE(reservation.actual_check_in,
                                     reservation.check_in)
                ), allocated_revenue AS (
                    SELECT days.day,
                           SUM(revenue_intervals.room_revenue
                               * EXTRACT(EPOCH FROM (
                                   LEAST(revenue_intervals.stay_end,
                                         days.day + INTERVAL '1 day')
                                   - GREATEST(revenue_intervals.stay_start,
                                              days.day::timestamp)
                               ))
                               / NULLIF(EXTRACT(EPOCH FROM (
                                   revenue_intervals.stay_end
                                   - revenue_intervals.stay_start)), 0))
                               AS allocated_room_revenue
                    FROM days
                    JOIN revenue_intervals
                      ON revenue_intervals.stay_start
                           < days.day + INTERVAL '1 day'
                     AND revenue_intervals.stay_end > days.day::timestamp
                    GROUP BY days.day
                )
                SELECT days.day,
                       COALESCE(sold.sold_hours, 0) AS sold_hours,
                       COALESCE(available.available_hours, 0)
                           AS available_hours,
                       COALESCE(allocated_revenue.allocated_room_revenue, 0)
                           AS allocated_room_revenue
                FROM days
                LEFT JOIN sold USING (day)
                LEFT JOIN available USING (day)
                LEFT JOIN allocated_revenue USING (day)
                ORDER BY days.day
                """.formatted(HOTEL_TIMEZONE, HOTEL_TIMEZONE, HOTEL_TIMEZONE);
        MapSqlParameterSource parameters = localParameters(period)
                .addValue("fromDate", Date.valueOf(period.from()))
                .addValue("toDate", Date.valueOf(period.to()));
        return jdbc.query(sql, parameters, (resultSet, rowNum) ->
                new DailyOccupancyRow(
                        localDate(resultSet, "day"),
                        decimal(resultSet, "sold_hours"),
                        decimal(resultSet, "available_hours"),
                        decimal(resultSet, "allocated_room_revenue")));
    }

    public List<RoomTypeRow> roomTypePerformance(StatisticsPeriod period) {
        String sql = """
                WITH days AS (
                    SELECT generate_series(CAST(:fromDate AS date),
                                           CAST(:toDate AS date),
                                           INTERVAL '1 day')::date AS day
                ), usage AS (
                    SELECT room_type.id AS room_type_id,
                           COUNT(DISTINCT reservation.id) AS booking_count,
                           SUM(room_line.quantity) AS reserved_quantity,
                           SUM(EXTRACT(EPOCH FROM (
                               LEAST(
                                   CASE
                                       WHEN reservation.status = 'CHECKED_OUT'
                                            AND reservation.actual_check_out IS NOT NULL
                                           THEN reservation.actual_check_out
                                       WHEN reservation.status = 'CHECKED_IN'
                                           THEN GREATEST(
                                               reservation.check_out,
                                               CURRENT_TIMESTAMP AT TIME ZONE '%s')
                                       ELSE reservation.check_out
                                   END,
                                   :toLocal)
                               - GREATEST(
                                   CASE
                                       WHEN reservation.status IN ('CHECKED_IN', 'CHECKED_OUT')
                                            AND reservation.actual_check_in IS NOT NULL
                                           THEN reservation.actual_check_in
                                       ELSE reservation.check_in
                                   END,
                                   :fromLocal)
                           )) / 3600.0 * room_line.quantity) AS sold_hours
                    FROM reservation_room_types room_line
                    JOIN reservations reservation
                      ON reservation.id = room_line.reservation_id
                    JOIN room_types room_type
                      ON room_type.id = room_line.room_type_id
                    WHERE reservation.status IN
                          ('CONFIRMED', 'CHECKED_IN', 'CHECKED_OUT')
                      AND (CASE
                               WHEN reservation.status IN ('CHECKED_IN', 'CHECKED_OUT')
                                    AND reservation.actual_check_in IS NOT NULL
                                   THEN reservation.actual_check_in
                               ELSE reservation.check_in
                           END) < :toLocal
                      AND (CASE
                               WHEN reservation.status = 'CHECKED_OUT'
                                    AND reservation.actual_check_out IS NOT NULL
                                   THEN reservation.actual_check_out
                               WHEN reservation.status = 'CHECKED_IN'
                                   THEN GREATEST(
                                       reservation.check_out,
                                       CURRENT_TIMESTAMP AT TIME ZONE '%s')
                               ELSE reservation.check_out
                           END) > :fromLocal
                    GROUP BY room_type.id
                ), availability AS (
                    SELECT room.room_type_id,
                           COUNT(room.id) * 24.0 AS available_hours
                    FROM days
                    JOIN rooms room
                      ON (room.created_at IS NULL
                          OR room.created_at < days.day + INTERVAL '1 day')
                     AND (room.decommissioned_at IS NULL
                          OR room.decommissioned_at > days.day::timestamp)
                     AND (room.sellable = TRUE
                          OR (room.decommissioned_at IS NOT NULL
                              AND room.decommissioned_at > days.day::timestamp))
                     AND NOT (
                         days.day >= (CURRENT_TIMESTAMP AT TIME ZONE '%s')::date
                         AND room.status = 'MAINTENANCE')
                    GROUP BY room.room_type_id
                ), invoice_room_lines AS (
                    SELECT room_type.id AS room_type_id,
                           COALESCE((line ->> 'actualRoomCharge')::numeric, 0)
                               AS room_revenue,
                           COALESCE((line ->> 'extraGuestCharge')::numeric, 0)
                               AS extra_guest_revenue,
                           COALESCE(reservation.actual_check_in,
                                    reservation.check_in) AS stay_start,
                           COALESCE(reservation.actual_check_out,
                                    reservation.check_out) AS stay_end
                    FROM reservation_invoices invoice
                    JOIN reservations reservation
                      ON reservation.id = invoice.reservation_id
                    CROSS JOIN LATERAL jsonb_array_elements(
                        COALESCE(invoice.snapshot_json::jsonb -> 'roomTypes',
                                 '[]'::jsonb)) line
                    JOIN room_types room_type
                      ON room_type.code = line ->> 'roomTypeCode'
                    WHERE COALESCE(reservation.actual_check_in,
                                   reservation.check_in) < :toLocal
                      AND COALESCE(reservation.actual_check_out,
                                   reservation.check_out) > :fromLocal
                      AND COALESCE(reservation.actual_check_out,
                                   reservation.check_out)
                          > COALESCE(reservation.actual_check_in,
                                     reservation.check_in)
                ), invoice_lines AS (
                    SELECT room_type_id,
                           SUM(room_revenue
                               * EXTRACT(EPOCH FROM (
                                   LEAST(stay_end, :toLocal)
                                   - GREATEST(stay_start, :fromLocal)
                               ))
                               / NULLIF(EXTRACT(EPOCH FROM (
                                   stay_end - stay_start)), 0))
                               AS recognized_room_revenue,
                           SUM(extra_guest_revenue
                               * EXTRACT(EPOCH FROM (
                                   LEAST(stay_end, :toLocal)
                                   - GREATEST(stay_start, :fromLocal)
                               ))
                               / NULLIF(EXTRACT(EPOCH FROM (
                                   stay_end - stay_start)), 0))
                               AS extra_guest_revenue
                    FROM invoice_room_lines
                    GROUP BY room_type_id
                )
                SELECT room_type.id,
                       room_type.code,
                       room_type.type_name,
                       COALESCE(usage.booking_count, 0) AS booking_count,
                       COALESCE(usage.reserved_quantity, 0) AS reserved_quantity,
                       COALESCE(usage.sold_hours, 0) AS sold_hours,
                       COALESCE(availability.available_hours, 0)
                           AS available_hours,
                       COALESCE(invoice_lines.recognized_room_revenue, 0)
                           AS recognized_room_revenue,
                       COALESCE(invoice_lines.extra_guest_revenue, 0)
                           AS extra_guest_revenue
                FROM room_types room_type
                LEFT JOIN usage ON usage.room_type_id = room_type.id
                LEFT JOIN availability
                  ON availability.room_type_id = room_type.id
                LEFT JOIN invoice_lines
                  ON invoice_lines.room_type_id = room_type.id
                WHERE usage.room_type_id IS NOT NULL
                   OR availability.room_type_id IS NOT NULL
                   OR invoice_lines.room_type_id IS NOT NULL
                ORDER BY recognized_room_revenue DESC,
                         room_type.type_name ASC
                """.formatted(HOTEL_TIMEZONE, HOTEL_TIMEZONE, HOTEL_TIMEZONE);
        MapSqlParameterSource parameters = localParameters(period)
                .addValue("fromDate", Date.valueOf(period.from()))
                .addValue("toDate", Date.valueOf(period.to()));
        return jdbc.query(sql, parameters, (resultSet, rowNum) ->
                new RoomTypeRow(
                        resultSet.getLong("id"),
                        resultSet.getString("code"),
                        resultSet.getString("type_name"),
                        resultSet.getLong("booking_count"),
                        resultSet.getLong("reserved_quantity"),
                        decimal(resultSet, "sold_hours"),
                        decimal(resultSet, "available_hours"),
                        decimal(resultSet, "recognized_room_revenue"),
                        decimal(resultSet, "extra_guest_revenue")));
    }

    public CurrentBalances currentBalances() {
        String sql = """
                WITH accepted_by_reservation AS (
                    SELECT payment.reservation_id,
                           SUM(payment.accepted_amount)
                               FILTER (WHERE payment.accepted_amount IS NOT NULL)
                               AS accepted_amount
                    FROM payment_transactions payment
                    WHERE payment.status IN %s
                    GROUP BY payment.reservation_id
                ), refunded_by_reservation AS (
                    SELECT COALESCE(refund.reservation_id,
                                    refund_payment.reservation_id)
                               AS reservation_id,
                           SUM(COALESCE(refund.actual_refund_amount,
                                        refund.requested_amount,
                                        refund.amount, 0)) AS refunded_amount
                    FROM payment_refunds refund
                    LEFT JOIN payment_transactions refund_payment
                      ON refund_payment.id = refund.payment_transaction_id
                    WHERE refund.status = 'SUCCEEDED'
                      AND COALESCE(refund.reservation_id,
                                   refund_payment.reservation_id) IS NOT NULL
                    GROUP BY COALESCE(refund.reservation_id,
                                      refund_payment.reservation_id)
                ), open_reservations AS (
                    SELECT reservation.id,
                           reservation.status,
                           reservation.total_amount,
                           GREATEST(
                               COALESCE(accepted.accepted_amount, 0)
                               - COALESCE(refunded.refunded_amount, 0), 0)
                               AS net_accepted
                    FROM reservations reservation
                    LEFT JOIN accepted_by_reservation accepted
                      ON accepted.reservation_id = reservation.id
                    LEFT JOIN refunded_by_reservation refunded
                      ON refunded.reservation_id = reservation.id
                    WHERE reservation.status IN
                          ('DRAFT', 'CONFIRMED', 'CANCELLATION_PENDING',
                           'CHECKED_IN')
                ), active_refund_payable AS (
                    SELECT SUM(COALESCE(refund.requested_amount,
                                        refund.amount, 0)) AS amount
                    FROM payment_refunds refund
                    WHERE refund.status IN
                          ('AWAITING_CUSTOMER_INFO',
                           'READY_FOR_MANUAL_TRANSFER', 'REQUESTED',
                           'PROCESSING', 'MANUAL_REVIEW', 'FAILED')
                ), required_refund_coverage AS (
                    SELECT payment.id,
                           COALESCE(payment.refund_required_amount, 0)
                               AS required_amount,
                           COALESCE(SUM(refund.amount) FILTER (
                               WHERE refund.source_type IN
                                   ('UNACCEPTED_PAYMENT',
                                    'ADDITIONAL_TRANSFER',
                                    'CHECKOUT_OVERPAYMENT')
                                 AND refund.status IN
                                   ('AWAITING_CUSTOMER_INFO',
                                    'READY_FOR_MANUAL_TRANSFER', 'REQUESTED',
                                    'PROCESSING', 'MANUAL_REVIEW', 'FAILED',
                                    'SUCCEEDED')
                           ), 0) AS covered_amount
                    FROM payment_transactions payment
                    LEFT JOIN payment_refunds refund
                      ON refund.payment_transaction_id = payment.id
                    WHERE COALESCE(payment.refund_required_amount, 0) > 0
                    GROUP BY payment.id, payment.refund_required_amount
                ), uncovered_required_refunds AS (
                    SELECT COALESCE(SUM(GREATEST(
                               required_amount - covered_amount, 0)), 0)
                               AS amount
                    FROM required_refund_coverage
                ), cancellation_refund_coverage AS (
                    SELECT reservation.id,
                           COALESCE(reservation.refundable_amount, 0)
                               AS required_amount,
                           COALESCE(SUM(refund.amount) FILTER (
                                WHERE refund.source_key LIKE
                                    CONCAT('reservation-cancellation:',
                                           reservation.id, ':%%')
                                 AND refund.status IN
                                   ('AWAITING_CUSTOMER_INFO',
                                    'READY_FOR_MANUAL_TRANSFER', 'REQUESTED',
                                    'PROCESSING', 'MANUAL_REVIEW', 'FAILED',
                                    'SUCCEEDED')
                           ), 0) AS covered_amount
                    FROM reservations reservation
                    LEFT JOIN payment_refunds refund
                      ON refund.reservation_id = reservation.id
                    WHERE reservation.status = 'CANCELLATION_PENDING'
                      AND COALESCE(reservation.refundable_amount, 0) > 0
                    GROUP BY reservation.id, reservation.refundable_amount
                ), uncovered_cancellation_refunds AS (
                    SELECT COALESCE(SUM(GREATEST(
                               required_amount - covered_amount, 0)), 0)
                               AS amount
                    FROM cancellation_refund_coverage
                )
                SELECT COALESCE(SUM(GREATEST(total_amount - net_accepted, 0))
                                    FILTER (WHERE status = 'CHECKED_IN'), 0)
                           AS outstanding_receivables,
                       COALESCE(SUM(net_accepted), 0) AS customer_deposits,
                       COALESCE((SELECT amount FROM active_refund_payable), 0)
                           + COALESCE((SELECT amount
                                      FROM uncovered_required_refunds), 0)
                           + COALESCE((SELECT amount
                                      FROM uncovered_cancellation_refunds), 0)
                           AS refund_payable
                FROM open_reservations
                """.formatted(PAID_STATUSES);
        return jdbc.queryForObject(sql, new MapSqlParameterSource(),
                (resultSet, rowNum) -> new CurrentBalances(
                        decimal(resultSet, "outstanding_receivables"),
                        decimal(resultSet, "customer_deposits"),
                        decimal(resultSet, "refund_payable")));
    }

    public BusinessStatisticsResponse.ReservationRevenuePage reservationRevenue(
            StatisticsPeriod period,
            StatisticsGranularity granularity,
            String query,
            String reservationStatus,
            int page,
            int size) {
        String invoiceBucket = bucketUtc(
                "selected.issued_at_utc", granularity.postgresUnit());
        String filters = """
                WHERE invoice.issued_at_utc >= :fromUtc
                  AND invoice.issued_at_utc < :toUtc
                  AND (:query IS NULL
                       OR POSITION(:query IN UPPER(reservation.reservation_code)) > 0
                       OR POSITION(:query IN UPPER(invoice.invoice_number)) > 0)
                  AND (:reservationStatus IS NULL
                       OR reservation.status = :reservationStatus)
                """;
        String aggregates = """
                WITH selected_invoices AS (
                    SELECT invoice.id AS invoice_id,
                           invoice.reservation_id,
                           invoice.invoice_number,
                           invoice.issued_at_utc,
                           invoice.settlement_status,
                           invoice.pricing_version,
                           invoice.actual_room_charge,
                           invoice.room_charge,
                           invoice.extra_guest_charge,
                           invoice.add_on_service_amount,
                           invoice.additional_fee,
                           invoice.late_checkout_fee,
                           invoice.tax_amount,
                           invoice.discount_amount,
                           invoice.total_amount,
                           reservation.reservation_code,
                           reservation.status AS reservation_status,
                           reservation.check_in,
                           reservation.check_out,
                           reservation.actual_check_in,
                           reservation.actual_check_out
                    FROM reservation_invoices invoice
                    JOIN reservations reservation
                      ON reservation.id = invoice.reservation_id
                    %s
                    ORDER BY invoice.issued_at_utc DESC, invoice.id DESC
                    LIMIT :limit OFFSET :offset
                ), payment_totals AS (
                    SELECT payment.reservation_id,
                           SUM(payment.received_amount)
                               FILTER (WHERE payment.received_amount IS NOT NULL)
                               AS gross_cash_inflow,
                           SUM(payment.accepted_amount)
                               FILTER (WHERE payment.accepted_amount IS NOT NULL)
                               AS accepted_cash_inflow,
                           COUNT(*) FILTER (WHERE payment.received_amount IS NULL)
                               AS legacy_payment_count
                    FROM payment_transactions payment
                    WHERE payment.status IN %s
                      AND EXISTS (
                          SELECT 1
                          FROM selected_invoices selected
                          WHERE selected.reservation_id = payment.reservation_id
                      )
                    GROUP BY payment.reservation_id
                ), refund_totals AS (
                    SELECT COALESCE(refund.reservation_id,
                                    refund_payment.reservation_id)
                               AS reservation_id,
                           SUM(COALESCE(refund.actual_refund_amount,
                                        refund.requested_amount,
                                        refund.amount, 0))
                               AS refund_outflow,
                           COUNT(*) FILTER (WHERE refund.actual_refund_amount IS NULL)
                               AS legacy_refund_count
                    FROM payment_refunds refund
                    LEFT JOIN payment_transactions refund_payment
                      ON refund_payment.id = refund.payment_transaction_id
                    WHERE refund.status = 'SUCCEEDED'
                      AND EXISTS (
                          SELECT 1
                          FROM selected_invoices selected
                          WHERE selected.reservation_id = COALESCE(
                              refund.reservation_id,
                              refund_payment.reservation_id)
                      )
                    GROUP BY COALESCE(refund.reservation_id,
                                      refund_payment.reservation_id)
                )
                """.formatted(filters, PAID_STATUSES);
        MapSqlParameterSource parameters = utcParameters(period)
                .addValue("query", normalizeFilter(query), Types.VARCHAR)
                .addValue("reservationStatus",
                        normalizeFilter(reservationStatus), Types.VARCHAR)
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        String dataSql = """
                %s
                SELECT %s AS bucket,
                       selected.reservation_id,
                       selected.reservation_code,
                       selected.reservation_status,
                       selected.check_in,
                       selected.check_out,
                       selected.actual_check_in,
                       selected.actual_check_out,
                       selected.invoice_number,
                       selected.issued_at_utc,
                       selected.issued_at_utc AT TIME ZONE '%s'
                           AS issued_at_local,
                       selected.settlement_status,
                       selected.pricing_version,
                       COALESCE(selected.actual_room_charge,
                                selected.room_charge, 0) AS room_charge,
                       COALESCE(selected.extra_guest_charge, 0)
                           AS extra_guest_charge,
                       COALESCE(selected.add_on_service_amount, 0)
                           AS add_on_service_amount,
                       COALESCE(selected.additional_fee, 0) AS additional_fee,
                       COALESCE(selected.late_checkout_fee, 0)
                           AS late_checkout_fee,
                       selected.total_amount
                           - COALESCE(selected.actual_room_charge,
                                      selected.room_charge, 0)
                           - COALESCE(selected.extra_guest_charge, 0)
                           - COALESCE(selected.add_on_service_amount, 0)
                           - COALESCE(selected.additional_fee, 0)
                           - COALESCE(selected.late_checkout_fee, 0)
                           - COALESCE(selected.tax_amount, 0)
                           + COALESCE(selected.discount_amount, 0)
                           AS other_revenue,
                       COALESCE(selected.discount_amount, 0) AS discount_amount,
                       COALESCE(selected.tax_amount, 0) AS tax_amount,
                       selected.total_amount AS recognized_revenue,
                       COALESCE(payment_totals.gross_cash_inflow, 0)
                           AS gross_cash_inflow,
                       COALESCE(payment_totals.accepted_cash_inflow, 0)
                           AS accepted_cash_inflow,
                       COALESCE(refund_totals.refund_outflow, 0)
                           AS refund_outflow,
                       COALESCE(payment_totals.gross_cash_inflow, 0)
                           - COALESCE(refund_totals.refund_outflow, 0)
                           AS net_cash_flow,
                       CASE
                           WHEN COALESCE(payment_totals.legacy_payment_count, 0) > 0
                             OR COALESCE(refund_totals.legacy_refund_count, 0) > 0
                           THEN 'LEGACY_UNRECONCILED'
                           ELSE 'CANONICAL'
                       END AS data_quality
                FROM selected_invoices selected
                LEFT JOIN payment_totals
                  ON payment_totals.reservation_id = selected.reservation_id
                LEFT JOIN refund_totals
                  ON refund_totals.reservation_id = selected.reservation_id
                ORDER BY selected.issued_at_utc DESC, selected.invoice_id DESC
                """.formatted(
                aggregates,
                invoiceBucket,
                HOTEL_TIMEZONE);
        List<BusinessStatisticsResponse.ReservationRevenueEntry> content =
                jdbc.query(dataSql, parameters, (resultSet, rowNum) -> {
                    LocalDate bucket = localDate(resultSet, "bucket");
                    return new BusinessStatisticsResponse.ReservationRevenueEntry(
                            bucket,
                            granularity.nextBucket(bucket),
                            resultSet.getLong("reservation_id"),
                            resultSet.getString("reservation_code"),
                            resultSet.getString("reservation_status"),
                            resultSet.getObject("check_in", LocalDateTime.class),
                            resultSet.getObject("check_out", LocalDateTime.class),
                            resultSet.getObject(
                                    "actual_check_in", LocalDateTime.class),
                            resultSet.getObject(
                                    "actual_check_out", LocalDateTime.class),
                            resultSet.getString("invoice_number"),
                            instant(resultSet, "issued_at_utc"),
                            resultSet.getObject(
                                    "issued_at_local", LocalDateTime.class),
                            resultSet.getString("settlement_status"),
                            resultSet.getString("pricing_version"),
                            decimal(resultSet, "room_charge"),
                            decimal(resultSet, "extra_guest_charge"),
                            decimal(resultSet, "add_on_service_amount"),
                            decimal(resultSet, "additional_fee"),
                            decimal(resultSet, "late_checkout_fee"),
                            decimal(resultSet, "other_revenue"),
                            decimal(resultSet, "discount_amount"),
                            decimal(resultSet, "tax_amount"),
                            decimal(resultSet, "recognized_revenue"),
                            decimal(resultSet, "gross_cash_inflow"),
                            decimal(resultSet, "accepted_cash_inflow"),
                            decimal(resultSet, "refund_outflow"),
                            decimal(resultSet, "net_cash_flow"),
                            resultSet.getString("data_quality"));
                });
        String countSql = """
                SELECT COUNT(*)
                FROM reservation_invoices invoice
                JOIN reservations reservation
                  ON reservation.id = invoice.reservation_id
                %s
                """.formatted(filters);
        Long total = jdbc.queryForObject(countSql, parameters, Long.class);
        long totalElements = total != null ? total : 0L;
        int totalPages = totalElements == 0
                ? 0
                : (int) ((totalElements + size - 1L) / size);
        return new BusinessStatisticsResponse.ReservationRevenuePage(
                content, page, size, totalElements, totalPages);
    }

    public BusinessStatisticsResponse.LedgerPage ledger(
            StatisticsPeriod period,
            String eventType,
            String provider,
            String status,
            int page,
            int size) {
        return ledger(period, eventType, provider, status, null, page, size);
    }

    public BusinessStatisticsResponse.LedgerPage ledger(
            StatisticsPeriod period,
            String eventType,
            String provider,
            String status,
            String query,
            int page,
            int size) {
        String union = """
                SELECT CONCAT('PAYMENT:', payment.id) AS entry_key,
                       'CASH_IN' AS event_type,
                       payment.paid_at_utc AS occurred_at_utc,
                       payment.reservation_id,
                       reservation.reservation_code,
                       COALESCE(payment.provider_reference,
                                payment.provider_txn_id,
                                payment.txn_ref) AS reference,
                       payment.provider,
                       payment.status,
                       COALESCE(payment.received_amount, payment.amount)::numeric
                           AS amount,
                       'IN' AS direction,
                       CASE WHEN payment.received_amount IS NULL
                            THEN 'LEGACY_UNRECONCILED' ELSE 'CANONICAL' END
                           AS data_quality,
                       'Tiền khách thanh toán' AS description
                FROM payment_transactions payment
                JOIN reservations reservation
                  ON reservation.id = payment.reservation_id
                WHERE payment.paid_at_utc >= :fromUtc
                  AND payment.paid_at_utc < :toUtc
                  AND payment.status IN %s
                UNION ALL
                SELECT CONCAT('PROVIDER_IN:', provider_event.id),
                       'UNMATCHED_CASH_IN',
                       COALESCE(provider_event.provider_occurred_at_utc,
                                provider_event.received_at_utc),
                       NULL::bigint,
                       NULL::varchar,
                       COALESCE(provider_event.bank_reference_code,
                                provider_event.provider_reference,
                                provider_event.provider_event_id),
                       provider_event.provider,
                       provider_event.status,
                       provider_event.amount::numeric,
                       'IN',
                       'REVIEW_REQUIRED',
                       'Tiền vào ngân hàng chưa ghép giao dịch thanh toán'
                FROM payment_provider_events provider_event
                WHERE COALESCE(provider_event.provider_occurred_at_utc,
                               provider_event.received_at_utc) >= :fromUtc
                  AND COALESCE(provider_event.provider_occurred_at_utc,
                               provider_event.received_at_utc) < :toUtc
                  AND LOWER(provider_event.transfer_type) = 'in'
                  AND provider_event.amount > 0
                  AND provider_event.payment_transaction_id IS NULL
                  AND provider_event.status IN (
                      'RECEIVED', 'PROCESSING',
                      'FAILED_RETRYABLE', 'REVIEW_REQUIRED')
                UNION ALL
                SELECT CONCAT('REFUND:', refund.id),
                       'REFUND_OUT',
                       refund.completed_at_utc,
                       COALESCE(refund.reservation_id,
                                refund_payment.reservation_id),
                       reservation.reservation_code,
                       COALESCE(refund.manual_transfer_reference,
                                refund.refund_code,
                                refund.request_id),
                       refund.provider,
                       refund.status,
                       COALESCE(refund.actual_refund_amount,
                                refund.requested_amount)::numeric,
                       'OUT',
                       CASE WHEN refund.actual_refund_amount IS NULL
                            THEN 'LEGACY_UNRECONCILED' ELSE 'CANONICAL' END,
                       'Tiền đã hoàn cho khách'
                FROM payment_refunds refund
                LEFT JOIN payment_transactions refund_payment
                  ON refund_payment.id = refund.payment_transaction_id
                LEFT JOIN reservations reservation
                  ON reservation.id = COALESCE(refund.reservation_id,
                                               refund_payment.reservation_id)
                WHERE refund.completed_at_utc >= :fromUtc
                  AND refund.completed_at_utc < :toUtc
                  AND refund.status = 'SUCCEEDED'
                UNION ALL
                SELECT CONCAT('PROVIDER_OUT:', provider_event.id),
                       'UNCLASSIFIED_CASH_OUT',
                       COALESCE(provider_event.provider_occurred_at_utc,
                                provider_event.received_at_utc),
                       NULL::bigint,
                       NULL::varchar,
                       COALESCE(provider_event.bank_reference_code,
                                provider_event.provider_reference,
                                provider_event.provider_event_id),
                       provider_event.provider,
                       provider_event.status,
                       provider_event.amount::numeric,
                       'OUT',
                       'REVIEW_REQUIRED',
                       'Tiền ra ngân hàng chưa ghép refund hoàn tất'
                FROM payment_provider_events provider_event
                WHERE COALESCE(provider_event.provider_occurred_at_utc,
                               provider_event.received_at_utc) >= :fromUtc
                  AND COALESCE(provider_event.provider_occurred_at_utc,
                               provider_event.received_at_utc) < :toUtc
                  AND LOWER(provider_event.transfer_type) = 'out'
                  AND provider_event.amount > 0
                  AND provider_event.status IN (
                      'RECEIVED', 'PROCESSING',
                      'FAILED_RETRYABLE', 'REVIEW_REQUIRED')
                  AND NOT EXISTS (
                        SELECT 1
                        FROM payment_refunds matched_refund
                        WHERE matched_refund.completion_provider_event_id
                                = provider_event.id
                          AND matched_refund.status = 'SUCCEEDED'
                  )
                UNION ALL
                SELECT CONCAT('INVOICE:', invoice.id),
                       'REVENUE_RECOGNIZED',
                       invoice.issued_at_utc,
                       invoice.reservation_id,
                       reservation.reservation_code,
                       invoice.invoice_number,
                       NULL,
                       invoice.settlement_status,
                       invoice.total_amount,
                       'RECOGNIZED',
                       'CANONICAL',
                       'Doanh thu ghi nhận khi checkout'
                FROM reservation_invoices invoice
                JOIN reservations reservation
                  ON reservation.id = invoice.reservation_id
                WHERE invoice.issued_at_utc >= :fromUtc
                  AND invoice.issued_at_utc < :toUtc
                """.formatted(PAID_STATUSES);
        String filters = """
                WHERE (:eventType IS NULL OR ledger.event_type = :eventType)
                  AND (:provider IS NULL OR ledger.provider = :provider)
                  AND (:status IS NULL OR ledger.status = :status)
                  AND (:query IS NULL
                       OR POSITION(:query IN UPPER(COALESCE(
                              ledger.reservation_code, ''))) > 0
                       OR POSITION(:query IN UPPER(COALESCE(
                              ledger.reference, ''))) > 0)
                """;
        MapSqlParameterSource parameters = utcParameters(period)
                .addValue("eventType", normalizeFilter(eventType), Types.VARCHAR)
                .addValue("provider", normalizeFilter(provider), Types.VARCHAR)
                .addValue("status", normalizeFilter(status), Types.VARCHAR)
                .addValue("query", normalizeFilter(query), Types.VARCHAR)
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        String dataSql = """
                WITH ledger AS (%s)
                SELECT ledger.*,
                       ledger.occurred_at_utc AT TIME ZONE '%s'
                           AS occurred_at_local
                FROM ledger
                %s
                ORDER BY ledger.occurred_at_utc DESC, ledger.entry_key DESC
                LIMIT :limit OFFSET :offset
                """.formatted(union, HOTEL_TIMEZONE, filters);
        List<BusinessStatisticsResponse.LedgerEntry> content = jdbc.query(
                dataSql,
                parameters,
                (resultSet, rowNum) -> new BusinessStatisticsResponse.LedgerEntry(
                        resultSet.getString("entry_key"),
                        resultSet.getString("event_type"),
                        instant(resultSet, "occurred_at_utc"),
                        resultSet.getObject("occurred_at_local", LocalDateTime.class),
                        resultSet.getString("reservation_code"),
                        resultSet.getString("reference"),
                        resultSet.getString("provider"),
                        resultSet.getString("status"),
                        decimal(resultSet, "amount"),
                        resultSet.getString("direction"),
                        resultSet.getString("data_quality"),
                        resultSet.getString("description")));
        String countSql = """
                WITH ledger AS (%s)
                SELECT COUNT(*) FROM ledger %s
                """.formatted(union, filters);
        Long total = jdbc.queryForObject(countSql, parameters, Long.class);
        long totalElements = total != null ? total : 0L;
        int totalPages = totalElements == 0
                ? 0
                : (int) ((totalElements + size - 1L) / size);
        return new BusinessStatisticsResponse.LedgerPage(
                content, page, size, totalElements, totalPages);
    }

    private String bucketUtc(String column, String unit) {
        return "date_trunc('%s', %s AT TIME ZONE '%s')::date"
                .formatted(unit, column, HOTEL_TIMEZONE);
    }

    private String bucketLocal(String column, String unit) {
        return "date_trunc('%s', %s)::date".formatted(unit, column);
    }

    private MapSqlParameterSource utcParameters(StatisticsPeriod period) {
        return new MapSqlParameterSource()
                .addValue("fromUtc", Timestamp.from(period.fromUtc()))
                .addValue("toUtc", Timestamp.from(period.toUtcExclusive()));
    }

    private MapSqlParameterSource localParameters(StatisticsPeriod period) {
        return new MapSqlParameterSource()
                .addValue("fromLocal", Timestamp.valueOf(period.fromLocal()))
                .addValue("toLocal", Timestamp.valueOf(period.toLocalExclusive()));
    }

    private String normalizeFilter(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal decimal(ResultSet resultSet, String column)
            throws SQLException {
        BigDecimal value = resultSet.getBigDecimal(column);
        return value != null ? value : BigDecimal.ZERO;
    }

    private LocalDate localDate(ResultSet resultSet, String column)
            throws SQLException {
        Date value = resultSet.getDate(column);
        return value != null ? value.toLocalDate() : null;
    }

    private Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value != null ? value.toInstant() : null;
    }

    public record RevenueRow(
            LocalDate period,
            BigDecimal recognizedRevenue,
            BigDecimal roomRevenue,
            BigDecimal addOnRevenue,
            BigDecimal otherRevenue,
            BigDecimal additionalFee,
            BigDecimal lateCheckoutFee,
            BigDecimal discountAmount,
            BigDecimal taxAmount,
            long invoiceCount,
            BigDecimal grossCashInflow,
            BigDecimal acceptedCashInflow,
            BigDecimal refundOutflow,
            BigDecimal legacyUnreconciledAmount,
            long legacyUnreconciledCount) {
    }

    public record CashFlowRow(
            LocalDate period,
            BigDecimal grossCashInflow,
            BigDecimal acceptedCashInflow,
            BigDecimal unmatchedCashInflow,
            BigDecimal refundOutflow,
            BigDecimal unclassifiedCashOutflow,
            long paymentCount,
            long unmatchedCashInCount,
            long refundCount,
            long unclassifiedCashOutCount,
            BigDecimal legacyUnreconciledAmount,
            long legacyUnreconciledCount) {
    }

    public record MoneyFlowRow(
            LocalDate period,
            BigDecimal cashIncome,
            BigDecimal transferIncome,
            BigDecimal cashRefund,
            BigDecimal transferRefund,
            long paymentCount,
            long refundCount,
            long unmatchedTransferCount,
            BigDecimal unmatchedTransferAmount) {
    }

    public record MoneyWindowRow(
            BigDecimal cashIncome,
            BigDecimal transferIncome,
            BigDecimal cashRefund,
            BigDecimal transferRefund,
            long paymentCount,
            long refundCount) {
    }

    public record BookingRow(
            LocalDate period,
            long total,
            long paymentPending,
            long draft,
            long confirmed,
            long cancellationPending,
            long cancelled,
            long checkedIn,
            long checkedOut,
            long noShow) {
    }

    public record DailyOccupancyRow(
            LocalDate day,
            BigDecimal soldHours,
            BigDecimal availableHours,
            BigDecimal allocatedRoomRevenue) {
    }

    public record RoomTypeRow(
            Long roomTypeId,
            String roomTypeCode,
            String roomTypeName,
            long bookingCount,
            long reservedQuantity,
            BigDecimal soldHours,
            BigDecimal availableHours,
            BigDecimal recognizedRoomRevenue,
            BigDecimal extraGuestRevenue) {
    }

    public record CurrentBalances(
            BigDecimal outstandingReceivables,
            BigDecimal customerDeposits,
            BigDecimal refundPayable) {
    }
}
