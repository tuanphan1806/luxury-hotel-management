-- Additive invoice evidence for Pricing V2. Existing immutable invoice JSON
-- remains untouched; historical rows are explicitly backfilled with zero.
ALTER TABLE reservation_invoices
    ADD COLUMN extra_guest_charge decimal(19,2),
    ADD COLUMN pricing_version varchar(32);

UPDATE reservation_invoices
SET extra_guest_charge = COALESCE(extra_guest_charge, 0),
    pricing_version = COALESCE(pricing_version, 'LEGACY_V1')
WHERE extra_guest_charge IS NULL
   OR pricing_version IS NULL;

ALTER TABLE reservation_invoices
    ALTER COLUMN extra_guest_charge SET DEFAULT 0,
    ALTER COLUMN extra_guest_charge SET NOT NULL,
    ALTER COLUMN pricing_version SET DEFAULT 'LEGACY_V1',
    ALTER COLUMN pricing_version SET NOT NULL;

ALTER TABLE reservation_invoices
    ADD CONSTRAINT chk_invoice_extra_guest_charge
        CHECK (extra_guest_charge >= 0),
    ADD CONSTRAINT chk_invoice_pricing_version
        CHECK (pricing_version IN ('LEGACY_V1', 'MOTEL_PACKAGE_V2'));
