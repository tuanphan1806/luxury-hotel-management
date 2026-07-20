-- PostgreSQL-only cleanup kept additive so environments that already applied
-- the consolidated V1/V2 history retain stable Flyway checksums.

ALTER TABLE rooms
    ALTER COLUMN sellable SET DEFAULT TRUE;
