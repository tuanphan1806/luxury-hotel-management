-- Pricing must resolve to exactly one active policy/rate at any instant.
-- The original partial unique indexes only prevent two open-ended versions;
-- they do not reject overlapping finite validity windows.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM stay_policy_versions left_policy
        JOIN stay_policy_versions right_policy
          ON right_policy.policy_code = left_policy.policy_code
         AND right_policy.id > left_policy.id
         AND right_policy.active = true
         AND left_policy.active = true
         AND tstzrange(
                left_policy.effective_from_utc,
                left_policy.effective_to_utc,
                '[)')
             && tstzrange(
                right_policy.effective_from_utc,
                right_policy.effective_to_utc,
                '[)')
    ) THEN
        RAISE EXCEPTION
            'Pricing V2 preflight failed: active stay-policy windows overlap'
            USING ERRCODE = '23505';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM room_rate_profiles left_rate
        JOIN room_rate_profiles right_rate
          ON right_rate.room_type_id = left_rate.room_type_id
         AND right_rate.id > left_rate.id
         AND right_rate.active = true
         AND left_rate.active = true
         AND tstzrange(
                left_rate.effective_from_utc,
                left_rate.effective_to_utc,
                '[)')
             && tstzrange(
                right_rate.effective_from_utc,
                right_rate.effective_to_utc,
                '[)')
    ) THEN
        RAISE EXCEPTION
            'Pricing V2 preflight failed: active room-rate windows overlap'
            USING ERRCODE = '23505';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION reject_overlapping_stay_policy_window()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.active AND EXISTS (
        SELECT 1
        FROM stay_policy_versions existing
        WHERE existing.policy_code = NEW.policy_code
          AND existing.active = true
          AND existing.id IS DISTINCT FROM NEW.id
          AND tstzrange(
                existing.effective_from_utc,
                existing.effective_to_utc,
                '[)')
              && tstzrange(
                NEW.effective_from_utc,
                NEW.effective_to_utc,
                '[)')
    ) THEN
        RAISE EXCEPTION
            'Active stay-policy validity window overlaps an existing version'
            USING ERRCODE = '23505';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_stay_policy_versions_no_overlap
BEFORE INSERT OR UPDATE OF policy_code, effective_from_utc, effective_to_utc, active
ON stay_policy_versions
FOR EACH ROW
EXECUTE FUNCTION reject_overlapping_stay_policy_window();

CREATE OR REPLACE FUNCTION reject_overlapping_room_rate_window()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.active AND EXISTS (
        SELECT 1
        FROM room_rate_profiles existing
        WHERE existing.room_type_id = NEW.room_type_id
          AND existing.active = true
          AND existing.id IS DISTINCT FROM NEW.id
          AND tstzrange(
                existing.effective_from_utc,
                existing.effective_to_utc,
                '[)')
              && tstzrange(
                NEW.effective_from_utc,
                NEW.effective_to_utc,
                '[)')
    ) THEN
        RAISE EXCEPTION
            'Active room-rate validity window overlaps an existing version'
            USING ERRCODE = '23505';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_room_rate_profiles_no_overlap
BEFORE INSERT OR UPDATE OF room_type_id, effective_from_utc, effective_to_utc, active
ON room_rate_profiles
FOR EACH ROW
EXECUTE FUNCTION reject_overlapping_room_rate_window();
