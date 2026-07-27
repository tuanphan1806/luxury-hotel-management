-- Guard the assumptions used by SePay/VietQR and the pricing engine:
-- VND configuration is whole-number money, and every selected physical room
-- must have at least one allocated guest. Historical evidence remains
-- readable; NOT VALID constraints enforce all new inserts/updates without
-- rewriting old financial records.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM room_rate_profiles
        WHERE active = true
          AND (
              first_block_price <> trunc(first_block_price)
              OR extra_unit_price <> trunc(extra_unit_price)
              OR overnight_price <> trunc(overnight_price)
              OR daily_price <> trunc(daily_price)
              OR extra_guest_price <> trunc(extra_guest_price)
          )
    ) THEN
        RAISE EXCEPTION
            'Pricing guard failed: an active room rate contains fractional VND';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM service_catalog
        WHERE is_active = true
          AND price <> trunc(price)
    ) THEN
        RAISE EXCEPTION
            'Pricing guard failed: an active add-on service contains fractional VND';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM room_types
        WHERE price IS NOT NULL
          AND price <> trunc(price)
    ) THEN
        RAISE EXCEPTION
            'Pricing guard failed: a legacy room price contains fractional VND';
    END IF;
END;
$$;

ALTER TABLE room_rate_profiles
    ADD CONSTRAINT chk_room_rate_profile_whole_vnd
        CHECK (
            first_block_price = trunc(first_block_price)
            AND extra_unit_price = trunc(extra_unit_price)
            AND overnight_price = trunc(overnight_price)
            AND daily_price = trunc(daily_price)
            AND extra_guest_price = trunc(extra_guest_price)
        ) NOT VALID;

ALTER TABLE service_catalog
    ADD CONSTRAINT chk_service_catalog_whole_vnd
        CHECK (price = trunc(price)) NOT VALID;

ALTER TABLE room_types
    ADD CONSTRAINT chk_room_types_price_whole_vnd
        CHECK (price IS NULL OR price = trunc(price)) NOT VALID;

ALTER TABLE reservation_room_types
    ADD CONSTRAINT chk_rrt_minimum_one_guest_per_room
        CHECK (
            line_guest_count IS NULL
            OR line_guest_count >= quantity
        ) NOT VALID;

ALTER TABLE pricing_quote_lines
    ADD CONSTRAINT chk_pricing_quote_minimum_one_guest_per_room
        CHECK (line_guest_count >= room_quantity) NOT VALID;

ALTER TABLE reservation_rate_snapshots
    ADD CONSTRAINT chk_rate_snapshot_minimum_one_guest_per_room
        CHECK (line_guest_count >= room_quantity) NOT VALID;
