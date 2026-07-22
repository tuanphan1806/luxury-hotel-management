package com.hotel.backend.service;

import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentPurpose;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.HoldStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.request.PaymentRequest;
import com.hotel.backend.dto.response.PaymentResponse;
import com.hotel.backend.dto.response.PaymentRefundResponse;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.ReservationRoomType;
import com.hotel.backend.entity.RoomHold;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.repository.ReservationRoomTypeRepository;
import com.hotel.backend.repository.RoomHoldRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock SePayService sePayService;
    @Mock PaymentTransactionRepository transactionRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock ReservationRoomTypeRepository reservationRoomTypeRepository;
    @Mock RoomHoldRepository roomHoldRepository;
    @Mock ReservationService reservationService;
    @Mock ReservationAuditService reservationAuditService;
    @Mock PaymentRefundService paymentRefundService;
    @Mock PaymentSessionExpiryService paymentSessionExpiryService;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks PaymentService paymentService;

    @Test
    void depositPaymentUsesReservationLockAndRejectsExpiredHold() {
        Reservation reservation = Reservation.builder()
                .reservationCode("RSV-EXPIRED")
                .status(ReservationStatus.PAYMENT_PENDING)
                .totalAmount(BigDecimal.valueOf(100_000L))
                .roomTypes(new HashSet<>())
                .build();
        reservation.setId(7L);
        ReservationRoomType item = ReservationRoomType.builder()
                .reservation(reservation)
                .quantity(1)
                .build();
        RoomHold hold = RoomHold.builder()
                .reservationRoomType(item)
                .status(HoldStatus.ACTIVE)
                .expiresAt(LocalDateTime.now().minusSeconds(1))
                .build();
        item.setRoomHold(hold);
        reservation.getRoomTypes().add(item);
        User staff = User.builder().type(UserType.STAFF).build();
        PaymentRequest request = new PaymentRequest();
        request.setBookingId(7L);
        request.setProvider(PaymentProvider.SEPAY);
        request.setPurpose(PaymentPurpose.DEPOSIT);
        when(reservationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(reservation));
        when(sePayService.newExpiryTime()).thenReturn(LocalDateTime.now().plusMinutes(5));
        when(reservationRoomTypeRepository.countByReservationId(7L)).thenReturn(1L);
        when(roomHoldRepository.findByReservationId(7L)).thenReturn(List.of(hold));

        assertThrows(AppException.class, () -> paymentService.createPayment(
                request, new org.springframework.mock.web.MockHttpServletRequest(), staff, null));

        verify(reservationRepository).findByIdForUpdate(7L);
        verify(reservationRepository, never()).findById(7L);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void cashFinalPaymentUsesReservationLockAndCollectsRemainingBalance() {
        Reservation reservation = Reservation.builder()
                .reservationCode("RSV-CASH")
                .status(ReservationStatus.CHECKED_IN)
                .totalAmount(BigDecimal.valueOf(100_000L))
                .build();
        reservation.setId(8L);
        User staff = User.builder().type(UserType.STAFF).build();
        PaymentRequest request = new PaymentRequest();
        request.setBookingId(8L);
        request.setProvider(PaymentProvider.CASH);
        request.setPurpose(PaymentPurpose.FINAL_PAYMENT);
        when(reservationRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(reservation));
        when(reservationService.getProjectedCheckoutTotal(8L)).thenReturn(100_000L);
        when(paymentRefundService.getNetPaidAmount(8L)).thenReturn(50_000L);
        when(transactionRepository.save(any(PaymentTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentResponse response = paymentService.createCashPayment(
                request, new org.springframework.mock.web.MockHttpServletRequest(), staff);

        assertEquals(50_000L, response.getAmount());
        assertEquals(50_000L, response.getExpectedAmount());
        assertEquals(50_000L, response.getReceivedAmount());
        assertEquals(50_000L, response.getAcceptedAmount());
        assertEquals(0L, response.getRefundRequiredAmount());
        assertEquals(PaymentStatus.SUCCESS, response.getStatus());
        verify(reservationRepository).findByIdForUpdate(8L);
        verify(reservationRepository, never()).findById(8L);
        verify(reservationService).convertHoldsAfterPayment(8L);
    }

    @Test
    void reconcileRefundDelegatesToProviderBackedWorkflow() {
        PaymentRefundResponse expected = PaymentRefundResponse.builder()
                .refundId("refund-1")
                .status(RefundStatus.SUCCEEDED)
                .amount(50_000L)
                .build();
        when(paymentRefundService.reconcile("refund-1")).thenReturn(expected);

        PaymentRefundResponse response = paymentService.reconcileRefund("refund-1");

        assertSame(expected, response);
        verify(paymentRefundService).reconcile("refund-1");
    }

    @Test
    void retryRefundDelegatesWithoutManualCompletion() {
        PaymentRefundResponse expected = PaymentRefundResponse.builder()
                .refundId("refund-2")
                .status(RefundStatus.PROCESSING)
                .build();
        when(paymentRefundService.retry("refund-2")).thenReturn(expected);

        assertSame(expected, paymentService.retryRefund("refund-2"));
        verify(paymentRefundService).retry("refund-2");
    }

    @Test
    void pendingRefundQueueReturnsOperationalFields() {
        PaymentRefundResponse pending = PaymentRefundResponse.builder()
                .refundId("refund-3")
                .reservationCode("RSV-2")
                .amount(25_000L)
                .status(RefundStatus.PROCESSING)
                .build();
        when(paymentRefundService.getOperationalQueue()).thenReturn(List.of(pending));

        List<PaymentRefundResponse> responses = paymentService.getPendingRefunds();

        assertEquals(1, responses.size());
        assertEquals("RSV-2", responses.get(0).getReservationCode());
        assertEquals(25_000L, responses.get(0).getAmount());
    }
}
