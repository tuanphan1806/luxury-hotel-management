package com.hotel.backend.service;

import java.time.Instant;
import java.time.LocalDateTime;

public interface RoomHoldLifecyclePort {

    void activatePaymentHolds(Long reservationId, LocalDateTime expiresAt);

    void convertHoldsAfterPayment(Long reservationId);

    void cancelForPaymentFailure(Long reservationId, String reasonCode, String message);

    boolean recoverOnTimeDepositPayment(
            Long reservationId,
            String paymentId,
            Instant providerOccurredAt);
}
