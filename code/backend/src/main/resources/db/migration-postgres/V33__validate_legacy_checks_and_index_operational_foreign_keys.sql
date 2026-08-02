-- Finish the staged PostgreSQL cutover once legacy rows have been verified.
-- VALIDATE CONSTRAINT is idempotent for an already-valid constraint and keeps
-- the original constraint identity used by Hibernate/Flyway diagnostics.
ALTER TABLE reservations
    VALIDATE CONSTRAINT chk_reservations_date_range;
ALTER TABLE reservation_room_types
    VALIDATE CONSTRAINT chk_reservation_room_types_quantity_positive;
ALTER TABLE payment_transactions
    VALIDATE CONSTRAINT chk_payment_transactions_amounts_nonnegative;
ALTER TABLE payment_refunds
    VALIDATE CONSTRAINT chk_payment_refunds_amounts_nonnegative;

ALTER TABLE reservation_audit_logs
    VALIDATE CONSTRAINT chk_audit_old_value_object;
ALTER TABLE reservation_audit_logs
    VALIDATE CONSTRAINT chk_audit_new_value_object;
ALTER TABLE reservation_audit_logs
    VALIDATE CONSTRAINT chk_audit_detail_object;
ALTER TABLE reservation_audit_logs
    VALIDATE CONSTRAINT chk_audit_risk_level;
ALTER TABLE payment_refunds
    VALIDATE CONSTRAINT chk_payment_refunds_detail_object;

ALTER TABLE room_rate_profiles
    VALIDATE CONSTRAINT chk_room_rate_profile_whole_vnd;
ALTER TABLE service_catalog
    VALIDATE CONSTRAINT chk_service_catalog_whole_vnd;
ALTER TABLE room_types
    VALIDATE CONSTRAINT chk_room_types_price_whole_vnd;
ALTER TABLE reservation_room_types
    VALIDATE CONSTRAINT chk_rrt_minimum_one_guest_per_room;
ALTER TABLE pricing_quote_lines
    VALIDATE CONSTRAINT chk_pricing_quote_minimum_one_guest_per_room;
ALTER TABLE reservation_rate_snapshots
    VALIDATE CONSTRAINT chk_rate_snapshot_minimum_one_guest_per_room;

-- Operational foreign keys that are used for joins, parent cleanup checks or
-- exception review. Prefixing the referenced column keeps PostgreSQL from
-- scanning the full child table when the parent row is updated/deleted.
CREATE INDEX IF NOT EXISTS idx_work_schedule_shift_template
    ON work_schedule_assignments (shift_template_id, work_date);
CREATE INDEX IF NOT EXISTS idx_work_schedule_created_by
    ON work_schedule_assignments (created_by);
CREATE INDEX IF NOT EXISTS idx_work_schedule_updated_by
    ON work_schedule_assignments (updated_by)
    WHERE updated_by IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_work_schedule_cancelled_by
    ON work_schedule_assignments (cancelled_by)
    WHERE cancelled_by IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_cash_movement_payment
    ON cash_movements (payment_transaction_id)
    WHERE payment_transaction_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_cash_movement_refund
    ON cash_movements (refund_id)
    WHERE refund_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_cash_movement_created_by
    ON cash_movements (created_by);

CREATE INDEX IF NOT EXISTS idx_checkout_reconciliation_requester
    ON checkout_reconciliation_requests (requested_by)
    WHERE requested_by IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_checkout_reconciliation_resolver
    ON checkout_reconciliation_requests (resolved_by)
    WHERE resolved_by IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_checkout_reconciliation_evidence
    ON checkout_reconciliation_requests (evidence_asset_id)
    WHERE evidence_asset_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_pricing_quote_lines_room_type
    ON pricing_quote_lines (room_type_id, pricing_quote_id);
CREATE INDEX IF NOT EXISTS idx_pricing_quote_lines_rate_profile
    ON pricing_quote_lines (rate_profile_id, pricing_quote_id);
