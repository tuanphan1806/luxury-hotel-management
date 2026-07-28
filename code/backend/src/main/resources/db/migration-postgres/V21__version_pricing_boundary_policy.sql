-- Version the pricing boundary policy without rewriting historical quotes,
-- commitments or reservation snapshots.
--
-- Historical policies retain the pre-V21 behavior:
--   * an early-morning arrival may enter OVERNIGHT with no minimum duration;
--   * a post-24-hour remainder starts after the grace window.
--
-- New quotes use:
--   * a 120-minute minimum before an early-morning same-day stay may enter
--     OVERNIGHT;
--   * a chargeable remainder cycle that starts at the exact 24-hour boundary.

ALTER TABLE stay_policy_versions
    ADD COLUMN early_morning_overnight_minimum_minutes integer,
    ADD COLUMN remainder_cycle_starts_at_boundary boolean;

UPDATE stay_policy_versions
SET early_morning_overnight_minimum_minutes = 0,
    remainder_cycle_starts_at_boundary = false;

ALTER TABLE stay_policy_versions
    ALTER COLUMN early_morning_overnight_minimum_minutes SET NOT NULL,
    ALTER COLUMN early_morning_overnight_minimum_minutes SET DEFAULT 120,
    ALTER COLUMN remainder_cycle_starts_at_boundary SET NOT NULL,
    ALTER COLUMN remainder_cycle_starts_at_boundary SET DEFAULT true,
    ADD CONSTRAINT chk_stay_policy_early_morning_minimum
        CHECK (early_morning_overnight_minimum_minutes
            BETWEEN 0 AND daily_duration_minutes);

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

-- Preserve every still-relevant rate window attached to the policy being
-- versioned, not only an open-ended profile. Operators may have a finite
-- current rate and one or more adjacent future rates already scheduled.
CREATE TEMP TABLE pricing_v21_profiles_to_version
ON COMMIT DROP
AS
SELECT profile.*
FROM room_rate_profiles profile
JOIN stay_policy_versions policy
  ON policy.id = profile.stay_policy_version_id
WHERE policy.policy_code = 'DEFAULT_MOTEL_POLICY'
  AND policy.active = true
  AND policy.effective_to_utc IS NULL
  AND profile.active = true
  AND (
      profile.effective_to_utc IS NULL
      OR profile.effective_to_utc > CURRENT_TIMESTAMP
  );

DO $$
DECLARE
    cutover_utc timestamp with time zone := CURRENT_TIMESTAMP;
    previous_policy stay_policy_versions%ROWTYPE;
    next_policy_id bigint;
    next_policy_version integer;
