-- Support the staff calendar aggregate without loading other employees'
-- assignment entities into the application process.
CREATE INDEX IF NOT EXISTS idx_work_schedule_active_date_template
    ON work_schedule_assignments(work_date, shift_template_id)
    WHERE status <> 'CANCELLED';
