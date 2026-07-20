-- Audit rows are evidence, not editable business data. Hibernate @Immutable
-- protects ORM writes; this trigger also blocks direct SQL UPDATE/DELETE/TRUNCATE.
CREATE OR REPLACE FUNCTION prevent_reservation_audit_log_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'reservation_audit_logs is append-only'
        USING ERRCODE = '55000';
END;
$$;

DROP TRIGGER IF EXISTS trg_reservation_audit_logs_append_only
    ON reservation_audit_logs;

CREATE TRIGGER trg_reservation_audit_logs_append_only
BEFORE UPDATE OR DELETE OR TRUNCATE ON reservation_audit_logs
FOR EACH STATEMENT
EXECUTE FUNCTION prevent_reservation_audit_log_mutation();
