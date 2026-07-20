-- Run with application writes stopped, after importing rows with their legacy
-- primary keys and before post-cutover-validate.sql.
--
-- PostgreSQL identity sequences do not automatically advance when COPY/INSERT
-- supplies explicit ids. Without this step, the first new application write
-- can reuse an imported id and fail with a duplicate-key error.

DO $$
DECLARE
    identity_column RECORD;
    sequence_name TEXT;
    highest_id BIGINT;
BEGIN
    FOR identity_column IN
        SELECT table_schema, table_name, column_name
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND is_identity = 'YES'
        ORDER BY table_name, ordinal_position
    LOOP
        sequence_name := pg_get_serial_sequence(
            format('%I.%I', identity_column.table_schema, identity_column.table_name),
            identity_column.column_name
        );

        IF sequence_name IS NULL THEN
            RAISE EXCEPTION 'Missing identity sequence for %.%.%',
                identity_column.table_schema,
                identity_column.table_name,
                identity_column.column_name;
        END IF;

        EXECUTE format(
            'SELECT COALESCE(MAX(%I), 0) FROM %I.%I',
            identity_column.column_name,
            identity_column.table_schema,
            identity_column.table_name
        ) INTO highest_id;

        IF highest_id = 0 THEN
            PERFORM setval(sequence_name::regclass, 1, FALSE);
        ELSE
            PERFORM setval(sequence_name::regclass, highest_id, TRUE);
        END IF;
    END LOOP;

    -- Imported data has no useful planner statistics yet.
    EXECUTE 'ANALYZE';
END $$;
