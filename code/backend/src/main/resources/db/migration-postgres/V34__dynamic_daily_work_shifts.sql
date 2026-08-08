-- Daily work shifts are explicitly opened by ADMIN.
-- A reusable template no longer implies that the shift exists on every date.
-- Existing assignments/registration requests are backfilled so historical
-- attendance and cashier-shift links remain intact.

ALTER TABLE work_shift_requirements
    ADD COLUMN shift_code_snapshot VARCHAR(32),
    ADD COLUMN shift_name_snapshot VARCHAR(100),
    ADD COLUMN shift_color_snapshot VARCHAR(7),
    ADD COLUMN start_time_snapshot TIME WITHOUT TIME ZONE,
    ADD COLUMN end_time_snapshot TIME WITHOUT TIME ZONE,
    ADD COLUMN check_in_early_minutes_snapshot INTEGER,
    ADD COLUMN late_tolerance_minutes_snapshot INTEGER,
    ADD COLUMN sort_order_snapshot INTEGER,
    ADD COLUMN registration_open BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN assignment_policy_snapshot VARCHAR(24) NOT NULL DEFAULT 'MANUAL_APPROVAL',
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    ADD COLUMN created_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN cancelled_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN cancelled_at_utc TIMESTAMPTZ,
    ADD COLUMN completed_at_utc TIMESTAMPTZ,
    ADD COLUMN cancellation_reason VARCHAR(500);

UPDATE work_shift_requirements requirement
SET shift_code_snapshot = template.code,
    shift_name_snapshot = template.name,
    shift_color_snapshot = template.color,
    start_time_snapshot = template.start_time,
    end_time_snapshot = template.end_time,
    check_in_early_minutes_snapshot = template.check_in_early_minutes,
    late_tolerance_minutes_snapshot = template.late_tolerance_minutes,
    sort_order_snapshot = template.sort_order
FROM work_shift_templates template
WHERE template.id = requirement.shift_template_id;

WITH referenced_slots AS (
    SELECT assignment.shift_template_id,
           assignment.work_date,
           COUNT(*) FILTER (WHERE assignment.status <> 'CANCELLED') AS assigned_count,
           MIN(assignment.created_by) AS created_by
    FROM work_schedule_assignments assignment
    GROUP BY assignment.shift_template_id, assignment.work_date
    UNION ALL
    SELECT request.shift_template_id,
           request.work_date,
           0::BIGINT AS assigned_count,
           NULL::BIGINT AS created_by
    FROM work_shift_registration_requests request
    GROUP BY request.shift_template_id, request.work_date
), consolidated_slots AS (
    SELECT shift_template_id,
           work_date,
           GREATEST(1, MAX(assigned_count))::INTEGER AS required_staff,
           MIN(created_by) AS created_by
    FROM referenced_slots
    GROUP BY shift_template_id, work_date
)
INSERT INTO work_shift_requirements (
    shift_template_id,
    work_date,
    required_staff,
    shift_code_snapshot,
    shift_name_snapshot,
    shift_color_snapshot,
    start_time_snapshot,
    end_time_snapshot,
    check_in_early_minutes_snapshot,
    late_tolerance_minutes_snapshot,
    sort_order_snapshot,
    registration_open,
    assignment_policy_snapshot,
    status,
    created_by,
    created_at_utc,
    updated_at_utc
)
SELECT slot.shift_template_id,
       slot.work_date,
       slot.required_staff,
       template.code,
       template.name,
       template.color,
       template.start_time,
       template.end_time,
       template.check_in_early_minutes,
       template.late_tolerance_minutes,
       template.sort_order,
       TRUE,
       'MANUAL_APPROVAL',
       'OPEN',
       slot.created_by,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM consolidated_slots slot
JOIN work_shift_templates template ON template.id = slot.shift_template_id
ON CONFLICT (shift_template_id, work_date) DO NOTHING;

ALTER TABLE work_shift_requirements
    ALTER COLUMN shift_code_snapshot SET NOT NULL,
    ALTER COLUMN shift_name_snapshot SET NOT NULL,
    ALTER COLUMN shift_color_snapshot SET NOT NULL,
    ALTER COLUMN start_time_snapshot SET NOT NULL,
    ALTER COLUMN end_time_snapshot SET NOT NULL,
    ALTER COLUMN check_in_early_minutes_snapshot SET NOT NULL,
    ALTER COLUMN late_tolerance_minutes_snapshot SET NOT NULL,
    ALTER COLUMN sort_order_snapshot SET NOT NULL;

-- The former model allowed zero as an implicit "not configured" value.
-- Once a row becomes an explicit daily shift it must have real capacity.
UPDATE work_shift_requirements
SET required_staff = 1
WHERE required_staff < 1;

ALTER TABLE work_shift_requirements
    ADD CONSTRAINT chk_work_daily_shift_status
        CHECK (status IN ('OPEN', 'COMPLETED', 'CANCELLED')),
    ADD CONSTRAINT chk_work_daily_shift_assignment_policy
        CHECK (assignment_policy_snapshot IN ('ADMIN_ONLY', 'MANUAL_APPROVAL', 'AUTO_ASSIGN')),
    ADD CONSTRAINT chk_work_daily_shift_time
        CHECK (start_time_snapshot <> end_time_snapshot),
    ADD CONSTRAINT chk_work_daily_shift_snapshot_minutes
        CHECK (check_in_early_minutes_snapshot BETWEEN 0 AND 240
            AND late_tolerance_minutes_snapshot BETWEEN 0 AND 240),
    ADD CONSTRAINT chk_work_daily_shift_color
        CHECK (shift_color_snapshot ~ '^#[0-9A-Fa-f]{6}$'),
    ADD CONSTRAINT chk_work_daily_shift_required_staff
        CHECK (required_staff BETWEEN 1 AND 100) NOT VALID,
    ADD CONSTRAINT chk_work_daily_shift_cancelled_fields
        CHECK ((status = 'CANCELLED') = (cancelled_at_utc IS NOT NULL)),
    ADD CONSTRAINT chk_work_daily_shift_completed_fields
        CHECK ((status = 'COMPLETED') = (completed_at_utc IS NOT NULL));

-- AUTO_ASSIGN is approved by policy rather than by a human administrator.
-- Keep reviewed_at and assignment mandatory while allowing reviewed_by to be
-- null so the audit trail does not pretend that an ADMIN approved it.
ALTER TABLE work_shift_registration_requests
    DROP CONSTRAINT chk_work_shift_registration_review;

ALTER TABLE work_shift_registration_requests
    ADD CONSTRAINT chk_work_shift_registration_review
        CHECK (
            (status = 'PENDING'
                AND reviewed_by IS NULL
                AND reviewed_at_utc IS NULL
                AND assignment_id IS NULL)
            OR (status = 'CANCELLED'
                AND reviewed_by IS NULL
                AND reviewed_at_utc IS NULL
                AND assignment_id IS NULL)
            OR (status = 'REJECTED'
                AND reviewed_by IS NOT NULL
                AND reviewed_at_utc IS NOT NULL
                AND assignment_id IS NULL)
            OR (status = 'APPROVED'
                AND reviewed_at_utc IS NOT NULL
                AND assignment_id IS NOT NULL)
        );

ALTER TABLE work_shift_requirements
    DROP CONSTRAINT chk_work_shift_required_staff;

ALTER TABLE work_shift_requirements
    VALIDATE CONSTRAINT chk_work_daily_shift_required_staff;

CREATE INDEX idx_work_daily_shift_status_date
    ON work_shift_requirements(status, work_date, sort_order_snapshot);
