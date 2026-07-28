package com.hotel.backend.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class CashierShiftPostgresMigrationIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("hotelmanagement_cashier_shift")
                    .withUsername("hotel")
                    .withPassword("hotel");

    @Test
    void shiftsAreUniquePerOperatorAndCashLedgerIsAppendOnly() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration-postgres")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Long userId = jdbc.queryForObject("""
                INSERT INTO users(email, email_verified, full_name, status, type, username)
                VALUES ('cashier-migration@example.com', TRUE, 'Cashier migration',
                        'ACTIVE', 'STAFF', 'cashier-migration')
                RETURNING id
                """, Long.class);
        Long shiftId = jdbc.queryForObject("""
                INSERT INTO cashier_shifts(
                    shift_code, business_date, status, opened_by,
                    opened_by_name, opened_by_role, opened_at_utc,
                    opening_cash_amount)
                VALUES ('CS-MIGRATION-1', DATE '2026-07-28', 'OPEN', ?,
                        'Cashier migration', 'STAFF',
                        TIMESTAMPTZ '2026-07-28 01:00:00Z', 500000)
                RETURNING id
                """, Long.class, userId);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO cashier_shifts(
                    shift_code, business_date, status, opened_by,
                    opened_by_name, opened_by_role, opened_at_utc,
                    opening_cash_amount)
                VALUES ('CS-MIGRATION-2', DATE '2026-07-28', 'OPEN', ?,
                        'Cashier migration', 'STAFF',
                        TIMESTAMPTZ '2026-07-28 02:00:00Z', 0)
                """, userId))
                .hasMessageContaining("uk_cashier_shift_active_user");

        Long movementId = jdbc.queryForObject("""
                INSERT INTO cash_movements(
                    cashier_shift_id, movement_type, direction, amount,
                    source_type, source_id, created_by, created_by_name,
                    created_by_role, reason, occurred_at_utc)
                VALUES (?, 'OPENING_FLOAT', 'IN', 500000,
                        'CASHIER_SHIFT', 'CS-MIGRATION-1', ?,
                        'Cashier migration', 'STAFF', 'Tiền đầu ca',
                        TIMESTAMPTZ '2026-07-28 01:00:00Z')
                RETURNING id
                """, Long.class, shiftId, userId);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE cash_movements SET amount = 1 WHERE id = ?", movementId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM cash_movements WHERE id = ?", movementId))
                .hasMessageContaining("append-only");

        assertThat(jdbc.update("""
                UPDATE cashier_shifts
                SET status = 'CLOSED', expected_cash_amount = 500000,
                    counted_cash_amount = 500000, variance_amount = 0,
                    closed_by = ?, closed_by_name = 'Cashier migration',
                    closed_by_role = 'STAFF',
                    closed_at_utc = TIMESTAMPTZ '2026-07-28 09:00:00Z'
                WHERE id = ?
                """, userId, shiftId)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE cashier_shifts SET close_note = 'changed' WHERE id = ?", shiftId))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM cashier_shifts WHERE id = ?", shiftId))
                .hasMessageContaining("cannot be deleted");
    }
}
