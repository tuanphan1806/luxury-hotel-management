package com.hotel.backend.service;

import com.hotel.backend.dto.request.CancelReservationRequest;
import com.hotel.backend.dto.request.RejectReservationRequest;
import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.entity.User;

public interface ReservationCancellationUseCases {

    ReservationResponse cancelReservation(
            Long reservationId,
            CancelReservationRequest request,
            User currentUser,
            String guestToken);

    ReservationResponse approveCancellation(Long reservationId, CancelReservationRequest request);

    ReservationResponse rejectCancellation(Long reservationId);

    ReservationResponse cancelByStaff(Long reservationId, CancelReservationRequest request);

    ReservationResponse confirmReservation(Long reservationId);

    ReservationResponse rejectConfirmation(Long reservationId, RejectReservationRequest request);
}
