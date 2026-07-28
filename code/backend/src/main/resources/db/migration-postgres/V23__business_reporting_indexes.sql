-- Read-only business reporting access paths. This migration is deliberately
-- expand-only: it does not change payment, refund, reservation or invoice data.

CREATE INDEX IF NOT EXISTS idx_payment_transactions_paid_at_utc
    ON payment_transactions (paid_at_utc DESC)
    WHERE status IN ('SUCCESS', 'REFUND_PENDING', 'REFUNDED')
      AND paid_at_utc IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_payment_refunds_succeeded_completed_at_utc
    ON payment_refunds (completed_at_utc DESC)
    WHERE status = 'SUCCEEDED' AND completed_at_utc IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_reservation_invoices_issued_at_utc
    ON reservation_invoices (issued_at_utc DESC)
    WHERE issued_at_utc IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_reservations_created_at_status
    ON reservations (created_at DESC, status);

CREATE INDEX IF NOT EXISTS idx_reservations_stay_window_status
    ON reservations (check_in, check_out, status);

CREATE INDEX IF NOT EXISTS idx_reservation_room_types_room_type_reservation
    ON reservation_room_types (room_type_id, reservation_id);
