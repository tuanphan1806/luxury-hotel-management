package com.hotel.backend.migration;

import com.hotel.backend.entity.IdempotencyRequest;
import com.hotel.backend.entity.PricingQuote;
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

    private static final String LATEST_VERSION = "25";

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
            assertTableExists(connection, "oauth_login_tickets");
            assertTableExists(connection, "oauth_profile_completion_tickets");
            assertTableExists(connection, "facility_images");
            assertTableExists(connection, "room_type_images");
            assertTableExists(connection, "service_catalog");
            assertTableExists(connection, "reservation_services");
            assertTableExists(connection, "stay_policy_versions");
            assertTableExists(connection, "room_rate_profiles");
            assertTableExists(connection, "reservation_rate_snapshots");
            assertTableExists(connection, "pricing_quotes");
            assertTableExists(connection, "pricing_quote_lines");
            assertTableExists(connection, "pricing_quote_commitments");
            assertTableExists(connection, "cashier_shifts");
            assertTableExists(connection, "cash_movements");
            assertColumn(connection, "room_types", "code");
            assertColumn(connection, "reservations", "pricing_version");
            assertColumn(connection, "reservations", "display_package_summary");
            assertColumn(connection, "reservations", "inventory_protected_until");
            assertColumn(connection, "reservation_room_types", "line_guest_count");
            assertColumn(connection, "reservation_room_types", "minimum_committed_room_charge");
            assertColumn(connection, "reservation_room_types", "max_package_reached");
            assertColumn(connection, "stay_policy_versions", "inventory_protection_mode");
            assertColumn(connection, "stay_policy_versions",
                    "early_morning_overnight_minimum_minutes");
            assertColumn(connection, "stay_policy_versions",
                    "remainder_cycle_starts_at_boundary");
            assertColumn(connection, "payment_provider_events", "bank_reference_code");
            assertColumn(connection, "payment_refunds", "completion_provider_event_id");
            assertColumn(connection, "payment_refunds", "refund_detail_json");
            assertColumnAbsent(connection, "payment_transactions", "requested_bank_code");
            assertColumnAbsent(connection, "payment_transactions", "provider_create_date");
            assertColumnAbsent(connection, "payment_transactions", "card_token");
            assertColumnAbsent(connection, "payment_refunds", "request_history");
            assertColumnAbsent(connection, "payment_refunds", "transaction_type");
            assertColumnAbsent(connection, "payment_refunds", "original_transaction_date");
            assertColumn(connection, "reservation_audit_logs", "detail_json");
            assertColumn(connection, "reservation_audit_logs", "risk_level");
            assertColumn(connection, "oauth_login_tickets", "token_hash");
            assertColumn(connection, "oauth_login_tickets", "expires_at_utc");
            assertColumn(connection, "oauth_login_tickets", "consumed_at_utc");
            assertColumn(connection, "oauth_profile_completion_tickets", "token_hash");
            assertColumn(connection, "oauth_profile_completion_tickets", "provider_subject");
            assertColumn(connection, "oauth_profile_completion_tickets", "expires_at_utc");
            assertColumn(connection, "oauth_profile_completion_tickets", "consumed_at_utc");
            assertColumn(connection, "reservation_invoices", "add_on_service_amount");
            assertColumn(connection, "reservation_invoices", "extra_guest_charge");
            assertColumn(connection, "reservation_invoices", "pricing_version");
            assertColumnType(connection, "idempotency_requests", "request_hash", "character", 64);
            assertColumnType(connection, "reservation_invoices", "currency", "character", 3);
            assertColumnType(connection, "payment_provider_events", "provider_occurred_at_utc",
                    "timestamp with time zone", null);
            assertColumnType(connection, "reservations", "check_in",
                    "timestamp without time zone", null);
            assertIdentityColumn(connection, "rooms", "id");
            assertIdentityColumn(connection, "pricing_quote_commitments", "id");
            assertIndex(connection, "idx_reservations_status_checkin");
            assertIndex(connection, "idx_payment_transactions_reservation_purpose_status");
            assertIndex(connection, "idx_audit_target_occurred");
            assertIndex(connection, "idx_audit_correlation_id");
            assertIndex(connection, "idx_checkout_reconciliation_pending");
            assertIndex(connection, "idx_audit_notification_due");
            assertIndex(connection, "idx_oauth_login_tickets_expiry");
            assertIndex(connection, "idx_oauth_profile_completion_expiry");
            assertIndex(connection, "idx_oauth_profile_completion_identity");
            assertIndex(connection, "idx_facility_images_facility_order");
            assertIndex(connection, "idx_room_type_images_room_type_order");
            assertIndex(connection, "idx_service_catalog_active_sort");
            assertIndex(connection, "idx_reservation_services_reservation_status");
            assertIndex(connection, "idx_media_assets_owner");
            assertIndex(connection, "uk_stay_policy_open_version");
            assertIndex(connection, "uk_room_rate_profile_open_version");
            assertIndex(connection, "idx_rate_snapshot_line_created");
            assertIndex(connection, "idx_pricing_quotes_expiry");
            assertIndex(connection, "idx_pricing_quote_lines_quote");
            assertIndex(connection, "idx_payment_transactions_paid_at_utc");
            assertIndex(connection, "idx_payment_refunds_succeeded_completed_at_utc");
            assertIndex(connection, "idx_reservation_invoices_issued_at_utc");
            assertIndex(connection, "idx_reservations_created_at_status");
            assertIndex(connection, "idx_reservations_stay_window_status");
            assertIndex(connection, "idx_reservation_room_types_room_type_reservation");
            assertIndex(connection, "idx_provider_events_unlinked_cash_occurred_at");
            assertIndex(connection, "uk_cashier_shift_active_user");
            assertIndex(connection, "idx_cash_movement_shift_occurred");
            assertConstraint(connection, "chk_reservations_date_range");
            assertConstraint(connection, "chk_payment_refunds_amounts_nonnegative");
            assertConstraint(connection, "chk_payment_transactions_provider");
            assertConstraint(connection, "chk_payment_transactions_refund_provider");
            assertConstraint(connection, "chk_payment_refunds_provider");
            assertConstraint(connection, "chk_payment_refunds_channel");
            assertConstraint(connection, "chk_payment_refunds_completion_method");
            assertConstraint(connection, "chk_payment_provider_events_provider");
            assertConstraint(connection, "chk_reconciliation_state_provider");
            assertConstraint(connection, "uk_oauth_login_tickets_token_hash");
            assertConstraint(connection, "fk_oauth_login_tickets_user");
            assertConstraint(connection, "uk_oauth_profile_completion_token_hash");
            assertConstraint(connection, "uk_facility_images_url");
            assertConstraint(connection, "uk_room_type_images_url");
            assertConstraint(connection, "chk_facility_images_max_order");
            assertConstraint(connection, "chk_room_type_images_max_order");
            assertConstraint(connection, "uk_room_types_code");
            assertConstraint(connection, "chk_reservations_pricing_version");
            assertConstraint(connection, "chk_invoice_pricing_version");
            assertConstraint(connection, "chk_invoice_extra_guest_charge");
            assertConstraint(connection, "uk_stay_policy_code_version");
            assertConstraint(connection, "chk_stay_policy_inventory_protection");
            assertConstraint(connection, "chk_stay_policy_early_morning_minimum");
            assertConstraint(connection, "uk_room_rate_profile_version");
            assertConstraint(connection, "uk_reservation_rate_snapshot_sequence");
            assertConstraint(connection, "chk_room_rate_profile_monotonic_package_prices");
            assertConstraint(connection, "chk_room_rate_profile_whole_vnd");
            assertConstraint(connection, "chk_service_catalog_whole_vnd");
            assertConstraint(connection, "chk_service_catalog_pricing_unit");
            assertConstraint(connection, "chk_reservation_services_pricing_unit");
            assertConstraint(connection, "chk_room_types_price_whole_vnd");
            assertConstraint(connection, "chk_rrt_minimum_one_guest_per_room");
            assertConstraint(connection, "chk_pricing_quote_minimum_one_guest_per_room");
            assertConstraint(connection, "chk_rate_snapshot_minimum_one_guest_per_room");
            assertConstraint(connection, "uk_pricing_quote_room_type");
            assertConstraint(connection, "uk_pricing_quote_commitment_quote");
            assertConstraint(connection, "uk_pricing_quote_commitment_reservation");
            assertConstraint(connection, "uk_cash_movement_source");
            assertConstraint(connection, "chk_cash_movement_direction_type");
            assertConstraintAbsent(connection, "uk_media_assets_owner");
            assertColumnDefault(connection, "rooms", "sellable", "true");
            assertColumnDefault(connection, "stay_policy_versions",
                    "early_morning_overnight_minimum_minutes", "120");
            assertColumnDefault(connection, "stay_policy_versions",
                    "remainder_cycle_starts_at_boundary", "true");
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
    void existingV8CatalogueBackfillsOrderedMultiImageRows() throws Exception {
        Flyway v8 = flyway("8");
        v8.clean();
        v8.migrate();

        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO facilities (
                        facility_name, facility_name_en, type, image_url
                    ) VALUES
                        ('Hồ bơi', 'Swimming Pool', 'PUBLIC',
                         'https://cdn.example/static/facilities/fasilitas-1.jpg'),
                        ('Spa & chăm sóc sức khỏe', 'Spa & Wellness', 'PUBLIC',
                         'https://cdn.example/facilities/spa-managed.webp')
                    """);
            statement.executeUpdate("""
                    INSERT INTO room_types (
                        type_name, type_name_en, price, max_guests, image_url
                    ) VALUES
                        ('Phòng tiêu chuẩn', 'Standard', 50000, 2,
                         'https://cdn.example/static/room_types/room-standard-main.webp'),
                        ('Phòng Executive', 'Executive Room', 65000, 2,
                         'https://cdn.example/room_types/executive-managed.webp')
                    """);
        }

        Flyway latest = flyway();
        latest.migrate();

        try (Connection connection = POSTGRES.createConnection("")) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT string_agg(fi.image_url, '|' ORDER BY fi.display_order)
                    FROM facility_images fi
                    JOIN facilities f ON f.id = fi.facility_id
                    WHERE f.facility_name = 'Spa & chăm sóc sức khỏe'
                    """);
                 ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo(
                        "https://cdn.example/facilities/spa-managed.webp"
                                + "|https://cdn.example/static/facilities/facility-spa-detail.webp");
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT string_agg(rti.image_url, '|' ORDER BY rti.display_order)
                    FROM room_type_images rti
                    JOIN room_types rt ON rt.id = rti.room_type_id
                    WHERE rt.type_name = 'Phòng Executive'
                    """);
                 ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo(
                        "https://cdn.example/room_types/executive-managed.webp"
                                + "|https://cdn.example/static/room_types/room-executive-work.webp"
                                + "|https://cdn.example/static/room_types/room-executive-bathroom.webp");
            }
        }
    }

    @Test
    void existingV13DataGetsCompatibilitySafePricingV2Foundation() throws Exception {
        Flyway v13 = flyway("13");
        v13.clean();
        v13.migrate();

        long canonicalRoomTypeId;
        long familyRoomTypeId;
        long customRoomTypeId;
        long reservationId;
        try (Connection connection = POSTGRES.createConnection("")) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO room_types (
                        type_name, type_name_en, price, max_guests
                    ) VALUES
                        ('Phòng tiêu chuẩn', 'Standard', 70000, 2),
                        ('Phòng gia đình', 'Family Room', 80000, 2),
                        ('Phòng hướng hồ', 'Lake View', 95000, 3)
                    RETURNING id
                    """)) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    canonicalRoomTypeId = resultSet.getLong(1);
                    assertThat(resultSet.next()).isTrue();
                    familyRoomTypeId = resultSet.getLong(1);
                    assertThat(resultSet.next()).isTrue();
                    customRoomTypeId = resultSet.getLong(1);
                }
            }

            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO customer_profiles (full_name, source)
                        VALUES ('Pricing V2 compatibility guest', 'STAFF_CREATED')
                        """);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO reservations (
                        check_in, check_out, discount_amount, guest_count,
                        late_checkout_fee, reservation_code, status, tax_amount,
                        total_amount, required_initial_payment, customer_profile_id,
                        cancellation_fee, refundable_amount,
                        early_checkout_adjustment, checkout_additional_fee
                    )
                    SELECT
                        TIMESTAMP '2026-07-28 20:00:00',
                        TIMESTAMP '2026-07-29 08:00:00',
                        0, 1, 0, 'RES-PRICING-V2-COMPAT', 'PENDING', 0,
                        170000, 85000, id, 0, 170000, 0, 0
                    FROM customer_profiles
                    WHERE full_name = 'Pricing V2 compatibility guest'
                    RETURNING id
                    """)) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    reservationId = resultSet.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO reservation_room_types (
                        reservation_id, room_type_id, quantity, room_price, subtotal
                    ) VALUES (?, ?, 1, 170000, 170000)
                    """)) {
                statement.setLong(1, reservationId);
                statement.setLong(2, canonicalRoomTypeId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO reservation_invoices (
                        created_at, invoice_number, issued_at, snapshot_json,
                        total_amount, currency, reservation_id,
                        add_on_service_amount
                    ) VALUES (
                        CURRENT_TIMESTAMP, 'INV-PRICING-V2-COMPAT',
                        CURRENT_TIMESTAMP, '{}', 170000, 'VND', ?, 0
                    )
                    """)) {
                statement.setLong(1, reservationId);
                statement.executeUpdate();
            }
        }

        flyway().migrate();

        try (Connection connection = POSTGRES.createConnection("")) {
            assertScalar(connection,
                    "SELECT code FROM room_types WHERE id = " + canonicalRoomTypeId,
                    "STANDARD");
            assertScalar(connection,
                    "SELECT code FROM room_types WHERE id = " + customRoomTypeId,
                    "ROOM_TYPE_" + customRoomTypeId);
            assertScalar(connection,
                    "SELECT max_guests::text FROM room_types WHERE id = " + familyRoomTypeId,
                    "6");
            assertScalar(connection, """
                    SELECT included_guests::text
                    FROM room_rate_profiles
                    WHERE room_type_id = %d
                      AND active = true
                      AND effective_to_utc IS NULL
                    """.formatted(familyRoomTypeId), "4");
            assertScalar(connection,
                    "SELECT pricing_version FROM reservations WHERE id = " + reservationId,
                    "LEGACY_V1");
            assertScalar(connection,
                    "SELECT extra_guest_charge::text FROM reservation_invoices "
                            + "WHERE reservation_id = " + reservationId,
                    "0.00");
            assertScalar(connection,
                    "SELECT pricing_version FROM reservation_invoices "
                            + "WHERE reservation_id = " + reservationId,
                    "LEGACY_V1");
            assertScalar(connection,
                    "SELECT count(*)::text FROM reservation_rate_snapshots",
                    "0");
            assertScalar(connection,
                    "SELECT grace_minutes::text FROM stay_policy_versions "
                            + "WHERE policy_code = 'DEFAULT_MOTEL_POLICY' "
                            + "AND active = true AND effective_to_utc IS NULL",
                    "15");
            assertScalar(connection,
                    "SELECT inventory_protection_mode FROM stay_policy_versions "
                            + "WHERE policy_code = 'DEFAULT_MOTEL_POLICY' "
                            + "AND active = true AND effective_to_utc IS NULL",
                    "PACKAGE_ENTITLEMENT");
            assertScalar(connection,
                    "SELECT effective_from_utc::date::text FROM stay_policy_versions "
                            + "WHERE policy_code = 'DEFAULT_MOTEL_POLICY' "
                            + "AND policy_version = 1",
                    "2026-01-01");
            assertScalar(connection,
                    "SELECT overnight_early_morning_end::text "
                            + "FROM stay_policy_versions "
                            + "WHERE policy_code = 'DEFAULT_MOTEL_POLICY' "
                            + "AND active = true AND effective_to_utc IS NULL",
                    "08:00:00");
            assertScalar(connection,
                    "SELECT early_morning_overnight_minimum_minutes::text "
                            + "FROM stay_policy_versions "
                            + "WHERE policy_code = 'DEFAULT_MOTEL_POLICY' "
                            + "AND active = true AND effective_to_utc IS NULL",
                    "120");
            assertScalar(connection,
                    "SELECT remainder_cycle_starts_at_boundary::text "
                            + "FROM stay_policy_versions "
                            + "WHERE policy_code = 'DEFAULT_MOTEL_POLICY' "
                            + "AND active = true AND effective_to_utc IS NULL",
                    "true");
            assertScalar(connection,
                    "SELECT concat_ws('|', "
                            + "early_morning_overnight_minimum_minutes, "
                            + "remainder_cycle_starts_at_boundary) "
                            + "FROM stay_policy_versions "
                            + "WHERE policy_code = 'DEFAULT_MOTEL_POLICY' "
                            + "AND policy_version = 1",
                    "0|f");
            assertScalar(connection, """
                    SELECT count(*)::text
                    FROM room_rate_profiles
                    WHERE room_type_id = %d
                    """.formatted(canonicalRoomTypeId), "3");
            assertScalar(connection, """
                    SELECT first_block_price::text
                    FROM room_rate_profiles
                    WHERE room_type_id = %d
                      AND active = true
                      AND effective_to_utc IS NULL
                    """.formatted(canonicalRoomTypeId), "70000.00");
            assertScalar(connection, """
                    SELECT count(*)::text
                    FROM room_rate_profiles
                    WHERE room_type_id = %d
                    """.formatted(customRoomTypeId), "0");

            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT line_guest_count, minimum_committed_room_charge,
                           max_package_reached
                    FROM reservation_room_types
                    WHERE reservation_id = ?
                    """)) {
                statement.setLong(1, reservationId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getObject("line_guest_count")).isNull();
                    assertThat(resultSet.getBigDecimal("minimum_committed_room_charge")).isNull();
                    assertThat(resultSet.getString("max_package_reached")).isNull();
                }
            }
        }
    }

    @Test
    void pricingConfigurationIsVersionedAndCapacitySafe() throws Exception {
        Flyway flyway = flyway();
        flyway.clean();
        flyway.migrate();

        long roomTypeId;
        long profileId;
        try (Connection connection = POSTGRES.createConnection("")) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO room_types (code, type_name, max_guests)
                    VALUES ('VERSIONED_RATE_TEST', 'Versioned rate test', 2)
                    RETURNING id
                    """);
                 ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                roomTypeId = resultSet.getLong(1);
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO room_rate_profiles (
                        room_type_id, stay_policy_version_id, profile_version,
                        included_guests, first_block_minutes, first_block_price,
                        extra_unit_minutes, extra_unit_price, overnight_price,
                        daily_price, extra_guest_price, extra_guest_billing_mode,
                        effective_from_utc, active, created_at_utc
                    )
                    SELECT ?, id, 1, 2, 120, 70000, 60, 20000, 170000,
                           300000, 50000, 'PER_PACKAGE_CYCLE',
                           TIMESTAMPTZ '2026-07-27 00:00:00+00', true,
                           TIMESTAMPTZ '2026-07-27 00:00:00+00'
                    FROM stay_policy_versions
                    WHERE policy_code = 'DEFAULT_MOTEL_POLICY'
                      AND policy_version = 1
                    RETURNING id
                    """)) {
                statement.setLong(1, roomTypeId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    profileId = resultSet.getLong(1);
                }
            }
        }

        assertThatThrownBy(() -> executeSql("""
                UPDATE room_rate_profiles
                SET first_block_price = 1
                WHERE id = %d
                """.formatted(profileId)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("create a new version");
        assertThatThrownBy(() -> executeSql("""
                UPDATE room_types
                SET max_guests = 1
                WHERE id = %d
                """.formatted(roomTypeId)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("active included guests");
        assertThatThrownBy(() -> executeSql("""
                INSERT INTO room_rate_profiles (
                    room_type_id, stay_policy_version_id, profile_version,
                    included_guests, first_block_minutes, first_block_price,
                    extra_unit_minutes, extra_unit_price, overnight_price,
                    daily_price, extra_guest_price, extra_guest_billing_mode,
                    effective_from_utc, effective_to_utc, active, created_at_utc
                )
                SELECT %d, id, 2, 3, 120, 70000, 60, 20000, 170000,
                       300000, 50000, 'PER_PACKAGE_CYCLE',
                       TIMESTAMPTZ '2026-07-26 00:00:00+00',
                       TIMESTAMPTZ '2026-07-26 12:00:00+00', false,
                       TIMESTAMPTZ '2026-07-26 00:00:00+00'
                FROM stay_policy_versions
                WHERE policy_code = 'DEFAULT_MOTEL_POLICY'
                  AND policy_version = 1
                """.formatted(roomTypeId)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("exceeds room type capacity");
        assertThatThrownBy(() -> executeSql("""
                INSERT INTO room_rate_profiles (
                    room_type_id, stay_policy_version_id, profile_version,
                    included_guests, first_block_minutes, first_block_price,
                    extra_unit_minutes, extra_unit_price, overnight_price,
                    daily_price, extra_guest_price, extra_guest_billing_mode,
                    effective_from_utc, effective_to_utc, active, created_at_utc
                )
                SELECT %d, id, 3, 2, 120, 200000, 60, 20000, 170000,
                       300000, 50000, 'PER_PACKAGE_CYCLE',
                       TIMESTAMPTZ '2026-07-25 00:00:00+00',
                       TIMESTAMPTZ '2026-07-25 12:00:00+00', false,
                       TIMESTAMPTZ '2026-07-25 00:00:00+00'
                FROM stay_policy_versions
                WHERE policy_code = 'DEFAULT_MOTEL_POLICY'
                  AND policy_version = 1
                """.formatted(roomTypeId)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_room_rate_profile_monotonic_package_prices");

        executeSql("""
                UPDATE room_rate_profiles
                SET active = false,
                    effective_to_utc = TIMESTAMPTZ '2026-07-28 00:00:00+00'
                WHERE id = %d
                """.formatted(profileId));
        assertThatThrownBy(() -> executeSql(
                "DELETE FROM room_rate_profiles WHERE id = " + profileId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> executeSql("""
                UPDATE stay_policy_versions
                SET grace_minutes = 16
                WHERE policy_code = 'DEFAULT_MOTEL_POLICY'
                  AND policy_version = 1
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("create a new version");
        assertThatThrownBy(() -> executeSql("""
                UPDATE stay_policy_versions
                SET early_morning_overnight_minimum_minutes = 121
                WHERE policy_code = 'DEFAULT_MOTEL_POLICY'
                  AND active = true
                  AND effective_to_utc IS NULL
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("create a new version");
        assertThatThrownBy(() -> executeSql("""
                UPDATE stay_policy_versions
                SET remainder_cycle_starts_at_boundary = false
                WHERE policy_code = 'DEFAULT_MOTEL_POLICY'
                  AND active = true
                  AND effective_to_utc IS NULL
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("create a new version");
    }

    @Test
    void canonicalPricingMasterDataMatchesTheApprovedTariff() throws Exception {
        Flyway flyway = flyway("16");
        flyway.clean();
        flyway.migrate();
        executeSql("""
                INSERT INTO room_types (code, type_name, max_guests)
                VALUES ('STANDARD', 'Standard', 2),
                       ('DELUXE', 'Deluxe', 3),
                       ('EXECUTIVE', 'Executive', 3),
                       ('SUITE', 'Suite', 4),
                       ('FAMILY', 'Family', 6),
                       ('PRESIDENTIAL', 'Presidential', 6)
                """);
        flyway().migrate();

        try (Connection connection = POSTGRES.createConnection("")) {
            assertActiveRate(
                    connection,
                    "STANDARD",
                    "2|1|120|70000.00|60|20000.00|170000.00|300000.00|50000.00");
            assertActiveRate(
                    connection,
                    "DELUXE",
                    "3|2|120|100000.00|60|25000.00|220000.00|400000.00|50000.00");
            assertActiveRate(
                    connection,
                    "EXECUTIVE",
                    "3|2|120|120000.00|60|30000.00|270000.00|480000.00|50000.00");
            assertActiveRate(
                    connection,
                    "SUITE",
                    "4|2|120|150000.00|60|35000.00|350000.00|600000.00|50000.00");
            assertActiveRate(
                    connection,
                    "FAMILY",
                    "6|4|120|130000.00|60|30000.00|330000.00|550000.00|50000.00");
            assertActiveRate(
                    connection,
                    "PRESIDENTIAL",
                    "6|4|120|200000.00|60|50000.00|450000.00|850000.00|50000.00");
            assertScalar(connection, """
                    SELECT concat_ws('|', grace_minutes,
                            overnight_start_time::text,
                            overnight_early_morning_end::text,
                            early_morning_overnight_minimum_minutes,
                            overnight_hard_checkout_time::text,
                            overnight_maximum_minutes,
                            daily_threshold_minutes,
                            daily_duration_minutes,
                            turnover_buffer_minutes,
                            remainder_cycle_starts_at_boundary)
                    FROM stay_policy_versions
                    WHERE policy_code = 'DEFAULT_MOTEL_POLICY'
                      AND active = true
                      AND effective_to_utc IS NULL
                    """,
                    "15|20:00:00|08:00:00|120|12:00:00|720|1200|1440|30|t");
        }
    }

    @Test
    void pricingBoundaryPolicyVersioningPreservesFiniteAndScheduledRates()
            throws Exception {
        Flyway v20 = flyway("20");
        v20.clean();
        v20.migrate();

        executeSql("""
                WITH room_type AS (
                    INSERT INTO room_types (code, type_name, max_guests)
                    VALUES ('FINITE_RATE_CHAIN', 'Finite rate chain', 2)
                    RETURNING id
                ),
                policy AS (
                    SELECT id
                    FROM stay_policy_versions
                    WHERE policy_code = 'DEFAULT_MOTEL_POLICY'
                      AND active = true
                      AND effective_to_utc IS NULL
                    ORDER BY policy_version DESC
                    LIMIT 1
                )
                INSERT INTO room_rate_profiles (
                    room_type_id, stay_policy_version_id, profile_version,
                    included_guests, first_block_minutes, first_block_price,
                    extra_unit_minutes, extra_unit_price, overnight_price,
                    daily_price, extra_guest_price, extra_guest_billing_mode,
                    effective_from_utc, effective_to_utc, active,
                    created_at_utc
                )
                SELECT
                    room_type.id,
                    policy.id,
                    rate.profile_version,
                    1,
                    120,
                    rate.first_block_price,
                    60,
                    20000,
                    170000,
                    300000,
                    50000,
                    'PER_PACKAGE_CYCLE',
                    rate.effective_from_utc,
                    rate.effective_to_utc,
                    true,
                    CURRENT_TIMESTAMP
                FROM room_type
                CROSS JOIN policy
                CROSS JOIN (
                    VALUES
                        (
                            1,
                            71000::numeric,
                            CURRENT_TIMESTAMP - INTERVAL '1 day',
                            CURRENT_TIMESTAMP + INTERVAL '1 day'
                        ),
                        (
                            2,
                            72000::numeric,
                            CURRENT_TIMESTAMP + INTERVAL '1 day',
                            CURRENT_TIMESTAMP + INTERVAL '2 days'
                        )
                ) AS rate(
                    profile_version,
                    first_block_price,
                    effective_from_utc,
                    effective_to_utc
                )
                """);

        flyway().migrate();

        try (Connection connection = POSTGRES.createConnection("")) {
            assertScalar(connection, """
                    SELECT count(*)::text
                    FROM room_rate_profiles profile
                    JOIN room_types room_type
                      ON room_type.id = profile.room_type_id
                    JOIN stay_policy_versions policy
                      ON policy.id = profile.stay_policy_version_id
                    WHERE room_type.code = 'FINITE_RATE_CHAIN'
                      AND profile.active = true
                      AND policy.active = true
                      AND policy.early_morning_overnight_minimum_minutes = 120
                      AND policy.remainder_cycle_starts_at_boundary = true
                    """, "2");
            assertScalar(connection, """
                    SELECT string_agg(
                        profile.first_block_price::text,
                        ',' ORDER BY profile.effective_from_utc)
                    FROM room_rate_profiles profile
                    JOIN room_types room_type
                      ON room_type.id = profile.room_type_id
                    WHERE room_type.code = 'FINITE_RATE_CHAIN'
                      AND profile.active = true
                    """, "71000.00,72000.00");
            assertScalar(connection, """
                    SELECT string_agg(
                        profile.profile_version::text,
                        ',' ORDER BY profile.profile_version)
                    FROM room_rate_profiles profile
                    JOIN room_types room_type
                      ON room_type.id = profile.room_type_id
                    WHERE room_type.code = 'FINITE_RATE_CHAIN'
                      AND profile.active = true
                    """, "3,4");
            assertScalar(connection, """
                    SELECT count(*)::text
                    FROM room_rate_profiles profile
                    JOIN room_types room_type
                      ON room_type.id = profile.room_type_id
                    WHERE room_type.code = 'FINITE_RATE_CHAIN'
                      AND profile.active = true
                      AND profile.effective_from_utc <= CURRENT_TIMESTAMP
                      AND (
                          profile.effective_to_utc IS NULL
                          OR profile.effective_to_utc > CURRENT_TIMESTAMP
                      )
                    """, "1");
            assertScalar(connection, """
                    SELECT count(*)::text
                    FROM room_rate_profiles profile
                    JOIN room_types room_type
                      ON room_type.id = profile.room_type_id
                    WHERE room_type.code = 'FINITE_RATE_CHAIN'
                      AND profile.active = true
                      AND profile.effective_from_utc
                            <= CURRENT_TIMESTAMP + INTERVAL '36 hours'
                      AND profile.effective_to_utc
                            > CURRENT_TIMESTAMP + INTERVAL '36 hours'
                    """, "1");
            assertScalar(connection, """
                    SELECT count(*)::text
                    FROM room_rate_profiles profile
                    JOIN room_types room_type
                      ON room_type.id = profile.room_type_id
                    JOIN stay_policy_versions policy
                      ON policy.id = profile.stay_policy_version_id
                    WHERE room_type.code = 'FINITE_RATE_CHAIN'
                      AND profile.active = false
                      AND policy.early_morning_overnight_minimum_minutes = 0
                    """, "2");
        }
    }

    @Test
    void legacyCatalogNightUnitBecomesCanonicalAndNewCatalogRejectsAlias()
            throws Exception {
        Flyway v21 = flyway("21");
        v21.clean();
        v21.migrate();

        executeSql("""
                INSERT INTO service_catalog (
                    code, name, category, price, pricing_unit,
                    booking_enabled, in_stay_enabled, is_active, sort_order
                ) VALUES (
                    'LEGACY_PACKAGE_SERVICE',
                    'Legacy package service',
                    'AMENITY',
                    200000,
                    'PER_NIGHT',
                    true,
                    true,
                    true,
                    1
                )
                """);

        flyway().migrate();

        try (Connection connection = POSTGRES.createConnection("")) {
            assertScalar(connection, """
                    SELECT pricing_unit
                    FROM service_catalog
                    WHERE code = 'LEGACY_PACKAGE_SERVICE'
                    """, "PER_PACKAGE_CYCLE");
        }

        assertThatThrownBy(() -> executeSql("""
                INSERT INTO service_catalog (
                    code, name, category, price, pricing_unit,
                    booking_enabled, in_stay_enabled, is_active, sort_order
                ) VALUES (
                    'NEW_LEGACY_UNIT',
                    'New legacy unit',
                    'AMENITY',
                    1,
                    'PER_NIGHT',
                    true,
                    true,
                    true,
                    2
                )
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_service_catalog_pricing_unit");
    }

    @Test
    void pricingValidityWindowsCannotOverlapButAdjacentVersionsAreAllowed()
            throws Exception {
        Flyway flyway = flyway();
        flyway.clean();
        flyway.migrate();

        long roomTypeId;
        try (Connection connection = POSTGRES.createConnection("");
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO room_types (code, type_name, max_guests)
                     VALUES ('RATE_WINDOW_TEST', 'Rate window test', 2)
                     RETURNING id
                     """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                roomTypeId = resultSet.getLong(1);
            }
        }

        executeSql(rateWindowInsert(roomTypeId, 1,
                "2030-01-01 00:00:00+00", "2030-02-01 00:00:00+00"));
        executeSql(rateWindowInsert(roomTypeId, 2,
                "2030-02-01 00:00:00+00", "2030-03-01 00:00:00+00"));

        assertThatThrownBy(() -> executeSql(rateWindowInsert(
                roomTypeId,
                3,
                "2030-01-15 00:00:00+00",
                "2030-02-15 00:00:00+00")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("room-rate validity window overlaps");

        assertThatThrownBy(() -> executeSql("""
                INSERT INTO stay_policy_versions (
                    policy_code, policy_version, grace_minutes,
                    overnight_start_time, overnight_early_morning_end,
                    overnight_hard_checkout_time, overnight_maximum_minutes,
                    daily_threshold_minutes, daily_duration_minutes,
                    turnover_buffer_minutes, inventory_protection_mode,
                    effective_from_utc, effective_to_utc, active,
                    created_at_utc
                )
                SELECT policy_code, 999, grace_minutes,
                       overnight_start_time, overnight_early_morning_end,
                       overnight_hard_checkout_time, overnight_maximum_minutes,
                       daily_threshold_minutes, daily_duration_minutes,
                       turnover_buffer_minutes, inventory_protection_mode,
                       effective_from_utc + INTERVAL '1 second',
                       NULL, true, CURRENT_TIMESTAMP
                FROM stay_policy_versions
                WHERE policy_code = 'DEFAULT_MOTEL_POLICY'
                  AND active = true
                ORDER BY policy_version DESC
                LIMIT 1
                """))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("stay-policy validity window overlaps");
    }

    @Test
    void existingV14SeedDateRepairIsScopedToCanonicalFoundationRows() throws Exception {
        Flyway v14 = flyway("14");
        v14.clean();
        v14.migrate();

        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO room_types (code, type_name, max_guests)
                    VALUES
                        ('STANDARD', 'Foundation standard', 2),
                        ('CUSTOM_RATE', 'Custom rate', 2)
                    """);
            statement.executeUpdate("""
                    INSERT INTO room_rate_profiles (
                        room_type_id, stay_policy_version_id, profile_version,
                        included_guests, first_block_minutes, first_block_price,
                        extra_unit_minutes, extra_unit_price, overnight_price,
                        daily_price, extra_guest_price, extra_guest_billing_mode,
                        effective_from_utc, active, created_at_utc
                    )
                    SELECT room_type.id, policy.id, 1, 1, 120, 70000,
                           60, 20000, 170000, 300000, 50000,
                           'PER_PACKAGE_CYCLE',
                           TIMESTAMPTZ '2026-07-27 00:00:00+00', true,
                           TIMESTAMPTZ '2026-07-27 00:00:00+00'
                    FROM room_types room_type
                    CROSS JOIN stay_policy_versions policy
                    WHERE room_type.code IN ('STANDARD', 'CUSTOM_RATE')
                      AND policy.policy_code = 'DEFAULT_MOTEL_POLICY'
                      AND policy.policy_version = 1
                    """);
        }

        flyway().migrate();

        try (Connection connection = POSTGRES.createConnection("")) {
            assertScalar(connection, """
                    SELECT effective_from_utc::date::text
                    FROM room_rate_profiles profile
                    JOIN room_types room_type ON room_type.id = profile.room_type_id
                    WHERE room_type.code = 'STANDARD'
                    """, "2026-01-01");
            assertScalar(connection, """
                    SELECT effective_from_utc::date::text
                    FROM room_rate_profiles profile
                    JOIN room_types room_type ON room_type.id = profile.room_type_id
                    WHERE room_type.code = 'CUSTOM_RATE'
                    """, "2026-07-27");
        }
    }

    @Test
    void pricingRateSeedFillsMissingCanonicalProfilesWithoutOverwritingExistingRates()
            throws Exception {
        Flyway v16 = flyway("16");
        v16.clean();
        v16.migrate();

        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO room_types (code, type_name, max_guests)
                    VALUES
                        ('STANDARD', 'Standard migration seed', 2),
                        ('DELUXE', 'Deluxe operator rate', 3)
                    """);
            statement.executeUpdate("""
                    INSERT INTO room_rate_profiles (
                        room_type_id, stay_policy_version_id, profile_version,
                        included_guests, first_block_minutes, first_block_price,
                        extra_unit_minutes, extra_unit_price, overnight_price,
                        daily_price, extra_guest_price, extra_guest_billing_mode,
                        effective_from_utc, active, created_at_utc
                    )
                    SELECT room_type.id, policy.id, 7, 2, 120, 111000,
                           60, 27000, 233000, 411000, 55000,
                           'PER_PACKAGE_CYCLE',
                           TIMESTAMPTZ '2026-01-01 00:00:00+00', true,
                           TIMESTAMPTZ '2026-07-26 00:00:00+00'
                    FROM room_types room_type
                    CROSS JOIN stay_policy_versions policy
                    WHERE room_type.code = 'DELUXE'
                      AND policy.policy_code = 'DEFAULT_MOTEL_POLICY'
                      AND policy.policy_version = 1
                    """);
        }

        flyway().migrate();

        try (Connection connection = POSTGRES.createConnection("")) {
            assertScalar(connection, """
                    SELECT count(*)::text
                    FROM room_rate_profiles profile
                    JOIN room_types room_type ON room_type.id = profile.room_type_id
                    WHERE room_type.code = 'STANDARD'
                    """, "3");
            assertScalar(connection, """
                    SELECT first_block_price::text
                    FROM room_rate_profiles profile
                    JOIN room_types room_type ON room_type.id = profile.room_type_id
                    WHERE room_type.code = 'STANDARD'
                      AND profile.active = true
                      AND profile.effective_to_utc IS NULL
                    """, "70000.00");
            assertScalar(connection, """
                    SELECT count(*)::text
                    FROM room_rate_profiles profile
                    JOIN room_types room_type ON room_type.id = profile.room_type_id
                    WHERE room_type.code = 'DELUXE'
                    """, "3");
            assertScalar(connection, """
                    SELECT profile_version::text || ':' || first_block_price::text
                    FROM room_rate_profiles profile
                    JOIN room_types room_type ON room_type.id = profile.room_type_id
                    WHERE room_type.code = 'DELUXE'
                      AND profile.active = true
                      AND profile.effective_to_utc IS NULL
                    """, "9:111000.00");
        }
    }

    @Test
    void pricingRateSeedDoesNotAttachRatesToAnInactivePolicy()
            throws Exception {
        Flyway v16 = flyway("16");
        v16.clean();
        v16.migrate();

        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE stay_policy_versions
                    SET active = false
                    WHERE policy_code = 'DEFAULT_MOTEL_POLICY'
                      AND policy_version = 1
                    """);
            statement.executeUpdate("""
                    INSERT INTO room_types (code, type_name, max_guests)
                    VALUES ('STANDARD', 'Inactive policy safety', 2)
                    """);
        }

        flyway().migrate();

        try (Connection connection = POSTGRES.createConnection("")) {
            assertScalar(connection, """
                    SELECT count(*)::text
                    FROM room_rate_profiles profile
                    JOIN room_types room_type ON room_type.id = profile.room_type_id
                    WHERE room_type.code = 'STANDARD'
                    """, "0");
        }
    }

    @Test
    void pricingQuoteEvidenceRejectsUpdateAndDeletesWithItsLines() throws Exception {
        Flyway flyway = flyway();
        flyway.clean();
        flyway.migrate();

        String quoteId = "6f9619ff-8b86-d011-b42d-00cf4fc964ff";
        long quoteLineId;
        try (Connection connection = POSTGRES.createConnection("");
             PreparedStatement statement = connection.prepareStatement("""
                     WITH room_type AS (
                         INSERT INTO room_types (code, type_name, max_guests)
                         VALUES ('QUOTE_TEST', 'Quote test', 2)
                         RETURNING id
                     ),
                     profile AS (
                         INSERT INTO room_rate_profiles (
                             room_type_id, stay_policy_version_id, profile_version,
                             included_guests, first_block_minutes, first_block_price,
                             extra_unit_minutes, extra_unit_price, overnight_price,
                             daily_price, extra_guest_price, extra_guest_billing_mode,
                             effective_from_utc, active, created_at_utc
                         )
                         SELECT room_type.id, policy.id, 1, 1, 120, 70000,
                                60, 20000, 170000, 300000, 50000,
                                'PER_PACKAGE_CYCLE',
                                TIMESTAMPTZ '2026-01-01 00:00:00+00', true,
                                TIMESTAMPTZ '2026-01-01 00:00:00+00'
                         FROM room_type
                         CROSS JOIN stay_policy_versions policy
                         WHERE policy.policy_code = 'DEFAULT_MOTEL_POLICY'
                           AND policy.policy_version = 1
                         RETURNING id, room_type_id, stay_policy_version_id
                     ),
                     quote AS (
                         INSERT INTO pricing_quotes (
                             id, stay_policy_version_id, pricing_algorithm_version,
                             check_in, check_out, guest_count, room_charge,
                             extra_guest_charge, service_charge, total_amount,
                             inventory_protected_until, request_hash, quote_hash,
                             request_json, response_json, created_at_utc, expires_at_utc
                         )
                         SELECT
                             ?::uuid, profile.stay_policy_version_id,
                             'MOTEL_PACKAGE_V2',
                             TIMESTAMP '2026-08-01 10:00:00',
                             TIMESTAMP '2026-08-01 12:00:00',
                             1, 70000, 0, 0, 70000,
                             TIMESTAMP '2026-08-01 12:30:00',
                             repeat('a', 64), repeat('b', 64),
                             '{}'::jsonb, '{}'::jsonb,
                             TIMESTAMPTZ '2026-07-27 00:00:00+00',
                             TIMESTAMPTZ '2026-07-27 00:15:00+00'
                         FROM profile
                         RETURNING id
                     )
                     INSERT INTO pricing_quote_lines (
                         pricing_quote_id, room_type_id, rate_profile_id,
                         room_type_code_snapshot, rate_profile_version,
                         room_quantity, line_guest_count, stay_classification,
                         applied_package, transition_reason,
                         package_included_checkout, room_charge,
                         extra_guest_charge, line_total_before_services,
                         breakdown_json
                     )
                     SELECT quote.id, profile.room_type_id, profile.id,
                            'QUOTE_TEST', 1, 1, 1, 'DAY_STAY',
                            'HOURLY', 'HOURLY_WINDOW',
                            TIMESTAMP '2026-08-01 12:00:00',
                            70000, 0, 70000, '{}'::jsonb
                     FROM quote, profile
                     RETURNING id
                     """)) {
            statement.setString(1, quoteId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                quoteLineId = resultSet.getLong(1);
            }
        }

        assertThatThrownBy(() -> executeSql("""
                UPDATE pricing_quotes
                SET total_amount = 1
                WHERE id = '%s'::uuid
                """.formatted(quoteId)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> executeSql("""
                UPDATE pricing_quote_lines
                SET room_charge = 1
                WHERE id = %d
                """.formatted(quoteLineId)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("immutable");

        executeSql("DELETE FROM pricing_quotes WHERE id = '" + quoteId + "'::uuid");
        try (Connection connection = POSTGRES.createConnection("")) {
            assertScalar(connection,
                    "SELECT count(*)::text FROM pricing_quote_lines WHERE id = " + quoteLineId,
                    "0");
        }
    }

    @Test
    void v11RefusesToRelabelHistoricalVnpayFinancialRows() throws Exception {
        Flyway v10 = flyway("10");
        v10.clean();
        v10.migrate();

        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO customer_profiles (
                        full_name, source
                    ) VALUES (
                        'VNPay migration preflight', 'STAFF_CREATED'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO reservations (
                        check_in, check_out, discount_amount, guest_count,
                        late_checkout_fee, reservation_code, status, tax_amount,
                        total_amount, required_initial_payment, customer_profile_id,
                        cancellation_fee, refundable_amount,
                        early_checkout_adjustment, checkout_additional_fee
                    ) VALUES (
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 day',
                        0, 1, 0, 'RES-VNPAY-MIGRATION', 'PENDING', 0,
                        100000, 50000,
                        (SELECT id FROM customer_profiles
                         WHERE full_name = 'VNPay migration preflight'),
                        0, 100000, 0, 0
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO payment_transactions (
                        id, amount, currency, provider, status, txn_ref,
                        reservation_id, purpose, version
                    )
                    SELECT
                        'pay-vnpay-migration', 100000, 'VND', 'VNPAY',
                        'SUCCESS', 'VNPAY-MIGRATION-REF', id,
                        'DEPOSIT', 0
                    FROM reservations
                    WHERE reservation_code = 'RES-VNPAY-MIGRATION'
                    """);
        }

        Flyway latest = flyway();
        assertThatThrownBy(latest::migrate)
                .hasMessageContaining("V11 cannot remove VNPay")
                .hasMessageContaining("payment_transactions");

        try (Connection connection = POSTGRES.createConnection("");
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT provider
                     FROM payment_transactions
                     WHERE id = 'pay-vnpay-migration'
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("provider")).isEqualTo("VNPAY");
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

    @Test
    void pricingSnapshotsRejectUpdateDeleteAndTruncate() throws Exception {
        Flyway flyway = flyway();
        flyway.clean();
        flyway.migrate();

        long snapshotId;
        try (Connection connection = POSTGRES.createConnection("");
             PreparedStatement statement = connection.prepareStatement("""
                     WITH room_type AS (
                         INSERT INTO room_types (code, type_name, max_guests)
                         VALUES ('SNAPSHOT_TEST', 'Snapshot test', 2)
                         RETURNING id
                     ),
                     customer AS (
                         INSERT INTO customer_profiles (full_name, source)
                         VALUES ('Pricing snapshot guest', 'STAFF_CREATED')
                         RETURNING id
                     ),
                     reservation AS (
                         INSERT INTO reservations (
                             check_in, check_out, discount_amount, guest_count,
                             late_checkout_fee, reservation_code, status, tax_amount,
                             total_amount, required_initial_payment, customer_profile_id,
                             cancellation_fee, refundable_amount,
                             early_checkout_adjustment, checkout_additional_fee,
                             pricing_version
                         )
                         SELECT
                             TIMESTAMP '2026-07-28 20:00:00',
                             TIMESTAMP '2026-07-29 08:00:00',
                             0, 1, 0, 'RES-PRICING-SNAPSHOT', 'PENDING', 0,
                             170000, 85000, customer.id, 0, 170000, 0, 0,
                             'MOTEL_PACKAGE_V2'
                         FROM customer
                         RETURNING id
                     ),
                     reservation_line AS (
                         INSERT INTO reservation_room_types (
                             reservation_id, room_type_id, quantity,
                             room_price, subtotal, line_guest_count,
                             minimum_committed_room_charge, max_package_reached
                         )
                         SELECT reservation.id, room_type.id, 1,
                                170000, 170000, 1, 170000, 'OVERNIGHT'
                         FROM reservation, room_type
                         RETURNING id
                     ),
                     profile AS (
                         INSERT INTO room_rate_profiles (
                             room_type_id, stay_policy_version_id, profile_version,
                             included_guests, first_block_minutes, first_block_price,
                             extra_unit_minutes, extra_unit_price, overnight_price,
                             daily_price, extra_guest_price, extra_guest_billing_mode,
                             effective_from_utc, active, created_at_utc
                         )
                         SELECT room_type.id, policy.id, 1, 1, 120, 70000,
                                60, 20000, 170000, 300000, 50000,
                                'PER_PACKAGE_CYCLE',
                                TIMESTAMPTZ '2026-07-27 00:00:00+00', true,
                                TIMESTAMPTZ '2026-07-27 00:00:00+00'
                         FROM room_type
                         CROSS JOIN stay_policy_versions policy
                         WHERE policy.policy_code = 'DEFAULT_MOTEL_POLICY'
                           AND policy.policy_version = 1
                         RETURNING id, stay_policy_version_id
                     )
                     INSERT INTO reservation_rate_snapshots (
                         reservation_room_type_id, snapshot_sequence, snapshot_stage,
                         stay_policy_version_id, rate_profile_id,
                         pricing_algorithm_version, committed_check_in,
                         committed_check_out, stay_classification, initial_package,
                         applied_package, max_package_reached, transition_reason,
                         included_guests, max_guests_snapshot, line_guest_count,
                         room_quantity, extra_guest_count, first_block_minutes,
                         first_block_price, extra_unit_minutes, extra_unit_price,
                         grace_minutes, overnight_price, overnight_included_checkout,
                         daily_price, daily_duration_minutes, full_days,
                         remainder_minutes, charged_extra_units,
                         minimum_committed_room_charge, final_room_charge,
                         extra_guest_charge, allocated_service_charge,
                         adjustment_amount, breakdown_json, snapshot_hash,
                         created_at_utc
                     )
                     SELECT
                         reservation_line.id, 1, 'COMMITMENT',
                         profile.stay_policy_version_id, profile.id,
                         'MOTEL_PACKAGE_V2',
                         TIMESTAMP '2026-07-28 20:00:00',
                         TIMESTAMP '2026-07-29 08:00:00',
                         'NIGHT_STAY', 'OVERNIGHT', 'OVERNIGHT', 'OVERNIGHT',
                         'INITIAL_QUOTE', 1, 2, 1, 1, 0, 120, 70000,
                         60, 20000, 15, 170000,
                         TIMESTAMP '2026-07-29 08:00:00',
                         300000, 1440, 0, 720, 0, 170000, 170000,
                         0, 0, 0, '{"roomCharge":170000}'::jsonb,
                         repeat('a', 64), TIMESTAMPTZ '2026-07-27 00:00:00+00'
                     FROM reservation_line, profile
                     RETURNING id
                     """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                snapshotId = resultSet.getLong(1);
            }
        }

        assertThatThrownBy(() -> executeSql("""
                UPDATE reservation_rate_snapshots
                SET final_room_charge = 1
                WHERE id = %d
                """.formatted(snapshotId)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> executeSql(
                "DELETE FROM reservation_rate_snapshots WHERE id = " + snapshotId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> executeSql(
                "TRUNCATE reservation_rate_snapshots"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");
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
                    INSERT INTO room_types (id, code, type_name, max_guests)
                    VALUES (?, ?, ?, ?)
                    """)) {
                statement.setLong(1, 900_000L);
                statement.setString(2, "IMPORTED_LEGACY");
                statement.setString(3, "Imported legacy room type");
                statement.setInt(4, 2);
                statement.executeUpdate();
            }

            String finalizationSql = Files.readString(
                    Path.of("db", "postgres", "post-cutover-finalize.sql"),
                    StandardCharsets.UTF_8);
            try (var statement = connection.createStatement()) {
                statement.execute(finalizationSql);
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO room_types (code, type_name, max_guests)
                    VALUES (?, ?, ?)
                    RETURNING id
                    """)) {
                statement.setString(1, "FIRST_POSTGRES");
                statement.setString(2, "First PostgreSQL-created room type");
                statement.setInt(3, 2);
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
        assertColumnDefinition(PricingQuote.class, "requestHash", "CHAR(64)");
        assertColumnDefinition(PricingQuote.class, "quoteHash", "CHAR(64)");
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

    private void assertColumnAbsent(Connection connection, String tableName, String columnName)
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
                        .as("column %s.%s is absent", tableName, columnName)
                        .isFalse();
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

    private void assertConstraintAbsent(Connection connection, String constraintName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pg_constraint WHERE connamespace = 'public'::regnamespace AND conname = ?")) {
            statement.setString(1, constraintName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("constraint %s is absent", constraintName).isFalse();
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

    private void executeSql(String sql) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private String rateWindowInsert(
            long roomTypeId,
            int profileVersion,
            String effectiveFrom,
            String effectiveTo) {
        return """
                INSERT INTO room_rate_profiles (
                    room_type_id, stay_policy_version_id, profile_version,
                    included_guests, first_block_minutes, first_block_price,
                    extra_unit_minutes, extra_unit_price, overnight_price,
                    daily_price, extra_guest_price, extra_guest_billing_mode,
                    effective_from_utc, effective_to_utc, active,
                    created_at_utc
                )
                SELECT %d, policy.id, %d, 1, 120, 70000,
                       60, 20000, 170000, 300000, 50000,
                       'PER_PACKAGE_CYCLE',
                       TIMESTAMPTZ '%s', TIMESTAMPTZ '%s', true,
                       CURRENT_TIMESTAMP
                FROM stay_policy_versions policy
                WHERE policy.policy_code = 'DEFAULT_MOTEL_POLICY'
                  AND policy.active = true
                ORDER BY policy.policy_version DESC
                LIMIT 1
                """.formatted(
                        roomTypeId,
                        profileVersion,
                        effectiveFrom,
                        effectiveTo);
    }

    private void assertScalar(Connection connection, String sql, String expected)
            throws SQLException {
        try (var statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo(expected);
        }
    }

    private void assertActiveRate(
            Connection connection,
            String roomTypeCode,
            String expected) throws SQLException {
        assertScalar(connection, """
                SELECT concat_ws('|', room_type.max_guests,
                        profile.included_guests,
                        profile.first_block_minutes,
                        profile.first_block_price::text,
                        profile.extra_unit_minutes,
                        profile.extra_unit_price::text,
                        profile.overnight_price::text,
                        profile.daily_price::text,
                        profile.extra_guest_price::text)
                FROM room_rate_profiles profile
                JOIN room_types room_type
                  ON room_type.id = profile.room_type_id
                JOIN stay_policy_versions policy
                  ON policy.id = profile.stay_policy_version_id
                WHERE room_type.code = '%s'
                  AND profile.active = true
                  AND profile.effective_to_utc IS NULL
                  AND policy.active = true
                  AND policy.effective_to_utc IS NULL
                """.formatted(roomTypeCode), expected);
    }
}