BEGIN
    SELECT policy.*
    INTO previous_policy
    FROM stay_policy_versions policy
    WHERE policy.policy_code = 'DEFAULT_MOTEL_POLICY'
      AND policy.active = true
      AND policy.effective_to_utc IS NULL
    ORDER BY policy.policy_version DESC
    LIMIT 1
    FOR UPDATE;

    -- Do not silently re-enable a policy disabled by an operator.
    IF NOT FOUND THEN
        RETURN;
    END IF;

    IF previous_policy.early_morning_overnight_minimum_minutes = 120
       AND previous_policy.remainder_cycle_starts_at_boundary = true THEN
        RETURN;
    END IF;

    IF cutover_utc <= previous_policy.effective_from_utc THEN
        cutover_utc := previous_policy.effective_from_utc
                + INTERVAL '1 microsecond';
    END IF;

    UPDATE room_rate_profiles profile
    SET active = false,
        effective_to_utc = CASE
            -- Close a rate that has already started at the cutover so its
            -- historical window remains truthful. A future rate cannot be
            -- shortened to before its immutable effective_from timestamp;
            -- it is simply deactivated and cloned unchanged below.
            WHEN profile.effective_from_utc < cutover_utc
                THEN LEAST(
                    COALESCE(profile.effective_to_utc, cutover_utc),
                    cutover_utc)
            ELSE profile.effective_to_utc
        END
    WHERE profile.id IN (
        SELECT source_profile.id
        FROM pricing_v21_profiles_to_version source_profile
    );

    UPDATE stay_policy_versions
    SET active = false,
        effective_to_utc = cutover_utc
    WHERE id = previous_policy.id;

    SELECT COALESCE(MAX(policy.policy_version), 0) + 1
    INTO next_policy_version
    FROM stay_policy_versions policy
    WHERE policy.policy_code = previous_policy.policy_code;

    INSERT INTO stay_policy_versions (
        policy_code,
        policy_version,
        grace_minutes,
        overnight_start_time,
        overnight_early_morning_end,
        early_morning_overnight_minimum_minutes,
        overnight_hard_checkout_time,
        overnight_maximum_minutes,
        daily_threshold_minutes,
        daily_duration_minutes,
        turnover_buffer_minutes,
        remainder_cycle_starts_at_boundary,
        inventory_protection_mode,
        effective_from_utc,
        effective_to_utc,
        active,
        created_by_user_id,
        created_at_utc
    ) VALUES (
        previous_policy.policy_code,
        next_policy_version,
        previous_policy.grace_minutes,
        previous_policy.overnight_start_time,
        previous_policy.overnight_early_morning_end,
        120,
        previous_policy.overnight_hard_checkout_time,
        previous_policy.overnight_maximum_minutes,
        previous_policy.daily_threshold_minutes,
        previous_policy.daily_duration_minutes,
        previous_policy.turnover_buffer_minutes,
        true,
        previous_policy.inventory_protection_mode,
        cutover_utc,
        NULL,
        true,
        NULL,
        cutover_utc
    )
    RETURNING id INTO next_policy_id;

    WITH profiles_to_clone AS (
        SELECT
            previous_profile.*,
            CASE
                WHEN previous_profile.effective_from_utc < cutover_utc
                    THEN cutover_utc
                ELSE previous_profile.effective_from_utc
            END AS cloned_effective_from_utc,
            ROW_NUMBER() OVER (
                PARTITION BY previous_profile.room_type_id
                ORDER BY
                    CASE
                        WHEN previous_profile.effective_from_utc < cutover_utc
                            THEN cutover_utc
                        ELSE previous_profile.effective_from_utc
                    END,
                    previous_profile.id
            ) AS version_offset
        FROM pricing_v21_profiles_to_version previous_profile
        WHERE previous_profile.effective_to_utc IS NULL
           OR previous_profile.effective_to_utc > cutover_utc
    ),
    versioned_profiles AS (
        SELECT
            profile_to_clone.*,
            CAST((
                SELECT COALESCE(MAX(all_versions.profile_version), 0)
                FROM room_rate_profiles all_versions
                WHERE all_versions.room_type_id =
                        profile_to_clone.room_type_id
            ) + profile_to_clone.version_offset AS integer)
                AS next_profile_version
        FROM profiles_to_clone profile_to_clone
    )
    INSERT INTO room_rate_profiles (
        room_type_id,
        stay_policy_version_id,
        profile_version,
        included_guests,
        first_block_minutes,
        first_block_price,
        extra_unit_minutes,
        extra_unit_price,
        overnight_price,
        daily_price,
        extra_guest_price,
        extra_guest_billing_mode,
        effective_from_utc,
        effective_to_utc,
        active,
        created_by_user_id,
        created_at_utc
    )
    SELECT
        previous_profile.room_type_id,
        next_policy_id,
        previous_profile.next_profile_version,
        previous_profile.included_guests,
        previous_profile.first_block_minutes,
        previous_profile.first_block_price,
        previous_profile.extra_unit_minutes,
        previous_profile.extra_unit_price,
        previous_profile.overnight_price,
        previous_profile.daily_price,
        previous_profile.extra_guest_price,
        previous_profile.extra_guest_billing_mode,
        previous_profile.cloned_effective_from_utc,
        previous_profile.effective_to_utc,
        true,
        NULL,
        cutover_utc
    FROM versioned_profiles previous_profile;
END;
$$;
