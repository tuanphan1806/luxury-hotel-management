package com.hotel.backend.service;

import com.hotel.backend.dto.request.AssignRoomRequest;
import com.hotel.backend.dto.request.CheckoutRefundRequest;
import com.hotel.backend.dto.request.ReservationRefundRequest;
import com.hotel.backend.dto.response.ReservationResponse;

import java.util.List;

public interface StayLifecycleUseCases {

    ReservationResponse checkIn(Long reservationId, List<AssignRoomRequest> requests);

    ReservationResponse checkOut(Long reservationId);

    ReservationResponse updateCheckoutAdditionalFee(
            Long reservationId,
            CheckoutRefundRequest request);

    ReservationResponse requestCheckoutRefund(
            Long reservationId,
            ReservationRefundRequest request);

    ReservationResponse markNoShow(Long reservationId);
}
