\set ON_ERROR_STOP on

-- Local/staging-only repair for the PostgreSQL baseline that was applied before
-- the consolidated V1 file received a checksum-only correction. Run only after
-- a backup and a successful Hibernate schema validation. Never use this file to
-- hide an unexpected migration change in production.
DO $$
DECLARE
    applied_checksum INTEGER;
    expected_previous_checksum CONSTANT INTEGER := -553717861;
    current_source_checksum CONSTANT INTEGER := 611252054;
BEGIN
    SELECT checksum
      INTO applied_checksum
      FROM flyway_schema_history
     WHERE version = '1'
       AND success = TRUE;

    IF applied_checksum IS NULL THEN
        RAISE EXCEPTION 'Flyway V1 history row was not found';
    END IF;

    IF applied_checksum <> expected_previous_checksum THEN
        RAISE EXCEPTION
            'Refusing repair: expected previous V1 checksum %, found %',
            expected_previous_checksum,
            applied_checksum;
    END IF;

    UPDATE flyway_schema_history
       SET checksum = current_source_checksum
     WHERE version = '1'
       AND success = TRUE;
END $$;

SELECT version, checksum, success
FROM flyway_schema_history
WHERE version = '1';
