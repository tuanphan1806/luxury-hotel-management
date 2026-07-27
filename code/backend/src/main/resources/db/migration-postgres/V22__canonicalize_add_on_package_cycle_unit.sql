-- PER_NIGHT was implemented as "one charge per room-pricing package cycle",
-- not as a calendar night. Rename active catalog configuration to the
-- unambiguous canonical unit while retaining PER_NIGHT for immutable
-- reservation-service snapshots created before this migration.

ALTER TABLE service_catalog
    DROP CONSTRAINT chk_service_catalog_pricing_unit;

UPDATE service_catalog
SET pricing_unit = 'PER_PACKAGE_CYCLE',
    updated_at = CURRENT_TIMESTAMP
WHERE pricing_unit = 'PER_NIGHT';

ALTER TABLE service_catalog
    ADD CONSTRAINT chk_service_catalog_pricing_unit CHECK (
        pricing_unit IN (
            'PER_GUEST',
            'PER_PACKAGE_CYCLE',
            'PER_ITEM',
            'PER_ORDER',
            'PER_USE'));

ALTER TABLE reservation_services
    DROP CONSTRAINT chk_reservation_services_pricing_unit;

ALTER TABLE reservation_services
    ADD CONSTRAINT chk_reservation_services_pricing_unit CHECK (
        pricing_unit_snapshot IN (
            'PER_GUEST',
            'PER_PACKAGE_CYCLE',
            'PER_NIGHT',
            'PER_ITEM',
            'PER_ORDER',
            'PER_USE'));
