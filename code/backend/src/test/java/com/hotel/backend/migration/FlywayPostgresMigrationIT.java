package com.hotel.backend.migration;

import com.hotel.backend.entity.IdempotencyRequest;
import com.hotel.backend.entity.ReservationInvoice;
import jakarta.persistence.Column;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migration gate for a clean PostgreSQL database. Production starts from the
 * consolidated PostgreSQL V1 baseline and applies the PostgreSQL-only history.
 */
@Testcontainers
class FlywayPostgresMigrationIT {

    private static final String LATEST_VERSION = "5";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hotelmanagement_migration")
            .withUsername("hotel")
            .withPassword("hotel");

    @Test
    void cleanPostgresDatabaseMigratesAndMatchesHibernateSchema() throws Exception {
        Flyway flyway = flyway();
        flyway.clean();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion())
                .isEqualTo(LATEST_VERSION);

        try (Connection connection = POSTGRES.createConnection("")) {
            assertTableExists(connection, "reservations");
            assertTableExists(connection, "payment_provider_events");
            assertTableExists(connection, "payment_refunds");
            assertTableExists(connection, "checkout_reconciliation_requests");
            assertTableExists(connection, "audit_notification_outbox");
            assertColumn(connection, "payment_provider_events", "bank_reference_code");
            assertColumn(connection, "payment_refunds", "completion_provider_event_id");
            assertColumn(connection, "payment_refunds", "refund_detail_json");
            assertColumn(connection, "reservation_audit_logs", "detail_json");
            assertColumn(connection, "reservation_audit_logs", "risk_level");
            assertColumnType(connection, "idempotency_requests", "request_hash", "character", 64);
            assertColumnType(connection, "reservation_invoices", "currency", "character", 3);
            assertColumnType(connection, "payment_provider_events", "provider_occurred_at_utc",
                    "timestamp with time zone", null);
            assertColumnType(connection, "reservations", "check_in",
                    "timestamp without time zone", null);
            assertIdentityColumn(connection, "rooms", "id");
            assertIndex(connection, "idx_reservations_status_checkin");
            assertIndex(connection, "idx_payment_transactions_reservation_purpose_status");
            assertIndex(connection, "idx_audit_target_occurred");
            assertIndex(connection, "idx_audit_correlation_id");
            assertIndex(connection, "idx_checkout_reconciliation_pending");
            assertIndex(connection, "idx_audit_notification_due");
            assertConstraint(connection, "chk_reservations_date_range");
            assertConstraint(connection, "chk_payment_refunds_amounts_nonnegative");
            assertColumnDefault(connection, "rooms", "sellable", "true");
        }

