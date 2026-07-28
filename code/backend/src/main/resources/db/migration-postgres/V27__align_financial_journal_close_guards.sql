-- Align early V26 development databases with the committed financial-journal
-- schema. Every operation is safe when V26 was already applied in its final
-- form, so clean databases and upgraded databases converge on the same DDL.

ALTER TABLE financial_journal_entries
    ALTER COLUMN currency TYPE VARCHAR(3)
    USING BTRIM(currency);

ALTER TABLE business_day_closes
    ALTER COLUMN summary_hash TYPE VARCHAR(64)
    USING BTRIM(summary_hash);

ALTER TABLE financial_journal_entries
    DROP CONSTRAINT IF EXISTS chk_financial_journal_currency;

ALTER TABLE financial_journal_entries
    ADD CONSTRAINT chk_financial_journal_currency CHECK (currency = 'VND');

CREATE OR REPLACE FUNCTION prevent_posting_to_closed_business_day()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    entry_business_date DATE;
BEGIN
    IF TG_TABLE_NAME = 'financial_journal_entries' THEN
        entry_business_date := NEW.business_date;
    ELSE
        SELECT business_date INTO entry_business_date
        FROM financial_journal_entries
        WHERE id = NEW.journal_entry_id;
    END IF;

    IF EXISTS (
        SELECT 1 FROM business_day_closes
        WHERE business_date = entry_business_date
    ) THEN
        RAISE EXCEPTION 'business day % is closed', entry_business_date
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_financial_journal_entry_open_day
    ON financial_journal_entries;
CREATE TRIGGER trg_financial_journal_entry_open_day
BEFORE INSERT ON financial_journal_entries
FOR EACH ROW EXECUTE FUNCTION prevent_posting_to_closed_business_day();

DROP TRIGGER IF EXISTS trg_financial_journal_line_open_day
    ON financial_journal_lines;
CREATE TRIGGER trg_financial_journal_line_open_day
BEFORE INSERT ON financial_journal_lines
FOR EACH ROW EXECUTE FUNCTION prevent_posting_to_closed_business_day();
