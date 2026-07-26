package com.hotel.backend.service;

import com.hotel.backend.dto.response.CheckoutReconciliationResponse;
import com.hotel.backend.dto.response.FinalPaymentResponse;
import com.hotel.backend.dto.response.ReservationInvoiceResponse;
import com.hotel.backend.entity.User;

public interface CheckoutProjectionPort {

    FinalPaymentResponse calculateFinalPayment(Long reservationId, User currentUser);

    CheckoutReconciliationResponse getCheckoutReconciliation(
            Long reservationId,
            User currentUser);

    long getProjectedCheckoutTotal(Long reservationId);

    ReservationInvoiceResponse getInvoice(Long reservationId, User currentUser);
}
