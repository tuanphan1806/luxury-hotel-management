package com.hotel.backend.migration;

import com.hotel.backend.constant.FinancialAccountCode;
import com.hotel.backend.constant.FinancialEntryDirection;
import com.hotel.backend.repository.CashMovementRepository;
import com.hotel.backend.repository.CashierShiftRepository;
import com.hotel.backend.repository.FinancialJournalEntryRepository;
import com.hotel.backend.repository.FinancialJournalLineRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration-postgres",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AccountingRepositoryPostgresMigrationIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("hotelmanagement_accounting_repository")
                    .withUsername("hotel")
                    .withPassword("hotel");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CashMovementRepository movementRepository;
    @Autowired CashierShiftRepository shiftRepository;
    @Autowired FinancialJournalEntryRepository entryRepository;
    @Autowired FinancialJournalLineRepository lineRepository;

    @Test
    void accountingAggregatesAndNativeShiftProjectionRunOnPostgres() {
        Long userId = jdbc.queryForObject("""
                INSERT INTO users(email, email_verified, full_name, status, type, username)
                VALUES ('accounting-query@example.com', TRUE, 'Accounting query',
                        'ACTIVE', 'STAFF', 'accounting-query')
                RETURNING id
                """, Long.class);
        Long shiftId = jdbc.queryForObject("""
                INSERT INTO cashier_shifts(
                    shift_code, business_date, status, opened_by,
                    opened_by_name, opened_by_role, opened_at_utc,
                    opening_cash_amount, variance_amount)
                VALUES ('CS-QUERY-1', DATE '2026-07-28', 'OPEN', ?,
                        'Accounting query', 'STAFF',
                        TIMESTAMPTZ '2026-07-28 01:00:00Z', 0, 2500)
                RETURNING id
                """, Long.class, userId);
        jdbc.update("""
                INSERT INTO cash_movements(
                    cashier_shift_id, movement_type, direction, amount,
                    source_type, source_id, created_by, created_by_name,
                    created_by_role, reason, occurred_at_utc)
                VALUES (?, 'CASH_IN', 'IN', 100000, 'MANUAL', 'query-in', ?,
                            'Accounting query', 'STAFF', 'Thu thử',
                            TIMESTAMPTZ '2026-07-28 02:00:00Z'),
                       (?, 'CASH_OUT', 'OUT', 30000, 'MANUAL', 'query-out', ?,
                            'Accounting query', 'STAFF', 'Chi thử',
                            TIMESTAMPTZ '2026-07-28 03:00:00Z')
                """, shiftId, userId, shiftId, userId);

        Long entryId = jdbc.queryForObject("""
                INSERT INTO financial_journal_entries(
                    entry_number, business_date, original_business_date,
                    occurred_at_utc, posted_at_utc, source_type, source_id,
                    posting_kind, currency, description, late_posting,
                    total_debit, total_credit, detail_json)
                VALUES ('JE-QUERY-1', DATE '2026-07-28', DATE '2026-07-28',
                    TIMESTAMPTZ '2026-07-28 02:00:00Z',
                    TIMESTAMPTZ '2026-07-28 02:00:01Z',
                    'PAYMENT_PROVIDER_EVENT', 'query-event',
                    'PROVIDER_CASH_OBSERVED', 'VND', 'Query verification',
                    FALSE, 100000, 100000, '{}'::jsonb)
                RETURNING id
                """, Long.class);
        jdbc.update("""
                INSERT INTO financial_journal_lines(
                    journal_entry_id, line_number, account_code,
                    direction, amount, description)
                VALUES (?, 1, 'BANK_SEPAY', 'DEBIT', 100000, 'Tiền vào'),
                       (?, 2, 'UNRECONCILED_FUNDS', 'CREDIT', 100000,
                            'Chờ phân loại')
                """, entryId, entryId);

        var shiftSummary = movementRepository.summarizeByCashierShiftIds(List.of(shiftId));
        assertThat(shiftSummary).hasSize(1);
        assertThat(shiftSummary.get(0).getMovementCount()).isEqualTo(2L);
        assertThat(shiftSummary.get(0).getExpectedCash())
                .isEqualByComparingTo(BigDecimal.valueOf(70_000L));
        assertThat(shiftRepository.sumVarianceByBusinessDate(LocalDate.of(2026, 7, 28)))
                .isEqualByComparingTo(BigDecimal.valueOf(2_500L));

        var journalSummary = entryRepository.summarizeBusinessDate(LocalDate.of(2026, 7, 28));
        assertThat(journalSummary.getEntryCount()).isEqualTo(1L);
        assertThat(journalSummary.getTotalDebit())
                .isEqualByComparingTo(BigDecimal.valueOf(100_000L));
        assertThat(journalSummary.getTotalCredit())
                .isEqualByComparingTo(BigDecimal.valueOf(100_000L));
        assertThat(journalSummary.getUnbalancedCount()).isZero();

        var accountTotals = lineRepository.summarizeAccounts(LocalDate.of(2026, 7, 28));
        assertThat(accountTotals).anySatisfy(total -> {
            assertThat(total.getAccountCode()).isEqualTo(FinancialAccountCode.BANK_SEPAY);
            assertThat(total.getDirection()).isEqualTo(FinancialEntryDirection.DEBIT);
            assertThat(total.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(100_000L));
        });
    }
}
