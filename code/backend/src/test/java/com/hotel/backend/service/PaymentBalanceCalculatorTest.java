package com.hotel.backend.service;

import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.entity.Reservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentBalanceCalculatorTest {

    @Mock
    private PaymentReservationPort reservationService;

    @Mock
    private PaymentRefundService paymentRefundService;

    @InjectMocks
    private PaymentBalanceCalculator calculator;

    @Test
    void paymentPendingUsesStoredRequiredInitialPaymentAndCurrentNetPaidBalance() {
        Reservation reservation = reservation(
                ReservationStatus.PAYMENT_PENDING,
                BigDecimal.valueOf(100_000L));
        reservation.setRequiredInitialPayment(new BigDecimal("50000.2"));
        when(paymentRefundService.getNetPaidAmount(71L)).thenReturn(10_000L);

        PaymentBalanceCalculator.PaymentBalance balance = calculator.resolve(reservation);

        assertEquals(50_001L, balance.defaultAmount());
        assertEquals(90_000L, balance.remainingAmount());
    }

    @Test
    void paymentPendingFallsBackToCeilingHalfOfReservationTotal() {
        Reservation reservation = reservation(
                ReservationStatus.PAYMENT_PENDING,
                BigDecimal.valueOf(100_001L));
        when(paymentRefundService.getNetPaidAmount(71L)).thenReturn(0L);

        PaymentBalanceCalculator.PaymentBalance balance = calculator.resolve(reservation);

        assertEquals(50_001L, balance.defaultAmount());
        assertEquals(100_001L, balance.remainingAmount());
    }

    @Test
    void checkedInUsesProjectedCheckoutTotalAndRemainingAmountAsDefault() {
        Reservation reservation = reservation(
                ReservationStatus.CHECKED_IN,
                BigDecimal.valueOf(100_000L));
        when(reservationService.getProjectedCheckoutTotal(71L)).thenReturn(130_000L);
        when(paymentRefundService.getNetPaidAmount(71L)).thenReturn(50_000L);

        PaymentBalanceCalculator.PaymentBalance balance = calculator.resolve(reservation);

        assertEquals(80_000L, balance.defaultAmount());
        assertEquals(80_000L, balance.remainingAmount());
    }

    private Reservation reservation(ReservationStatus status, BigDecimal totalAmount) {
        Reservation reservation = Reservation.builder()
                .status(status)
                .totalAmount(totalAmount)
                .build();
        reservation.setId(71L);
        return reservation;
    }
}
