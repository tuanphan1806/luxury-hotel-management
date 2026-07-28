-- PostgreSQL does not create indexes for foreign keys automatically. Business
-- day close checks these source links repeatedly to detect completed sources
-- that have not been journalized, so keep those lookups index-backed.

CREATE INDEX IF NOT EXISTS idx_financial_journal_payment_transaction
    ON financial_journal_entries (payment_transaction_id)
    WHERE payment_transaction_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_financial_journal_refund
    ON financial_journal_entries (refund_id)
    WHERE refund_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_financial_journal_invoice
    ON financial_journal_entries (invoice_id)
    WHERE invoice_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_financial_journal_provider_event
    ON financial_journal_entries (provider_event_id)
    WHERE provider_event_id IS NOT NULL;
