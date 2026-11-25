CREATE TABLE reservation_room_departures (
    reservation_room_id BIGINT PRIMARY KEY REFERENCES reservation_rooms(id),
    departed_at TIMESTAMP NOT NULL,
    departure_room_name VARCHAR(255) NOT NULL,
    folio_number VARCHAR(100) NOT NULL,
    billing_mode VARCHAR(12) NOT NULL CHECK (billing_mode IN ('GROUP', 'SEPARATE')),
    departed_guests INTEGER NOT NULL CHECK (departed_guests >= 0),
    billed_extra_guests INTEGER NOT NULL CHECK (billed_extra_guests BETWEEN 0 AND departed_guests),
    room_charge NUMERIC(14,2) NOT NULL CHECK (room_charge >= 0),
    included_late_fee NUMERIC(14,2) NOT NULL CHECK (included_late_fee BETWEEN 0 AND room_charge),
    extra_charge NUMERIC(14,2) NOT NULL CHECK (extra_charge >= 0),
    service_charge NUMERIC(14,2) NOT NULL CHECK (service_charge >= 0),
    additional_fee BIGINT NOT NULL CHECK (additional_fee >= 0),
    allocated_payment NUMERIC(14,2) NOT NULL,
    cash_collected BIGINT NOT NULL CHECK (cash_collected >= 0 AND cash_collected <= allocated_payment),
    CHECK ((billing_mode = 'GROUP' AND allocated_payment = 0)
        OR (billing_mode = 'SEPARATE' AND allocated_payment = room_charge + extra_charge + service_charge + additional_fee))
);

CREATE TABLE room_departure_services (
    reservation_room_id BIGINT NOT NULL REFERENCES reservation_room_departures(reservation_room_id),
    service_order_id BIGINT NOT NULL REFERENCES reservation_services(id),
    name VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    amount NUMERIC(14,2) NOT NULL CHECK (amount >= 0),
    PRIMARY KEY (reservation_room_id, service_order_id)
);
