-- Make the overnight refund boundary explicit and reproducible.
--
-- A checked-in guest who leaves before this time may be repriced from actual
-- usage. At or after this time the committed overnight package is the minimum
-- room-charge floor. Completed invoices remain immutable; this policy is only
-- evaluated by an active reservation lifecycle projection.

ALTER TABLE stay_policy_versions
    ADD COLUMN overnight_refund_lock_time time NOT NULL
        DEFAULT TIME '23:00:00';

CREATE OR REPLACE FUNCTION protect_stay_policy_version()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'stay_policy_versions is versioned and cannot be deleted'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.policy_code IS DISTINCT FROM NEW.policy_code
       OR OLD.policy_version IS DISTINCT FROM NEW.policy_version
       OR OLD.grace_minutes IS DISTINCT FROM NEW.grace_minutes
       OR OLD.overnight_start_time IS DISTINCT FROM NEW.overnight_start_time
       OR OLD.overnight_early_morning_end
            IS DISTINCT FROM NEW.overnight_early_morning_end
       OR OLD.early_morning_overnight_minimum_minutes
            IS DISTINCT FROM NEW.early_morning_overnight_minimum_minutes
       OR OLD.overnight_refund_lock_time
            IS DISTINCT FROM NEW.overnight_refund_lock_time
       OR OLD.overnight_hard_checkout_time
            IS DISTINCT FROM NEW.overnight_hard_checkout_time
       OR OLD.overnight_maximum_minutes
            IS DISTINCT FROM NEW.overnight_maximum_minutes
       OR OLD.daily_threshold_minutes
            IS DISTINCT FROM NEW.daily_threshold_minutes
       OR OLD.daily_duration_minutes
            IS DISTINCT FROM NEW.daily_duration_minutes
       OR OLD.turnover_buffer_minutes
            IS DISTINCT FROM NEW.turnover_buffer_minutes
       OR OLD.remainder_cycle_starts_at_boundary
            IS DISTINCT FROM NEW.remainder_cycle_starts_at_boundary
       OR OLD.inventory_protection_mode
            IS DISTINCT FROM NEW.inventory_protection_mode
       OR OLD.effective_from_utc IS DISTINCT FROM NEW.effective_from_utc
       OR OLD.created_by_user_id IS DISTINCT FROM NEW.created_by_user_id
       OR OLD.created_at_utc IS DISTINCT FROM NEW.created_at_utc THEN
        RAISE EXCEPTION
            'stay_policy_versions financial policy is immutable; create a new version'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
