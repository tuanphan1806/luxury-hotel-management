package com.hotel.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalkInReservationResponse {
    private boolean reservationCreated;
    private ReservationResponse reservation;
    private String paymentCreationStatus;
    private PaymentResponse paymentInstructions;
    private String paymentError;
}
