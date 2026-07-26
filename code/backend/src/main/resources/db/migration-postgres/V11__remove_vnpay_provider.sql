-- VNPay has been decommissioned. Refuse to rewrite historical financial data:
-- an operator must reconcile/export any unsupported rows before this migration.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM payment_transactions
        WHERE provider NOT IN ('SEPAY', 'CASH')
           OR (refund_provider IS NOT NULL AND refund_provider NOT IN ('SEPAY', 'CASH'))
    ) THEN
        RAISE EXCEPTION
            'V11 cannot remove VNPay: payment_transactions still contains an unsupported provider';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM payment_refunds
        WHERE provider NOT IN ('SEPAY', 'CASH')
           OR channel NOT IN ('MANUAL_BANK_TRANSFER', 'CASH_AT_COUNTER')
           OR completion_method = 'PROVIDER_API'
    ) THEN
        RAISE EXCEPTION
            'V11 cannot remove VNPay: payment_refunds still contains an unsupported provider, channel, or completion method';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM payment_provider_events
        WHERE provider <> 'SEPAY'
    ) THEN
        RAISE EXCEPTION
            'V11 cannot remove VNPay: payment_provider_events still contains a non-SePay event';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM reconciliation_state
        WHERE provider <> 'SEPAY'
    ) THEN
        RAISE EXCEPTION
            'V11 cannot remove VNPay: reconciliation_state still contains a non-SePay cursor';
    END IF;
END
$$;

ALTER TABLE payment_transactions
    DROP COLUMN requested_bank_code,
    DROP COLUMN provider_create_date,
    DROP COLUMN card_token;

ALTER TABLE payment_refunds
    DROP COLUMN request_history,
    DROP COLUMN transaction_type,
    DROP COLUMN original_transaction_date;

ALTER TABLE payment_transactions
    ADD CONSTRAINT chk_payment_transactions_provider
        CHECK (provider IN ('SEPAY', 'CASH')),
    ADD CONSTRAINT chk_payment_transactions_refund_provider
        CHECK (refund_provider IS NULL OR refund_provider IN ('SEPAY', 'CASH'));

ALTER TABLE payment_refunds
    ADD CONSTRAINT chk_payment_refunds_provider
        CHECK (provider IN ('SEPAY', 'CASH')),
    ADD CONSTRAINT chk_payment_refunds_channel
        CHECK (channel IN ('MANUAL_BANK_TRANSFER', 'CASH_AT_COUNTER')),
    ADD CONSTRAINT chk_payment_refunds_completion_method
        CHECK (
            completion_method IS NULL
            OR completion_method IN ('SEPAY_WEBHOOK', 'MANUAL_FALLBACK', 'CASH_HANDOVER', 'LEGACY')
        );

ALTER TABLE payment_provider_events
    ADD CONSTRAINT chk_payment_provider_events_provider
        CHECK (provider = 'SEPAY');

ALTER TABLE reconciliation_state
    ADD CONSTRAINT chk_reconciliation_state_provider
        CHECK (provider = 'SEPAY');
