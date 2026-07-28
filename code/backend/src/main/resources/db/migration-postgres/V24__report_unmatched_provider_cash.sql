-- Keep unlinked provider money visible in read-only business reporting.
-- Expression index supports hotel-date range scans while retaining the
-- provider timestamp as the preferred source and received_at_utc as the
-- explicitly disclosed fallback for malformed legacy timestamps.

CREATE INDEX IF NOT EXISTS idx_provider_events_unlinked_cash_occurred_at
    ON payment_provider_events (
        (COALESCE(provider_occurred_at_utc, received_at_utc)) DESC,
        provider,
        transfer_type
    )
    WHERE amount > 0
      AND payment_transaction_id IS NULL;
