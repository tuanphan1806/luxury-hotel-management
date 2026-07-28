package com.hotel.backend.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.hotel.backend.entity.BusinessDayCloseLock;
import com.hotel.backend.repository.BusinessDayCloseLockRepository;
import com.hotel.backend.service.BusinessDayLockService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class FinancialJournalPostgresMigrationIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("hotelmanagement_financial_journal")
                    .withUsername("hotel")
                    .withPassword("hotel");

    @Test
    void journalIsBalancedIdempotentAppendOnlyAndClosedDaysRejectNewPosting() {
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

        BusinessDayCloseLockRepository lockRepository = mock(BusinessDayCloseLockRepository.class);
        LocalDate mutexDate = LocalDate.of(2026, 7, 28);
        when(lockRepository.findByBusinessDateForUpdate(mutexDate)).thenReturn(Optional.of(
                BusinessDayCloseLock.builder()
                        .businessDate(mutexDate)
                        .createdAtUtc(Instant.parse("2026-07-28T00:00:00Z"))
                        .build()));
        BusinessDayLockService lockService = new BusinessDayLockService(jdbc, lockRepository);
        lockService.lock(mutexDate);
        lockService.lock(mutexDate);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_day_close_locks WHERE business_date = DATE '2026-07-28'",
                Long.class)).isEqualTo(1L);

        Long adminId = jdbc.queryForObject("""
                INSERT INTO users(email, email_verified, full_name, status, type, username)
                VALUES ('journal-migration@example.com', TRUE, 'Journal migration',
                        'ACTIVE', 'ADMIN', 'journal-migration')
                RETURNING id
                """, Long.class);

        Long entryId = insertEntry(jdbc, "JE-20260727-000001", "source-1",
                "2026-07-27", "2026-07-27", false);
        jdbc.update("""
                INSERT INTO financial_journal_lines(
                    journal_entry_id, line_number, account_code, direction, amount, description)
                VALUES (?, 1, 'BANK_SEPAY', 'DEBIT', 100000, 'Tiền vào'),
                       (?, 2, 'CUSTOMER_DEPOSIT', 'CREDIT', 100000, 'Tiền khách ứng trước')
                """, entryId, entryId);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM financial_journal_lines WHERE journal_entry_id = ?",
                Long.class, entryId)).isEqualTo(2L);
        assertThatThrownBy(() -> insertEntry(jdbc, "JE-20260727-000002", "source-1",
                "2026-07-27", "2026-07-27", false))
                .hasMessageContaining("uk_financial_journal_source");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE financial_journal_entries SET description = 'changed' WHERE id = ?", entryId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM financial_journal_lines WHERE journal_entry_id = ?", entryId))
                .hasMessageContaining("append-only");

        jdbc.update("""
                INSERT INTO business_day_closes(
                    business_date, status, closed_by, closed_by_name, closed_by_role,
                    closed_at_utc, journal_entry_count, total_debit, total_credit,
                    payment_received_amount, refund_completed_amount,
                    recognized_revenue_amount, pending_refund_payable_amount,
                    cash_variance_amount, summary_json, summary_hash)
                VALUES (DATE '2026-07-27', 'CLOSED', ?, 'Journal migration', 'ADMIN',
                    TIMESTAMPTZ '2026-07-28 01:00:00Z', 1, 100000, 100000,
                    100000, 0, 0, 0, 0, '{}'::jsonb, ?)
                """, adminId, "a".repeat(64));

        assertThatThrownBy(() -> insertEntry(jdbc, "JE-20260727-000003", "source-2",
                "2026-07-27", "2026-07-27", false))
                .hasMessageContaining("business day 2026-07-27 is closed");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO financial_journal_lines(
                    journal_entry_id, line_number, account_code, direction, amount)
                VALUES (?, 3, 'BANK_SEPAY', 'DEBIT', 1)
                """, entryId))
                .hasMessageContaining("business day 2026-07-27 is closed");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE business_day_closes SET note = 'changed' WHERE business_date = DATE '2026-07-27'"))
                .hasMessageContaining("append-only");

        Long lateEntryId = insertEntry(jdbc, "JE-20260728-000001", "source-late",
                "2026-07-28", "2026-07-27", true);
        assertThat(lateEntryId).isPositive();
    }

    private Long insertEntry(
            JdbcTemplate jdbc,
            String entryNumber,
            String sourceId,
            String businessDate,
            String originalBusinessDate,
            boolean latePosting) {
        return jdbc.queryForObject("""
                INSERT INTO financial_journal_entries(
                    entry_number, business_date, original_business_date,
                    occurred_at_utc, posted_at_utc, source_type, source_id,
                    posting_kind, currency, description, late_posting,
                    total_debit, total_credit, detail_json)
                VALUES (?, ?::date, ?::date,
                    TIMESTAMPTZ '2026-07-27 12:00:00Z', CURRENT_TIMESTAMP,
                    'PAYMENT_TRANSACTION', ?, 'PAYMENT_RECEIVED', 'VND',
                    'Migration verification', ?, 100000, 100000, '{}'::jsonb)
                RETURNING id
                """, Long.class, entryNumber, businessDate, originalBusinessDate,
                sourceId, latePosting);
    }
}
