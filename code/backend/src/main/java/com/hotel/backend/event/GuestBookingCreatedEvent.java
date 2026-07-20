package com.hotel.backend.event;

public record GuestBookingCreatedEvent(Long reservationId, String email, String guestToken) {
}
