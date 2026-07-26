-- Keep existing seeded catalog rows while switching only the bundled default
-- images from large PNG files to their optimized WebP equivalents.
UPDATE service_catalog
SET image_url = regexp_replace(image_url, '\.png$', '.webp')
WHERE code IN (
    'IN_ROOM_BREAKFAST',
    'EXTRA_ROLLAWAY_BED',
    'MINI_PROJECTOR',
    'PRIVATE_BBQ_SET',
    'ROOM_DECORATION'
)
  AND image_url LIKE '%/add_on_services/%.png';
