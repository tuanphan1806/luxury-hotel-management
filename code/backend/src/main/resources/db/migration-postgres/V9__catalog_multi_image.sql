-- Normalize ordered catalogue images while preserving image_url as the
-- backwards-compatible primary image alias.

CREATE TABLE facility_images (
    facility_id bigint NOT NULL,
    display_order int NOT NULL,
    image_url varchar(500) NOT NULL,
    PRIMARY KEY (facility_id, display_order),
    CONSTRAINT uk_facility_images_url UNIQUE (facility_id, image_url),
    CONSTRAINT chk_facility_images_order CHECK (display_order >= 0),
    CONSTRAINT fk_facility_images_facility
        FOREIGN KEY (facility_id) REFERENCES facilities (id) ON DELETE CASCADE
);

CREATE TABLE room_type_images (
    room_type_id bigint NOT NULL,
    display_order int NOT NULL,
    image_url varchar(500) NOT NULL,
    PRIMARY KEY (room_type_id, display_order),
    CONSTRAINT uk_room_type_images_url UNIQUE (room_type_id, image_url),
    CONSTRAINT chk_room_type_images_order CHECK (display_order >= 0),
    CONSTRAINT fk_room_type_images_room_type
        FOREIGN KEY (room_type_id) REFERENCES room_types (id) ON DELETE CASCADE
);

CREATE INDEX idx_facility_images_facility_order
    ON facility_images (facility_id, display_order);
CREATE INDEX idx_room_type_images_room_type_order
    ON room_type_images (room_type_id, display_order);

-- One catalogue owner can now claim multiple independently validated uploads.
ALTER TABLE media_assets DROP CONSTRAINT IF EXISTS uk_media_assets_owner;
CREATE INDEX idx_media_assets_owner
    ON media_assets (owner_type, owner_id)
    WHERE owner_type IS NOT NULL AND owner_id IS NOT NULL;

-- Backfill every legacy primary image without rewriting the compatibility
-- columns on facilities/room_types.
INSERT INTO facility_images (facility_id, display_order, image_url)
SELECT id, 0, btrim(image_url)
FROM facilities
WHERE image_url IS NOT NULL AND btrim(image_url) <> ''
ON CONFLICT DO NOTHING;

INSERT INTO room_type_images (room_type_id, display_order, image_url)
SELECT id, 0, btrim(image_url)
FROM room_types
WHERE image_url IS NOT NULL AND btrim(image_url) <> ''
ON CONFLICT DO NOTHING;

-- Existing environments already contain the bundled static catalogue. Derive
-- the environment-specific static base (localhost in dev, Cloudinary in
-- production) instead of hardcoding a host in the migration.
WITH facility_static_base AS (
    SELECT regexp_replace(image_url, '/[^/]+$', '') AS base_url
    FROM facilities
    WHERE image_url IS NOT NULL
      AND image_url LIKE '%/facilities/%'
    ORDER BY
        CASE
            WHEN image_url LIKE '%/static/facilities/%' THEN 0
            WHEN image_url LIKE 'http://localhost%/facilities/%' THEN 1
            ELSE 2
        END,
        id
    LIMIT 1
),
facility_detail_map(facility_name, facility_name_en, filename) AS (
    VALUES
        ('Hồ bơi', 'Swimming Pool', 'facility-pool-detail.webp'),
        ('Thư viện', 'Library', 'facility-library-detail.webp'),
        ('Khu mua sắm', 'Marketplace', 'facility-marketplace-detail.webp'),
        ('Bếp riêng', 'Kitchen', 'facility-kitchen-detail.webp'),
        ('Quán cà phê', 'Cafe', 'facility-cafe-detail.webp'),
        ('Phòng tắm riêng', 'Bathroom', 'facility-bathroom-detail.webp'),
        ('Phòng khách', 'Living room', 'facility-living-room-detail.webp'),
        ('Spa & chăm sóc sức khỏe', 'Spa & Wellness', 'facility-spa-detail.webp'),
        ('Trung tâm thể hình', 'Fitness Center', 'facility-fitness-detail.webp')
)
INSERT INTO facility_images (facility_id, display_order, image_url)
SELECT f.id, 1, b.base_url || '/' || m.filename
FROM facilities f
JOIN facility_detail_map m
  ON lower(f.facility_name) = lower(m.facility_name)
  OR lower(COALESCE(f.facility_name_en, '')) = lower(m.facility_name_en)
CROSS JOIN facility_static_base b
ON CONFLICT DO NOTHING;

WITH room_type_static_base AS (
    SELECT regexp_replace(image_url, '/[^/]+$', '') AS base_url
    FROM room_types
    WHERE image_url IS NOT NULL
      AND image_url LIKE '%/room_types/%'
    ORDER BY
        CASE
            WHEN image_url LIKE '%/static/room_types/%' THEN 0
            WHEN image_url LIKE 'http://localhost%/room_types/%' THEN 1
            ELSE 2
        END,
        id
    LIMIT 1
),
room_image_map(type_name, type_name_en, display_order, filename) AS (
    VALUES
        ('Phòng tiêu chuẩn', 'Standard', 1, '7.jpg'),
        ('Phòng tiêu chuẩn', 'Standard', 2, 'room-standard-detail.webp'),
        ('Phòng Deluxe', 'Deluxe', 1, '9.jpg'),
        ('Phòng Deluxe', 'Deluxe', 2, '8.jpg'),
        ('Phòng Executive', 'Executive Room', 1, 'room-executive-work.webp'),
        ('Phòng Executive', 'Executive Room', 2, 'room-executive-bathroom.webp'),
        ('Phòng Suite', 'Suite', 1, '12.jpg'),
        ('Phòng Suite', 'Suite', 2, '5.jpg'),
        ('Phòng gia đình', 'Family Room', 1, '11.jpg'),
        ('Phòng gia đình', 'Family Room', 2, '10.jpg'),
        ('Phòng Tổng thống', 'Presidential Suite', 1, '13.jpg'),
        ('Phòng Tổng thống', 'Presidential Suite', 2, '14.jpg')
)
INSERT INTO room_type_images (room_type_id, display_order, image_url)
SELECT rt.id, m.display_order, b.base_url || '/' || m.filename
FROM room_types rt
JOIN room_image_map m
  ON lower(rt.type_name) = lower(m.type_name)
  OR lower(COALESCE(rt.type_name_en, '')) = lower(m.type_name_en)
CROSS JOIN room_type_static_base b
ON CONFLICT DO NOTHING;
