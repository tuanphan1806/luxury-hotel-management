-- Version the overnight arrival policy from 20:00-06:00 to 20:00-08:00.
--
-- Historical quotes, commitments and reservation snapshots keep referencing
-- policy/rate version 1. Only new quotes created after this atomic cutover use
-- the new policy and cloned rate profiles.

CREATE TEMP TABLE pricing_v18_open_profiles
ON COMMIT DROP
AS
SELECT profile.id
FROM room_rate_profiles profile
JOIN stay_policy_versions policy
  ON policy.id = profile.stay_policy_version_id
WHERE policy.policy_code = 'DEFAULT_MOTEL_POLICY'
  AND policy.active = true
  AND policy.effective_to_utc IS NULL
  AND profile.active = true
  AND profile.effective_to_utc IS NULL;

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

    -- Respect an operator-disabled pricing policy. Pricing V2 remains
    -- unavailable instead of silently re-enabling financial configuration.
    IF NOT FOUND THEN
        RETURN;
    END IF;

    -- A database that was already provisioned with the desired policy needs
    -- no additional financial version.
    IF previous_policy.overnight_early_morning_end = TIME '08:00' THEN
        RETURN;
    END IF;

    IF cutover_utc <= previous_policy.effective_from_utc THEN
        cutover_utc := previous_policy.effective_from_utc
                + INTERVAL '1 microsecond';
    END IF;

    UPDATE room_rate_profiles profile
    SET active = false,
        effective_to_utc = cutover_utc
    WHERE profile.id IN (
        SELECT open_profile.id
        FROM pricing_v18_open_profiles open_profile
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
        overnight_hard_checkout_time,
        overnight_maximum_minutes,
        daily_threshold_minutes,
        daily_duration_minutes,
        turnover_buffer_minutes,
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
        TIME '08:00',
        previous_policy.overnight_hard_checkout_time,
        previous_policy.overnight_maximum_minutes,
        previous_policy.daily_threshold_minutes,
        previous_policy.daily_duration_minutes,
        previous_policy.turnover_buffer_minutes,
        previous_policy.inventory_protection_mode,
        cutover_utc,
        NULL,
        true,
        NULL,
        cutover_utc
    )
    RETURNING id INTO next_policy_id;

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
        (
            SELECT COALESCE(MAX(all_versions.profile_version), 0) + 1
            FROM room_rate_profiles all_versions
            WHERE all_versions.room_type_id =
                    previous_profile.room_type_id
        ),
        previous_profile.included_guests,
        previous_profile.first_block_minutes,
        previous_profile.first_block_price,
        previous_profile.extra_unit_minutes,
        previous_profile.extra_unit_price,
        previous_profile.overnight_price,
        previous_profile.daily_price,
        previous_profile.extra_guest_price,
        previous_profile.extra_guest_billing_mode,
        cutover_utc,
        NULL,
        true,
        NULL,
        cutover_utc
    FROM room_rate_profiles previous_profile
    JOIN pricing_v18_open_profiles open_profile
      ON open_profile.id = previous_profile.id;
END;
$$;
