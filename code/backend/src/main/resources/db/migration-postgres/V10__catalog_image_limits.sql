-- Enforce catalogue cardinality at the database boundary as well as in the
-- API/service layer. Ordered positions make the maximum deterministic.

ALTER TABLE facility_images
    ADD CONSTRAINT chk_facility_images_max_order
    CHECK (display_order BETWEEN 0 AND 1);

ALTER TABLE room_type_images
    ADD CONSTRAINT chk_room_type_images_max_order
    CHECK (display_order BETWEEN 0 AND 2);
