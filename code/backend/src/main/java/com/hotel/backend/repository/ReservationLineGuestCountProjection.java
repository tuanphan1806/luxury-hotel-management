package com.hotel.backend.repository;

/** Aggregated active occupancy for one immutable reservation room-type line. */
public interface ReservationLineGuestCountProjection {
    Long getReservationRoomTypeId();
    Long getGuestCount();
}
