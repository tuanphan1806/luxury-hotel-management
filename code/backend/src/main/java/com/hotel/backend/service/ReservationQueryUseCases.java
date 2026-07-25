package com.hotel.backend.service;

import com.hotel.backend.dto.response.AvailabilityResponse;
import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.entity.User;

import java.time.LocalDateTime;
import java.util.List;

public interface ReservationQueryUseCases {

    ReservationResponse getReservation(Long reservationId, User currentUser, String guestToken);

    ReservationResponse lookupGuestReservation(String guestToken);

    List<ReservationResponse> getMyReservations(User currentUser);

    List<AvailabilityResponse> checkAvailability(LocalDateTime checkIn, LocalDateTime checkOut);

    List<ReservationResponse> getAllReservations();
}