        assertCanonicalFixedWidthMappings();
        assertHibernateSchemaValidation();
    }

    @Test
    void existingV3DataMigratesToV4WithoutRewriteOrLoss() throws Exception {
        Flyway v3 = flyway("3");
        v3.clean();
        v3.migrate();

        long legacyAuditId;
        try (Connection connection = POSTGRES.createConnection("");
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO reservation_audit_logs (
                         action, action_code, actor_name, actor_role, details,
                         metadata_json, occurred_at_utc
                     ) VALUES ('CANCEL', 'CANCEL', 'legacy-operator', 'STAFF',
                         'legacy detail', '{"source":"v3"}', CURRENT_TIMESTAMP)
                     RETURNING id
                     """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                legacyAuditId = resultSet.getLong(1);
            }
        }

        Flyway latest = flyway();
        latest.migrate();

        try (Connection connection = POSTGRES.createConnection("");
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT actor_name, details, metadata_json, risk_level,
                            old_value_json, new_value_json, detail_json
                     FROM reservation_audit_logs WHERE id = ?
                     """)) {
            statement.setLong(1, legacyAuditId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("actor_name")).isEqualTo("legacy-operator");
                assertThat(resultSet.getString("details")).isEqualTo("legacy detail");
                assertThat(resultSet.getString("metadata_json")).contains("v3");
                assertThat(resultSet.getString("risk_level")).isEqualTo("NORMAL");
                assertThat(resultSet.getObject("old_value_json")).isNull();
                assertThat(resultSet.getObject("new_value_json")).isNull();
                assertThat(resultSet.getObject("detail_json")).isNull();
            }
        }
    }

    @Test
    void auditTrailRejectsUpdateDeleteAndTruncate() throws Exception {
        Flyway flyway = flyway();
        flyway.clean();
        flyway.migrate();

        long auditId;
        try (Connection connection = POSTGRES.createConnection("");
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO reservation_audit_logs (
                         action, action_code, actor_name, actor_role, details,
                         occurred_at_utc
                     ) VALUES ('CANCEL', 'CANCEL', 'test-admin', 'ADMIN',
                         'immutable evidence', CURRENT_TIMESTAMP)
                     RETURNING id
                     """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                auditId = resultSet.getLong(1);
            }
        }

        assertThatThrownBy(() -> executeAuditMutation(
                "UPDATE reservation_audit_logs SET details = 'tampered' WHERE id = " + auditId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> executeAuditMutation(
                "DELETE FROM reservation_audit_logs WHERE id = " + auditId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> executeAuditMutation("TRUNCATE reservation_audit_logs CASCADE"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");

        try (Connection connection = POSTGRES.createConnection("");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT details FROM reservation_audit_logs WHERE id = ?")) {
            statement.setLong(1, auditId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("immutable evidence");
            }
        }
    }

    private void executeAuditMutation(String sql) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    @Test
    void postCutoverFinalizationReseedsImportedIdentityValues() throws Exception {
        Flyway flyway = flyway();
        flyway.clean();
        flyway.migrate();

        try (Connection connection = POSTGRES.createConnection("")) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO room_types (id, type_name, max_guests)
                    VALUES (?, ?, ?)
                    """)) {
                statement.setLong(1, 900_000L);
                statement.setString(2, "Imported legacy room type");
                statement.setInt(3, 2);
                statement.executeUpdate();
            }

            String finalizationSql = Files.readString(
                    Path.of("db", "postgres", "post-cutover-finalize.sql"),
                    StandardCharsets.UTF_8);
            try (var statement = connection.createStatement()) {
                statement.execute(finalizationSql);
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO room_types (type_name, max_guests)
                    VALUES (?, ?)
                    RETURNING id
                    """)) {
                statement.setString(1, "First PostgreSQL-created room type");
                statement.setInt(2, 2);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getLong(1)).isEqualTo(900_001L);
                }
            }
        }
    }

    private Flyway flyway() {
        return flyway(null);
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration-postgres")
                .cleanDisabled(false)
                .baselineOnMigrate(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private void assertHibernateSchemaValidation() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        LocalContainerEntityManagerFactoryBean entityManagerFactory =
                new LocalContainerEntityManagerFactoryBean();
        entityManagerFactory.setDataSource(dataSource);
        entityManagerFactory.setPackagesToScan("com.hotel.backend.entity");
        entityManagerFactory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());

        Properties properties = new Properties();
        properties.setProperty("hibernate.hbm2ddl.auto", "validate");
        properties.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        properties.setProperty("hibernate.jdbc.time_zone", "UTC");
        properties.setProperty(
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        entityManagerFactory.setJpaProperties(properties);

        try {
            entityManagerFactory.afterPropertiesSet();
        } finally {
            entityManagerFactory.destroy();
        }
    }

    private void assertCanonicalFixedWidthMappings() throws NoSuchFieldException {
        assertColumnDefinition(IdempotencyRequest.class, "requestHash", "CHAR(64)");
        assertColumnDefinition(ReservationInvoice.class, "currency", "CHAR(3)");
        assertColumnDefinition(ReservationInvoice.class, "snapshotHash", "CHAR(64)");
    }

    private void assertTableExists(Connection connection, String tableName) throws SQLException {
        String sql = """
                SELECT 1 FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("table %s exists", tableName).isTrue();
            }
        }
    }

    private void assertColumn(Connection connection, String tableName, String columnName)
            throws SQLException {
        String sql = """
                SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next())
                        .as("column %s.%s exists", tableName, columnName)
                        .isTrue();
            }
        }
    }

    private void assertColumnType(Connection connection, String tableName, String columnName,
                                  String expectedType, Integer expectedLength) throws SQLException {
        String sql = """
                SELECT data_type, character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("column %s.%s exists", tableName, columnName).isTrue();
                assertThat(resultSet.getString("data_type")).isEqualToIgnoringCase(expectedType);
                if (expectedLength != null) {
                    assertThat(resultSet.getInt("character_maximum_length")).isEqualTo(expectedLength);
                }
            }
        }
    }

    private void assertColumnDefault(Connection connection, String tableName, String columnName,
                                     String expectedDefault) throws SQLException {
        String sql = """
                SELECT column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("column %s.%s exists", tableName, columnName).isTrue();
                assertThat(resultSet.getString("column_default"))
                        .isEqualToIgnoringCase(expectedDefault);
            }
        }
    }

    private void assertIdentityColumn(Connection connection, String tableName, String columnName)
            throws SQLException {
        String sql = """
                SELECT is_identity FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("YES");
            }
        }
    }

    private void assertIndex(Connection connection, String indexName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?")) {
            statement.setString(1, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("index %s exists", indexName).isTrue();
            }
        }
    }

    private void assertConstraint(Connection connection, String constraintName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pg_constraint WHERE connamespace = 'public'::regnamespace AND conname = ?")) {
            statement.setString(1, constraintName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("constraint %s exists", constraintName).isTrue();
            }
        }
    }

    private void assertColumnDefinition(Class<?> entityClass, String fieldName,
                                        String expectedDefinition) throws NoSuchFieldException {
        Column column = entityClass.getDeclaredField(fieldName).getAnnotation(Column.class);
        assertThat(column)
                .as("@Column exists on %s.%s", entityClass.getSimpleName(), fieldName)
                .isNotNull();
        assertThat(column.columnDefinition()).isEqualToIgnoringCase(expectedDefinition);
    }
}
