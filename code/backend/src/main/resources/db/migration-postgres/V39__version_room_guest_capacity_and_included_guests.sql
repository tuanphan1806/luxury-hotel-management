-- Separate the advertised/suitable occupancy from the hard occupancy limit.
--
-- For the six managed room types, the rate now includes the room type's
-- previous capacity. Each canonical room type receives its own independently
-- configured hard limit; guests above the suitable occupancy use the
-- versioned extra-guest surcharge. Historical profiles, quotes, reservations
-- and invoices remain untouched.

CREATE TEMP TABLE pricing_v39_room_types_to_upgrade
ON COMMIT DROP
AS
WITH configured_capacity(room_type_code, suitable_guests, maximum_guests) AS (
    VALUES
        ('STANDARD', 2, 3),
        ('DELUXE', 3, 4),
        ('EXECUTIVE', 3, 4),
        ('SUITE', 4, 5),
        ('FAMILY', 6, 7),
        ('PRESIDENTIAL', 6, 7)
)
SELECT
    room_type.id AS room_type_id,
    configured_capacity.suitable_guests AS next_included_guests,
    configured_capacity.maximum_guests AS next_max_guests
FROM room_types room_type
JOIN configured_capacity
  ON configured_capacity.room_type_code = UPPER(room_type.code)
WHERE room_type.max_guests < configured_capacity.maximum_guests
   OR EXISTS (
       SELECT 1
       FROM room_rate_profiles current_profile
       WHERE current_profile.room_type_id = room_type.id
         AND current_profile.active = true
         AND current_profile.effective_from_utc <= CURRENT_TIMESTAMP
         AND (
             current_profile.effective_to_utc IS NULL
             OR current_profile.effective_to_utc > CURRENT_TIMESTAMP
         )
         AND current_profile.included_guests <>
             configured_capacity.suitable_guests
   );

CREATE TEMP TABLE pricing_v39_profiles_to_version
ON COMMIT DROP
AS
SELECT
    profile.*,
    capacity.next_included_guests
FROM room_rate_profiles profile
JOIN pricing_v39_room_types_to_upgrade capacity
  ON capacity.room_type_id = profile.room_type_id
WHERE profile.active = true
  AND profile.included_guests <> capacity.next_included_guests
  AND (
      profile.effective_to_utc IS NULL
      OR profile.effective_to_utc > CURRENT_TIMESTAMP
  );

DO $$
DECLARE
    cutover_utc timestamp with time zone := CURRENT_TIMESTAMP;
BEGIN
    -- Increasing capacity cannot invalidate any existing reservation. Never
    -- reduce an administrator's larger configured capacity during rollout.
    -- The
    -- database guard still prevents a future included-guests value from
    -- exceeding this new hard capacity.
    UPDATE room_types room_type
    SET max_guests = capacity.next_max_guests
    FROM pricing_v39_room_types_to_upgrade capacity
    WHERE room_type.id = capacity.room_type_id
      AND room_type.max_guests < capacity.next_max_guests;

    -- Close only current/future versions. Historical versions are immutable
    -- evidence and must continue to reproduce their original amounts.
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
        FROM pricing_v39_profiles_to_version source_profile
    );

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
        FROM pricing_v39_profiles_to_version previous_profile
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
        previous_profile.stay_policy_version_id,
        previous_profile.next_profile_version,
        previous_profile.next_included_guests,
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

    IF EXISTS (
        SELECT 1
        FROM room_types room_type
        JOIN room_rate_profiles profile
          ON profile.room_type_id = room_type.id
         AND profile.active = true
         AND profile.effective_from_utc <= cutover_utc
         AND (
             profile.effective_to_utc IS NULL
             OR profile.effective_to_utc > cutover_utc
         )
        JOIN (
            VALUES
                ('STANDARD', 2, 3),
                ('DELUXE', 3, 4),
                ('EXECUTIVE', 3, 4),
                ('SUITE', 4, 5),
                ('FAMILY', 6, 7),
                ('PRESIDENTIAL', 6, 7)
        ) AS capacity(room_type_code, next_included_guests, next_max_guests)
          ON capacity.room_type_code = UPPER(room_type.code)
        WHERE profile.included_guests <> capacity.next_included_guests
           OR room_type.max_guests < capacity.next_max_guests
    ) THEN
        RAISE EXCEPTION
            'V39 failed to align included guests and hard room capacity'
            USING ERRCODE = '23514';
    END IF;
END;
$$;
