package com.hotel.backend.service;

import com.hotel.backend.dto.request.AssignRoomRequest;
import com.hotel.backend.dto.request.AddStayGuestRequest;
import com.hotel.backend.dto.request.CheckoutRefundRequest;
import com.hotel.backend.dto.request.ExtendReservationRequest;
import com.hotel.backend.dto.request.MoveStayGuestRequest;
import com.hotel.backend.dto.request.ReservationRefundRequest;
import com.hotel.backend.dto.response.ReservationResponse;

import java.util.List;

public interface StayLifecycleUseCases {

    ReservationResponse checkIn(Long reservationId, List<AssignRoomRequest> requests);

    ReservationResponse addStayGuest(
            Long reservationId,
            AddStayGuestRequest request);

    ReservationResponse moveStayGuest(
            Long reservationId,
            Long guestId,
            MoveStayGuestRequest request);

    ReservationResponse checkOut(Long reservationId);

    ReservationResponse extendStay(
            Long reservationId,
            ExtendReservationRequest request);

    ReservationResponse updateCheckoutAdditionalFee(
            Long reservationId,
            CheckoutRefundRequest request);

    ReservationResponse requestCheckoutRefund(
            Long reservationId,
            ReservationRefundRequest request);

    ReservationResponse markNoShow(Long reservationId);
}
