-- Usernames are login identifiers and therefore must be unique regardless of
-- letter case or surrounding whitespace. Application checks already use
-- IgnoreCase lookups, but the legacy PostgreSQL constraint only protected the
-- exact stored value. This migration closes that race at the database boundary.

CREATE TEMP TABLE username_conflict_remediations (
    user_id bigint PRIMARY KEY,
    old_username varchar(255) NOT NULL,
    new_username varchar(255) NOT NULL
) ON COMMIT DROP;

DO $$
DECLARE
    conflict_record record;
    base_candidate text;
    candidate text;
    suffix integer;
BEGIN
    FOR conflict_record IN
        WITH ranked_usernames AS (
            SELECT
                id,
                username,
                row_number() OVER (
                    PARTITION BY lower(btrim(username))
                    ORDER BY
                        CASE type
                            WHEN 'ADMIN' THEN 0
                            WHEN 'STAFF' THEN 1
                            WHEN 'CUSTOMER' THEN 2
                            ELSE 3
                        END,
                        CASE status
                            WHEN 'ACTIVE' THEN 0
                            WHEN 'PENDING_VERIFICATION' THEN 1
                            ELSE 2
                        END,
                        CASE WHEN email_verified THEN 0 ELSE 1 END,
                        id
                ) AS username_rank
            FROM users
        )
        SELECT id, username
        FROM ranked_usernames
        WHERE username_rank > 1 OR btrim(username) = ''
        ORDER BY id
    LOOP
        base_candidate :=
            left(CASE
                    WHEN btrim(conflict_record.username) = '' THEN 'user'
                    ELSE btrim(conflict_record.username)
                 END, 210)
            || '__legacy_' || conflict_record.id;
        candidate := base_candidate;
        suffix := 0;

        WHILE EXISTS (
            SELECT 1
            FROM users
            WHERE id <> conflict_record.id
              AND lower(btrim(username)) = lower(candidate)
        ) LOOP
            suffix := suffix + 1;
            candidate := left(base_candidate, 245) || '_' || suffix;
        END LOOP;

        INSERT INTO username_conflict_remediations(user_id, old_username, new_username)
        VALUES (conflict_record.id, conflict_record.username, candidate);

        UPDATE users
        SET username = candidate,
            updated_at = timezone('UTC', now())
        WHERE id = conflict_record.id;
    END LOOP;
END
$$;

-- Canonical storage prevents a username that only differs by accidental
-- leading/trailing whitespace from being displayed or audited inconsistently.
UPDATE users
SET username = btrim(username),
    updated_at = timezone('UTC', now())
WHERE username <> btrim(username);

INSERT INTO reservation_audit_logs (
    target_type,
    target_id,
    action,
    action_code,
    actor_name,
    actor_role,
    details,
    occurred_at_utc,
    created_at,
    updated_at,
    old_value_json,
    new_value_json,
    detail_json,
    risk_level,
    category,
    dedup_key
)
SELECT
    'USER',
    user_id::text,
    'USERNAME_CONFLICT_REMEDIATED',
    'USERNAME_CONFLICT_REMEDIATED',
    'SYSTEM',
    'SYSTEM',
    'Chuẩn hóa username trùng không phân biệt hoa thường',
    now(),
    timezone('UTC', now()),
    timezone('UTC', now()),
    jsonb_build_object('username', old_username),
    jsonb_build_object('username', new_username),
    jsonb_build_object(
        'reason', 'CASE_INSENSITIVE_USERNAME_CONFLICT',
        'emailLoginAvailable', true
    ),
    'MEDIUM',
    'MANAGEMENT',
    'migration-v29-username-' || user_id
FROM username_conflict_remediations;

ALTER TABLE users
    ADD CONSTRAINT chk_users_username_not_blank_and_trimmed
        CHECK (username = btrim(username) AND btrim(username) <> '');

CREATE UNIQUE INDEX uk_users_username_case_insensitive
    ON users (lower(username));

COMMENT ON INDEX uk_users_username_case_insensitive IS
    'Login usernames are unique without regard to letter case.';
