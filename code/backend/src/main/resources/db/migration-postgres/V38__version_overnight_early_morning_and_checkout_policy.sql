-- Version the overnight policy for new quotes without rewriting historical
-- commitments, reservation snapshots or invoices.
--
-- New operational rules:
--   * check-in from 00:00 through 04:59 enters OVERNIGHT immediately;
--   * check-in from 05:00 is no longer an early-morning overnight trigger;
--   * every overnight entitlement has a hard checkout at 10:00;
--   * the existing 15-minute grace and per-room extra-hour rate remain intact.

ALTER TABLE stay_policy_versions
    ALTER COLUMN early_morning_overnight_minimum_minutes SET DEFAULT 0;

-- Preserve every active current/future rate window. New versions must point to
-- the new immutable policy while historical rates remain attached to the old
-- policy used by their quotes and reservations.
CREATE TEMP TABLE pricing_v38_profiles_to_version
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

    -- Never silently re-enable an operator-disabled pricing policy.
    IF NOT FOUND THEN
        RETURN;
    END IF;

    IF previous_policy.overnight_early_morning_end = TIME '05:00'
       AND previous_policy.early_morning_overnight_minimum_minutes = 0
       AND previous_policy.overnight_hard_checkout_time = TIME '10:00' THEN
        RETURN;
    END IF;

    IF cutover_utc <= previous_policy.effective_from_utc THEN
        cutover_utc := previous_policy.effective_from_utc
                + INTERVAL '1 microsecond';
    END IF;

    UPDATE room_rate_profiles profile
    SET active = false,
        effective_to_utc = CASE
            WHEN profile.effective_from_utc < cutover_utc
                THEN LEAST(
                    COALESCE(profile.effective_to_utc, cutover_utc),
                    cutover_utc)
            ELSE profile.effective_to_utc
        END
    WHERE profile.id IN (
        SELECT source_profile.id
        FROM pricing_v38_profiles_to_version source_profile
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
        overnight_refund_lock_time,
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
        TIME '05:00',
        0,
        previous_policy.overnight_refund_lock_time,
        TIME '10:00',
        previous_policy.overnight_maximum_minutes,
        previous_policy.daily_threshold_minutes,
        previous_policy.daily_duration_minutes,
        previous_policy.turnover_buffer_minutes,
        previous_policy.remainder_cycle_starts_at_boundary,
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
        FROM pricing_v38_profiles_to_version previous_profile
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
