-- Extend the existing outbox to booking confirmations without rewriting audit history.
ALTER TABLE audit_notification_outbox ALTER COLUMN audit_log_id DROP NOT NULL;
ALTER TABLE audit_notification_outbox
    ADD COLUMN reservation_id bigint REFERENCES reservations(id),
    ADD COLUMN encrypted_message text,
    ADD COLUMN idempotency_key varchar(36),
    ADD COLUMN delivery_scope varchar(128),
    ADD COLUMN first_attempt_at_utc timestamptz,
    ADD COLUMN acceptance_uncertain boolean NOT NULL DEFAULT false,
    ADD COLUMN provider_message_id varchar(512),
    ADD COLUMN delivery_status varchar(24),
    ADD COLUMN delivery_event_at_utc timestamptz,
    ADD CONSTRAINT uk_email_idempotency_key UNIQUE (idempotency_key),
    ADD CONSTRAINT uk_booking_email_delivery UNIQUE (reservation_id),
    ADD CONSTRAINT chk_email_origin CHECK ((audit_log_id IS NULL) <> (reservation_id IS NULL));

ALTER TABLE audit_notification_outbox DROP CONSTRAINT chk_audit_notification_status;
ALTER TABLE audit_notification_outbox ADD CONSTRAINT chk_audit_notification_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'PERMANENT_FAILURE', 'REVIEW_REQUIRED'));

-- Previous workers had no provider idempotency key. Their acceptance is unknowable.
UPDATE audit_notification_outbox
SET status = 'REVIEW_REQUIRED', acceptance_uncertain = true,
    last_error = 'Legacy attempt has unknown provider acceptance; reconcile before resending'
WHERE status IN ('PROCESSING', 'FAILED');
