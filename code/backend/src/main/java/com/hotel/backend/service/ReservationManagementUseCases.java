package com.hotel.backend.service;

import com.hotel.backend.dto.request.UpdateReservationRequest;
import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.entity.User;

public interface ReservationManagementUseCases {

    ReservationResponse updateReservation(
            Long reservationId,
            UpdateReservationRequest request,
            User currentUser);
}
