package com.hotel.backend.service;

import com.hotel.backend.dto.request.CreateReservationRequest;
import com.hotel.backend.dto.request.CreateWalkInCheckedInRequest;
import com.hotel.backend.dto.request.CreateWalkInReservationRequest;
import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.dto.response.WalkInReservationResponse;
import com.hotel.backend.entity.User;

public interface ReservationCreationUseCases {

    ReservationResponse createReservation(User currentUser, CreateReservationRequest request);

    ReservationResponse createReservation(
            User currentUser,
            CreateReservationRequest request,
            String deterministicGuestToken);

    ReservationResponse createWalkInReservation(CreateWalkInReservationRequest request);

    WalkInReservationResponse createWalkInCheckedIn(
            CreateWalkInCheckedInRequest request,
            User currentUser,
            String ipAddress);
}
