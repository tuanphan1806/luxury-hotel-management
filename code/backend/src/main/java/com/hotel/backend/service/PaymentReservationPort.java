package com.hotel.backend.service;

/**
 * Reservation capabilities required by payment creation and settlement only.
 */
public interface PaymentReservationPort extends RoomHoldLifecyclePort, CheckoutProjectionPort {
}
