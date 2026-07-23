ALTER TABLE checkout_reconciliation_requests
    DROP CONSTRAINT chk_checkout_reconciliation_status;

ALTER TABLE checkout_reconciliation_requests
    ADD CONSTRAINT chk_checkout_reconciliation_status
        CHECK (status IN (
            'PENDING',
            'APPROVED',
            'REJECTED',
            'RESOLVED_AUTOMATICALLY',
            'CANCELLED'
        ));
