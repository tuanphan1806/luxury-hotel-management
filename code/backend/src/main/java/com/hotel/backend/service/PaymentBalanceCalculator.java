package com.hotel.backend.service;

import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.entity.Reservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
@RequiredArgsConstructor
public class PaymentBalanceCalculator {

    private final PaymentReservationPort reservationService;
    private final PaymentRefundService paymentRefundService;

    public PaymentBalance resolve(Reservation reservation) {
        long requiredTotal = reservation.getStatus() == ReservationStatus.CHECKED_IN
                ? reservationService.getProjectedCheckoutTotal(reservation.getId())
                : reservation.getTotalAmount().longValue();
        long paidAmount = paymentRefundService.getNetPaidAmount(reservation.getId());
        long remainingAmount = Math.max(0L, requiredTotal - paidAmount);
        long defaultAmount = reservation.getStatus() == ReservationStatus.PAYMENT_PENDING
                ? getRequiredDepositAmount(reservation) : remainingAmount;
        return new PaymentBalance(defaultAmount, remainingAmount);
    }

    private long getRequiredDepositAmount(Reservation reservation) {
        if (reservation.getRequiredInitialPayment() != null
                && reservation.getRequiredInitialPayment().signum() > 0) {
            return reservation.getRequiredInitialPayment()
                    .setScale(0, RoundingMode.CEILING).longValue();
        }
        return reservation.getTotalAmount()
                .multiply(BigDecimal.valueOf(0.5))
                .setScale(0, RoundingMode.CEILING)
                .longValue();
    }

    public record PaymentBalance(long defaultAmount, long remainingAmount) {}
}
