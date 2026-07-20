\set ON_ERROR_STOP on

-- Run after post-cutover-finalize.sql and before reopening application writes.
-- This explicitly checks every public-schema foreign key because a bulk loader
-- may temporarily disable PostgreSQL constraint triggers during COPY.
DO $$
DECLARE
    fk RECORD;
    orphan_count BIGINT;
BEGIN
    FOR fk IN
        SELECT constraint_def.conname,
               constraint_def.conrelid::regclass AS child_table,
               constraint_def.confrelid::regclass AS parent_table,
               string_agg(
                   format('child.%I = parent.%I', child_column.attname, parent_column.attname),
                   ' AND ' ORDER BY key_column.ordinality
               ) AS join_predicate,
               string_agg(
                   format('child.%I IS NOT NULL', child_column.attname),
                   ' AND ' ORDER BY key_column.ordinality
               ) AS child_key_present,
               (array_agg(
                   format('parent.%I IS NULL', parent_column.attname)
                   ORDER BY key_column.ordinality
               ))[1] AS parent_missing
        FROM pg_constraint constraint_def
        JOIN LATERAL unnest(constraint_def.conkey, constraint_def.confkey)
             WITH ORDINALITY AS key_column(child_attnum, parent_attnum, ordinality)
             ON TRUE
        JOIN pg_attribute child_column
          ON child_column.attrelid = constraint_def.conrelid
         AND child_column.attnum = key_column.child_attnum
        JOIN pg_attribute parent_column
          ON parent_column.attrelid = constraint_def.confrelid
         AND parent_column.attnum = key_column.parent_attnum
        WHERE constraint_def.contype = 'f'
          AND constraint_def.connamespace = 'public'::regnamespace
        GROUP BY constraint_def.conname,
                 constraint_def.conrelid,
                 constraint_def.confrelid
    LOOP
        EXECUTE format(
            'SELECT count(*) FROM %s child LEFT JOIN %s parent ON %s WHERE %s AND %s',
            fk.child_table,
            fk.parent_table,
            fk.join_predicate,
            fk.child_key_present,
            fk.parent_missing
        ) INTO orphan_count;

        IF orphan_count > 0 THEN
            RAISE EXCEPTION 'Foreign key % has % orphan row(s) in %',
                fk.conname, orphan_count, fk.child_table;
        END IF;
    END LOOP;
END $$;

-- Refuse cutover when an imported explicit id is ahead of its identity
-- sequence. That condition would make a later application insert collide with
-- a legacy primary key even though every FK currently validates.
DO $$
DECLARE
    identity_column RECORD;
    sequence_name TEXT;
    highest_id BIGINT;
    sequence_last_value BIGINT;
    sequence_is_called BOOLEAN;
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

        EXECUTE format(
            'SELECT last_value, is_called FROM %s',
            sequence_name::regclass
        ) INTO sequence_last_value, sequence_is_called;

        IF highest_id = 0 THEN
            IF sequence_last_value < 1 THEN
                RAISE EXCEPTION 'Identity sequence % is invalid for empty %.%',
                    sequence_name,
                    identity_column.table_schema,
                    identity_column.table_name;
            END IF;
        ELSIF sequence_last_value < highest_id OR NOT sequence_is_called THEN
            RAISE EXCEPTION 'Identity sequence % is behind %.%: sequence %, max id %',
                sequence_name,
                identity_column.table_schema,
                identity_column.table_name,
                sequence_last_value,
                highest_id;
        END IF;
    END LOOP;
END $$;

-- V2 creates these checks as NOT VALID so legacy rows can first be inspected
-- and remediated. Validation performs a full table scan and fails atomically.
ALTER TABLE reservations
    VALIDATE CONSTRAINT chk_reservations_date_range;
ALTER TABLE reservation_room_types
    VALIDATE CONSTRAINT chk_reservation_room_types_quantity_positive;
ALTER TABLE payment_transactions
    VALIDATE CONSTRAINT chk_payment_transactions_amounts_nonnegative;
ALTER TABLE payment_refunds
    VALIDATE CONSTRAINT chk_payment_refunds_amounts_nonnegative;

-- Final operator-visible summary.
SELECT version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;

SELECT status, COUNT(*) AS reservation_count
FROM reservations
GROUP BY status
ORDER BY status;

SELECT status,
       COUNT(*) AS transaction_count,
       COALESCE(SUM(amount), 0) AS total_amount
FROM payment_transactions
GROUP BY status
ORDER BY status;

SELECT status,
       COUNT(*) AS refund_count,
       COALESCE(SUM(requested_amount), 0) AS total_requested_amount
FROM payment_refunds
GROUP BY status
ORDER BY status;
