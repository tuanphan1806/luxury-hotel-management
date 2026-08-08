-- Common hotel operating shifts. Upsert also repairs an environment where a
-- canonical template was removed manually. Existing daily-shift and
-- assignment snapshots intentionally remain intact.
INSERT INTO work_shift_templates (
    code, name, start_time, end_time, check_in_early_minutes,
    late_tolerance_minutes, color, sort_order, active
) VALUES
    ('SANG', 'Ca sáng', TIME '07:00', TIME '12:00', 30, 10, '#B8944F', 10, TRUE),
    ('CHIEU', 'Ca chiều', TIME '13:00', TIME '18:00', 30, 10, '#2F7D78', 20, TRUE),
    ('TOI', 'Ca tối', TIME '18:00', TIME '22:00', 30, 10, '#4E5D8C', 30, TRUE)
ON CONFLICT (code) DO UPDATE
SET name = EXCLUDED.name,
    start_time = EXCLUDED.start_time,
    end_time = EXCLUDED.end_time,
    check_in_early_minutes = EXCLUDED.check_in_early_minutes,
    late_tolerance_minutes = EXCLUDED.late_tolerance_minutes,
    color = EXCLUDED.color,
    sort_order = EXCLUDED.sort_order,
    active = TRUE,
    updated_at_utc = CURRENT_TIMESTAMP;

-- Custom templates use the same fixed period palette. ADMIN can still edit
-- names and times, while the start time determines the recognition colour.
UPDATE work_shift_templates
SET color = CASE
        WHEN start_time >= TIME '05:00' AND start_time < TIME '13:00' THEN '#B8944F'
        WHEN start_time >= TIME '13:00' AND start_time < TIME '18:00' THEN '#2F7D78'
        ELSE '#4E5D8C'
    END,
    sort_order = CASE
        WHEN start_time >= TIME '05:00' AND start_time < TIME '13:00' THEN 10
        WHEN start_time >= TIME '13:00' AND start_time < TIME '18:00' THEN 20
        ELSE 30
    END,
    updated_at_utc = CURRENT_TIMESTAMP;
