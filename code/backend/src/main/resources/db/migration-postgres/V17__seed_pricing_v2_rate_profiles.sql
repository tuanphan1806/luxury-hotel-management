-- Production-safe Pricing V2 master rates.
--
-- Render normally runs with APP_SEED_MASTER_DATA_ENABLED=false after the
-- initial bootstrap. Therefore rate profiles required by the canary must be
-- introduced by an additive data migration, not by relying on DataSeeder.
--
-- Existing financial configuration is authoritative: a room type that already
-- has any profile is never changed or given an additional open version here.
--
-- Older compatible databases may still have the original max_guests=2 seed for
-- every room type. Raise only undersized capacities to the approved Pricing V2
-- catalogue minimum before inserting profiles; never lower an operator's
-- larger configured capacity.
CREATE TEMPORARY TABLE pricing_v2_rate_seed (
    room_type_code,
    required_max_guests,
    included_guests,
    first_block_price,
    extra_unit_price,
    overnight_price,
    daily_price
) ON COMMIT DROP AS
VALUES
    ('STANDARD', 2, 1, 70000.00, 20000.00, 170000.00, 300000.00),
    ('DELUXE', 3, 2, 100000.00, 25000.00, 220000.00, 400000.00),
    ('EXECUTIVE', 3, 2, 120000.00, 30000.00, 270000.00, 480000.00),
    ('SUITE', 4, 2, 150000.00, 35000.00, 350000.00, 600000.00),
    ('FAMILY', 6, 4, 130000.00, 30000.00, 330000.00, 550000.00),
    ('PRESIDENTIAL', 6, 4, 200000.00, 50000.00, 450000.00, 850000.00);

UPDATE room_types room_type
SET max_guests = GREATEST(room_type.max_guests,
                          rate_seed.required_max_guests)
FROM pricing_v2_rate_seed rate_seed
WHERE room_type.code = rate_seed.room_type_code
  AND room_type.max_guests < rate_seed.required_max_guests;

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
    active,
    created_at_utc
)
SELECT
    room_type.id,
    policy.id,
    1,
    rate_seed.included_guests,
    120,
    rate_seed.first_block_price,
    60,
    rate_seed.extra_unit_price,
    rate_seed.overnight_price,
    rate_seed.daily_price,
    50000.00,
    'PER_PACKAGE_CYCLE',
    TIMESTAMPTZ '2026-01-01 00:00:00+00',
    true,
    TIMESTAMPTZ '2026-07-27 00:00:00+00'
FROM pricing_v2_rate_seed rate_seed
JOIN room_types room_type
  ON room_type.code = rate_seed.room_type_code
JOIN stay_policy_versions policy
  ON policy.policy_code = 'DEFAULT_MOTEL_POLICY'
 AND policy.policy_version = 1
 AND policy.active = true
 AND policy.effective_to_utc IS NULL
WHERE NOT EXISTS (
    SELECT 1
    FROM room_rate_profiles existing
    WHERE existing.room_type_id = room_type.id
);

DROP TABLE pricing_v2_rate_seed;
