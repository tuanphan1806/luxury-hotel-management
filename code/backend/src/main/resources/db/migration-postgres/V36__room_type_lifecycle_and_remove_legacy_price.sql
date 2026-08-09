-- RoomType is now priced exclusively through immutable room_rate_profiles.
-- A catalogue item can be deactivated without breaking historical bookings.

ALTER TABLE room_types
    ADD COLUMN active boolean NOT NULL DEFAULT true;

CREATE INDEX idx_room_types_active_id
    ON room_types(active, id);

-- A room type without any versioned rate must not remain sellable after the
-- legacy single-price column disappears. Do not invent a financial rate here.
UPDATE room_types room_type
SET active = false
WHERE (
    SELECT COUNT(*)
    FROM room_rate_profiles profile
    WHERE profile.room_type_id = room_type.id
      AND profile.active = true
      AND profile.effective_from_utc <= CURRENT_TIMESTAMP
      AND (
          profile.effective_to_utc IS NULL
          OR profile.effective_to_utc > CURRENT_TIMESTAMP
      )
) <> 1;

ALTER TABLE room_types
    DROP CONSTRAINT IF EXISTS chk_room_types_price_whole_vnd;

ALTER TABLE room_types
    DROP COLUMN price;

-- An unused room type may be deleted together with its unused rate versions.
-- Quote lines and reservation snapshots still RESTRICT deletion and therefore
-- preserve every rate version that has ever participated in a financial flow.
ALTER TABLE room_rate_profiles
    DROP CONSTRAINT fk_room_rate_profile_room_type;

ALTER TABLE room_rate_profiles
    ADD CONSTRAINT fk_room_rate_profile_room_type
        FOREIGN KEY (room_type_id) REFERENCES room_types(id) ON DELETE CASCADE;

CREATE OR REPLACE FUNCTION protect_room_rate_profile_version()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- PostgreSQL removes the parent row before executing the cascading
        -- child delete. This exception permits that one controlled lifecycle
        -- operation while still rejecting direct deletion of rate history.
        IF EXISTS (
            SELECT 1 FROM room_types WHERE id = OLD.room_type_id
        ) THEN
            RAISE EXCEPTION 'room_rate_profiles is versioned and cannot be deleted directly'
                USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.room_type_id IS DISTINCT FROM NEW.room_type_id
       OR OLD.stay_policy_version_id
            IS DISTINCT FROM NEW.stay_policy_version_id
       OR OLD.profile_version IS DISTINCT FROM NEW.profile_version
       OR OLD.included_guests IS DISTINCT FROM NEW.included_guests
       OR OLD.first_block_minutes IS DISTINCT FROM NEW.first_block_minutes
       OR OLD.first_block_price IS DISTINCT FROM NEW.first_block_price
       OR OLD.extra_unit_minutes IS DISTINCT FROM NEW.extra_unit_minutes
       OR OLD.extra_unit_price IS DISTINCT FROM NEW.extra_unit_price
       OR OLD.overnight_price IS DISTINCT FROM NEW.overnight_price
       OR OLD.daily_price IS DISTINCT FROM NEW.daily_price
       OR OLD.extra_guest_price IS DISTINCT FROM NEW.extra_guest_price
       OR OLD.extra_guest_billing_mode
            IS DISTINCT FROM NEW.extra_guest_billing_mode
       OR OLD.effective_from_utc IS DISTINCT FROM NEW.effective_from_utc
       OR OLD.created_by_user_id IS DISTINCT FROM NEW.created_by_user_id
       OR OLD.created_at_utc IS DISTINCT FROM NEW.created_at_utc THEN
        RAISE EXCEPTION 'room_rate_profiles financial fields are immutable; create a new version'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$;
