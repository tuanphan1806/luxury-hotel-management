package com.hotel.backend.migration;

import com.hotel.backend.statistics.BusinessStatisticsQueryRepository;
import com.hotel.backend.statistics.StatisticsGranularity;
import com.hotel.backend.statistics.StatisticsPeriod;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class BusinessStatisticsQueryMigrationIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("hotelmanagement_statistics")
                    .withUsername("hotel")
                    .withPassword("hotel");

    @Test
    void reportingQueriesExecuteAgainstCanonicalPostgresSchema() {
        DriverManagerDataSource dataSource = freshDataSource();
        BusinessStatisticsQueryRepository repository =
                new BusinessStatisticsQueryRepository(
                        new NamedParameterJdbcTemplate(dataSource));
        StatisticsPeriod period = StatisticsPeriod.resolve(
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                Clock.systemUTC());

        assertThat(repository.revenue(period, StatisticsGranularity.DAY))
                .isEmpty();
        assertThat(repository.cashFlow(
                period, StatisticsGranularity.DAY, null)).isEmpty();
        assertThat(repository.bookings(period, StatisticsGranularity.DAY))
                .isEmpty();
        assertThat(repository.dailyOccupancy(period)).hasSize(31);
        assertThat(repository.roomTypePerformance(period)).isEmpty();
        assertThat(repository.currentBalances().customerDeposits()).isZero();
        assertThat(repository.reservationRevenue(
                period,
                StatisticsGranularity.DAY,
                null,
                null,
                0,
                20).content()).isEmpty();
        assertThat(repository.ledger(
                period, null, null, null, 0, 25).content()).isEmpty();
    }

    @Test
    void reportingUsesHotelTimezoneCanonicalMoneyAndActualStayWindow() {
        DriverManagerDataSource dataSource = freshDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long customerId = jdbc.queryForObject("""
                INSERT INTO customer_profiles(full_name, source, created_at)
                VALUES ('Test guest', 'WALK_IN', TIMESTAMP '2026-07-09 09:00:00')
                RETURNING id
                """, Long.class);
        Long roomTypeId = jdbc.queryForObject("""
                INSERT INTO room_types(type_name, type_name_en, max_guests,
                                       code, created_at)
                VALUES ('Phòng tiêu chuẩn', 'Standard', 2, 'STANDARD',
                        TIMESTAMP '2026-01-01 00:00:00')
                RETURNING id
                """, Long.class);
        jdbc.update("""
                INSERT INTO rooms(room_name, room_type_id, status, sellable,
                                  created_at, cleaning_status)
                VALUES ('T-101', ?, 'AVAILABLE', TRUE,
                        TIMESTAMP '2026-01-01 00:00:00', 'CLEAN')
                """, roomTypeId);
        Long reservationId = jdbc.queryForObject("""
                INSERT INTO reservations(
                    created_at, check_in, check_out, actual_check_in,
                    actual_check_out, discount_amount, late_checkout_fee,
                    reservation_code, status, tax_amount, total_amount,
                    required_initial_payment, customer_profile_id,
                    cancellation_fee, refundable_amount,
                    early_checkout_adjustment, checkout_additional_fee,
                    guest_count, pricing_version)
                VALUES (
                    TIMESTAMP '2026-07-09 16:00:00',
                    TIMESTAMP '2026-07-10 18:00:00',
                    TIMESTAMP '2026-07-11 08:00:00',
                    TIMESTAMP '2026-07-10 22:00:00',
                    TIMESTAMP '2026-07-11 08:00:00',
                    0, 0, 'RES-STATS-1', 'CHECKED_OUT', 0, 170000,
                    85000, ?, 0, 0, 0, 0, 1, 'MOTEL_PACKAGE_V2')
                RETURNING id
                """, Long.class, customerId);
        jdbc.update("""
                INSERT INTO reservation_room_types(
                    quantity, room_price, subtotal, reservation_id,
                    room_type_id, created_at)
                VALUES (1, 170000, 170000, ?, ?,
                        TIMESTAMP '2026-07-09 16:00:00')
                """, reservationId, roomTypeId);
        jdbc.update("""
                INSERT INTO payment_transactions(
                    id, amount, expected_amount, received_amount,
                    accepted_amount, refund_required_amount,
                    currency, provider, status, txn_ref,
                    reservation_id, purpose, paid_at_utc, created_at)
                VALUES ('PAY-STATS-1', 200000, 170000, 200000, 170000, 40000,
                        'VND', 'SEPAY', 'REFUND_PENDING', 'TXN-STATS-1', ?,
                        'FINAL_PAYMENT', ?, TIMESTAMP '2026-07-09 16:00:00')
                """, reservationId,
                Timestamp.from(Instant.parse("2026-07-09T17:30:00Z")));
        jdbc.update("""
                INSERT INTO payment_provider_events(
                    id, provider, provider_event_id, provider_reference,
                    bank_reference_code, dedup_key, status, payload_hash,
                    transfer_type, amount, provider_occurred_at_utc,
                    received_at_utc, created_at)
                VALUES
                    ('EVT-STATS-IN-UNMATCHED', 'SEPAY', 'SEPAY-STATS-IN-1',
                     'PROVIDER-STATS-IN-1', 'BANK-STATS-IN-1',
                     'DEDUP-STATS-IN-1', 'REVIEW_REQUIRED', repeat('a', 64),
                     'in', 45000, TIMESTAMPTZ '2026-07-10 03:00:00Z',
                     TIMESTAMPTZ '2026-07-10 03:00:05Z',
                     TIMESTAMP '2026-07-10 10:00:05'),
                    ('EVT-STATS-OUT-MATCHED', 'SEPAY', 'SEPAY-STATS-OUT-1',
                     'PROVIDER-STATS-OUT-1', 'BANK-STATS-OUT-1',
                     'DEDUP-STATS-OUT-1', 'PROCESSED', repeat('b', 64),
                     'out', 30000, TIMESTAMPTZ '2026-07-10 18:00:00Z',
                     TIMESTAMPTZ '2026-07-10 18:00:05Z',
                     TIMESTAMP '2026-07-11 01:00:05'),
                    ('EVT-STATS-OUT-UNCLASSIFIED', 'SEPAY', 'SEPAY-STATS-OUT-2',
                     'PROVIDER-STATS-OUT-2', 'BANK-STATS-OUT-2',
                     'DEDUP-STATS-OUT-2', 'REVIEW_REQUIRED', repeat('c', 64),
                     'out', 5000, TIMESTAMPTZ '2026-07-10 19:00:00Z',
                     TIMESTAMPTZ '2026-07-10 19:00:05Z',
                     TIMESTAMP '2026-07-11 02:00:05')
                """);
        jdbc.update("""
                INSERT INTO payment_refunds(
                    id, reservation_id, completion_provider_event_id,
                    source_type, source_key, provider,
                    channel, status, amount, requested_amount,
                    actual_refund_amount, request_id, refund_code,
                    completed_at_utc, created_at)
                VALUES ('REF-STATS-1', ?, 'EVT-STATS-OUT-MATCHED',
                        'OVERPAYMENT', 'STATS-OVERPAY-1',
                        'SEPAY', 'MANUAL_BANK_TRANSFER', 'SUCCEEDED', 30000, 30000,
                        30000, 'REQ-STATS-1', 'RF-STATS-1', ?,
                        TIMESTAMP '2026-07-10 18:00:00')
                """, reservationId,
                Timestamp.from(Instant.parse("2026-07-10T18:00:00Z")));
        jdbc.update("""
                INSERT INTO payment_refunds(
                    id, payment_transaction_id, source_type, source_key,
                    provider, channel, status, amount, requested_amount,
                    request_id, refund_code, completed_at_utc, created_at)
                VALUES ('REF-STATS-LEGACY', 'PAY-STATS-1', 'LEGACY',
                        'STATS-LEGACY-SUCCEEDED-1', 'SEPAY',
                        'MANUAL_BANK_TRANSFER', 'SUCCEEDED', 5000, 5000,
                        'REQ-STATS-LEGACY', 'RF-STATS-LEGACY', ?,
                        TIMESTAMP '2026-07-10 18:10:00')
                """, Timestamp.from(Instant.parse("2026-07-10T18:10:00Z")));
        jdbc.update("""
                INSERT INTO payment_refunds(
                    id, reservation_id, payment_transaction_id,
                    source_type, source_key, provider, channel, status,
                    amount, requested_amount, request_id, refund_code,
                    created_at)
                VALUES ('REF-STATS-CANCELLED', ?, 'PAY-STATS-1',
                        'UNACCEPTED_PAYMENT', 'STATS-REQUIRED-CANCELLED-1',
                        'SEPAY', 'MANUAL_BANK_TRANSFER', 'CANCELLED',
                        40000, 40000, 'REQ-STATS-CANCELLED',
                        'RF-STATS-CANCELLED',
                        TIMESTAMP '2026-07-10 18:30:00')
                """, reservationId);
        jdbc.update("""
                INSERT INTO reservation_invoices(
                    created_at, created_at_utc, invoice_number, issued_at,
                    issued_at_utc, snapshot_json, total_amount, currency,
                    room_charge, actual_room_charge, planned_room_charge,
                    settlement_status, reservation_id)
                VALUES (
                    TIMESTAMP '2026-07-11 09:00:00', ?, 'INV-STATS-1',
                    TIMESTAMP '2026-07-11 09:00:00', ?,
                    $${"roomTypes":[{"roomTypeCode":"STANDARD",
                    "actualRoomCharge":170000,"extraGuestCharge":0}]}$$,
                    170000, 'VND', 170000, 170000, 170000, 'PAID', ?)
                """,
                Timestamp.from(Instant.parse("2026-07-11T02:00:00Z")),
                Timestamp.from(Instant.parse("2026-07-11T02:00:00Z")),
                reservationId);

        Long openReservationId = jdbc.queryForObject("""
                INSERT INTO reservations(
                    created_at, check_in, check_out, discount_amount,
                    late_checkout_fee, reservation_code, status, tax_amount,
                    total_amount, required_initial_payment, customer_profile_id,
                    cancellation_fee, refundable_amount,
                    early_checkout_adjustment, checkout_additional_fee,
                    guest_count, pricing_version)
                VALUES (TIMESTAMP '2026-06-01 09:00:00',
                        TIMESTAMP '2026-06-02 20:00:00',
                        TIMESTAMP '2026-06-03 08:00:00', 0, 0,
                        'RES-STATS-OPEN', 'DRAFT', 0, 100000, 50000, ?,
                        0, 0, 0, 0, 1, 'MOTEL_PACKAGE_V2')
                RETURNING id
                """, Long.class, customerId);
        jdbc.update("""
                INSERT INTO payment_transactions(
                    id, amount, expected_amount, received_amount,
                    accepted_amount, refund_required_amount, currency,
                    provider, status, txn_ref, reservation_id, purpose,
                    paid_at_utc, created_at)
                VALUES ('PAY-STATS-OPEN', 60000, 60000, 60000, 60000, 0,
                        'VND', 'SEPAY', 'SUCCESS', 'TXN-STATS-OPEN', ?,
                        'DEPOSIT', TIMESTAMPTZ '2026-06-01 03:00:00Z',
                        TIMESTAMP '2026-06-01 10:00:00')
                """, openReservationId);
        jdbc.update("""
                INSERT INTO payment_refunds(
                    id, payment_transaction_id, source_type, source_key,
                    provider, channel, status, amount, requested_amount,
                    request_id, refund_code, completed_at_utc, created_at)
                VALUES ('REF-STATS-OPEN-LEGACY', 'PAY-STATS-OPEN', 'LEGACY',
                        'STATS-OPEN-LEGACY-SUCCEEDED', 'SEPAY',
                        'MANUAL_BANK_TRANSFER', 'SUCCEEDED', 10000, 10000,
                        'REQ-STATS-OPEN', 'RF-STATS-OPEN',
                        TIMESTAMPTZ '2026-06-01 04:00:00Z',
                        TIMESTAMP '2026-06-01 11:00:00')
                """);

        BusinessStatisticsQueryRepository repository =
                new BusinessStatisticsQueryRepository(
                        new NamedParameterJdbcTemplate(dataSource));
        StatisticsPeriod period = StatisticsPeriod.resolve(
                LocalDate.of(2026, 7, 9),
                LocalDate.of(2026, 7, 12),
                Clock.fixed(Instant.parse("2026-07-12T05:00:00Z"),
                        java.time.ZoneOffset.UTC));

        List<BusinessStatisticsQueryRepository.RevenueRow> revenue =
                repository.revenue(period, StatisticsGranularity.DAY);
        assertThat(revenue).extracting(
                        BusinessStatisticsQueryRepository.RevenueRow::period)
                .containsExactly(
                        LocalDate.of(2026, 7, 10),
                        LocalDate.of(2026, 7, 11));
        assertThat(revenue.get(0).grossCashInflow())
                .isEqualByComparingTo("200000");
        assertThat(revenue.get(0).acceptedCashInflow())
                .isEqualByComparingTo("170000");
        assertThat(revenue.get(1).recognizedRevenue())
                .isEqualByComparingTo("170000");
        assertThat(revenue.get(1).invoiceCount()).isEqualTo(1);
        assertThat(revenue.get(1).refundOutflow())
                .isEqualByComparingTo("35000");

        List<BusinessStatisticsQueryRepository.CashFlowRow> cashFlow =
                repository.cashFlow(
                        period, StatisticsGranularity.DAY, "sepay");
        assertThat(cashFlow).extracting(
                        BusinessStatisticsQueryRepository.CashFlowRow::period)
                .containsExactly(
                        LocalDate.of(2026, 7, 10),
                        LocalDate.of(2026, 7, 11));
        assertThat(cashFlow.get(0).grossCashInflow())
                .isEqualByComparingTo("245000");
        assertThat(cashFlow.get(0).acceptedCashInflow())
                .isEqualByComparingTo("170000");
        assertThat(cashFlow.get(0).paymentCount()).isEqualTo(1);
        assertThat(cashFlow.get(0).unmatchedCashInflow())
                .isEqualByComparingTo("45000");
        assertThat(cashFlow.get(0).unmatchedCashInCount()).isEqualTo(1);
        assertThat(cashFlow.get(1).refundOutflow())
                .isEqualByComparingTo("35000");
        assertThat(cashFlow.get(1).refundCount()).isEqualTo(2);
        assertThat(cashFlow.get(1).unclassifiedCashOutflow())
                .isEqualByComparingTo("5000");
        assertThat(cashFlow.get(1).unclassifiedCashOutCount()).isEqualTo(1);
        assertThat(repository.cashFlow(
                period, StatisticsGranularity.DAY, "CASH")).isEmpty();

        List<BusinessStatisticsQueryRepository.DailyOccupancyRow> occupancy =
                repository.dailyOccupancy(period);
        assertThat(occupancy.stream()
                .filter(row -> row.day().equals(LocalDate.of(2026, 7, 10)))
                .findFirst().orElseThrow().soldHours())
                .isEqualByComparingTo("2");
        assertThat(occupancy.stream()
                .filter(row -> row.day().equals(LocalDate.of(2026, 7, 11)))
                .findFirst().orElseThrow().soldHours())
                .isEqualByComparingTo("8");

        assertThat(repository.roomTypePerformance(period))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.soldHours()).isEqualByComparingTo("10");
                    assertThat(row.recognizedRoomRevenue())
                            .isEqualByComparingTo("170000");
                });
        StatisticsPeriod firstStayDay = StatisticsPeriod.resolve(
                LocalDate.of(2026, 7, 10),
                LocalDate.of(2026, 7, 10),
                Clock.fixed(Instant.parse("2026-07-12T05:00:00Z"),
                        java.time.ZoneOffset.UTC));
        assertThat(repository.roomTypePerformance(firstStayDay))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.soldHours()).isEqualByComparingTo("2");
                    assertThat(row.recognizedRoomRevenue())
                            .isEqualByComparingTo("34000");
                });
        assertThat(repository.currentBalances().customerDeposits())
                .isEqualByComparingTo("50000");
        assertThat(repository.currentBalances().refundPayable())
                .isEqualByComparingTo("40000");
        assertThat(repository.reservationRevenue(
                period,
                StatisticsGranularity.DAY,
                "stats-1",
                "checked_out",
                0,
                20).content())
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.period())
                            .isEqualTo(LocalDate.of(2026, 7, 11));
                    assertThat(entry.reservationCode())
                            .isEqualTo("RES-STATS-1");
                    assertThat(entry.invoiceNumber())
                            .isEqualTo("INV-STATS-1");
                    assertThat(entry.recognizedRevenue())
                            .isEqualByComparingTo("170000");
                    assertThat(entry.grossCashInflow())
                            .isEqualByComparingTo("200000");
                    assertThat(entry.acceptedCashInflow())
                            .isEqualByComparingTo("170000");
                    assertThat(entry.refundOutflow())
                            .isEqualByComparingTo("35000");
                    assertThat(entry.netCashFlow())
                            .isEqualByComparingTo("165000");
                    assertThat(entry.dataQuality())
                            .isEqualTo("LEGACY_UNRECONCILED");
                });
        assertThat(repository.reservationRevenue(
                period,
                StatisticsGranularity.MONTH,
                "missing",
                null,
                0,
                20).content()).isEmpty();
        assertThat(repository.ledger(
                period, null, null, null, 0, 25).content()).hasSize(6);
        assertThat(repository.ledger(
                period, "REFUND_OUT", "SEPAY", "SUCCEEDED",
                "rf-stats-legacy", 0, 25).content())
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.reservationCode())
                            .isEqualTo("RES-STATS-1");
                    assertThat(entry.amount()).isEqualByComparingTo("5000");
                    assertThat(entry.dataQuality())
                            .isEqualTo("LEGACY_UNRECONCILED");
                });
        assertThat(repository.ledger(
                period, null, null, null, "txn-stats-1", 0, 25)
                .content()).singleElement();
        assertThat(repository.ledger(
                period, "CASH_IN", "SEPAY", "REFUND_PENDING", 0, 25)
                .content()).singleElement();
        assertThat(repository.ledger(
                period, "UNMATCHED_CASH_IN", "SEPAY", "REVIEW_REQUIRED",
                0, 25).content()).singleElement();
        assertThat(repository.ledger(
                period, "UNCLASSIFIED_CASH_OUT", "SEPAY", "REVIEW_REQUIRED",
                0, 25).content()).singleElement();
    }

    private DriverManagerDataSource freshDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration-postgres")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        return dataSource;
    }
}
