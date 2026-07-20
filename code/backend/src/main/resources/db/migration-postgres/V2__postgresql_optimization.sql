-- PostgreSQL-specific operational indexes and invariants.
--
-- V1 intentionally keeps the legacy business columns so the cutover is
-- reversible.  This migration adds only additive safeguards and indexes that
-- match the reservation, payment, cleanup, and dashboard query paths.

-- Reservation availability, customer history, and room-hold cleanup.
CREATE INDEX IF NOT EXISTS idx_reservations_status_checkin
    ON reservations (status, check_in, check_out);

CREATE INDEX IF NOT EXISTS idx_reservations_customer_created
    ON reservations (customer_profile_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_reservation_room_types_reservation_room_type
    ON reservation_room_types (reservation_id, room_type_id);

CREATE INDEX IF NOT EXISTS idx_room_holds_active_expiry
    ON room_holds (expires_at, reservation_room_type_id)
    WHERE status = 'ACTIVE';

-- Payment/reconciliation lookups are commonly scoped by reservation and
-- purpose/status.  Keep the existing single-column/legacy indexes for FK
-- maintenance and add the composite access paths used by the services.
CREATE INDEX IF NOT EXISTS idx_payment_transactions_reservation_purpose_status
    ON payment_transactions (reservation_id, purpose, status);

CREATE INDEX IF NOT EXISTS idx_payment_refunds_reservation_status
    ON payment_refunds (reservation_id, status);

CREATE INDEX IF NOT EXISTS idx_provider_events_unprocessed_received
    ON payment_provider_events (received_at_utc, created_at)
    WHERE processed_at_utc IS NULL;

CREATE INDEX IF NOT EXISTS idx_invoice_settlement_created
    ON reservation_invoices (settlement_status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_reviews_room_type_created
    ON reviews (room_type_id, created_at DESC);

-- Cleanup jobs should not scan the entire users table for expiring tokens.
CREATE INDEX IF NOT EXISTS idx_users_pending_verification_expiry
    ON users (verification_expires_at)
    WHERE email_verified = FALSE AND verification_expires_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_users_pending_reset_expiry
    ON users (password_reset_expires_at)
    WHERE password_reset_token_hash IS NOT NULL
      AND password_reset_expires_at IS NOT NULL;

-- Retire legacy single-column indexes whose complete lookup prefix is now
-- covered by a composite index above. PostgreSQL does not require an index on
-- the referencing side of a foreign key, so these only added write overhead.
DROP INDEX IF EXISTS fks0a3i3b28ytn37gc1wmakiyjg;
DROP INDEX IF EXISTS fk8lfgyecs06xlni10u8xs7a7fy;
DROP INDEX IF EXISTS fk6lysm563jhpge8piv14tglqqx;
DROP INDEX IF EXISTS idx_payment_refunds_payment;
DROP INDEX IF EXISTS idx_room_hold_expires_at;
DROP INDEX IF EXISTS idx_review_room_type;
DROP INDEX IF EXISTS idx_invoice_settlement_status;

-- NOT VALID avoids an immediate full-table scan when V2 is applied after a
-- staged import. New, changed, and subsequently copied rows are still checked;
-- run db/postgres/post-cutover-validate.sql before reopening writes.
ALTER TABLE reservations
    ADD CONSTRAINT chk_reservations_date_range
    CHECK (check_out > check_in) NOT VALID;

ALTER TABLE reservation_room_types
    ADD CONSTRAINT chk_reservation_room_types_quantity_positive
    CHECK (quantity > 0) NOT VALID;

ALTER TABLE payment_transactions
    ADD CONSTRAINT chk_payment_transactions_amounts_nonnegative
    CHECK (
        COALESCE(amount, 0) >= 0
        AND COALESCE(expected_amount, 0) >= 0
        AND COALESCE(received_amount, 0) >= 0
        AND COALESCE(accepted_amount, 0) >= 0
        AND COALESCE(refund_required_amount, 0) >= 0
        AND COALESCE(refund_amount, 0) >= 0
    ) NOT VALID;

ALTER TABLE payment_refunds
    ADD CONSTRAINT chk_payment_refunds_amounts_nonnegative
    CHECK (
        COALESCE(amount, 0) >= 0
        AND COALESCE(requested_amount, 0) >= 0
        AND COALESCE(actual_refund_amount, 0) >= 0
    ) NOT VALID;
