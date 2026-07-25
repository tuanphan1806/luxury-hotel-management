package com.hotel.backend.service;

/**
 * Backward-compatible facade for controllers and integration tests.
 */
public interface ReservationService
        extends ReservationCreationUseCases,
        ReservationQueryUseCases,
        ReservationCancellationUseCases,
        StayLifecycleUseCases,
        ReservationManagementUseCases,
        PaymentReservationPort {
}
