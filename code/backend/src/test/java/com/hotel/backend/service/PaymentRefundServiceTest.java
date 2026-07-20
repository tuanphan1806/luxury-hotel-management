package com.hotel.backend.service;

import com.hotel.backend.config.VNPayConfig;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.constant.RefundSourceType;
import com.hotel.backend.constant.PaymentProviderEventStatus;
import com.hotel.backend.entity.PaymentRefund;
import com.hotel.backend.entity.PaymentProviderEvent;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.repository.PaymentRefundRepository;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.RefundRecipientRepository;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.repository.RoomHoldRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentRefundServiceTest {

    @Mock PaymentRefundRepository refundRepository;
    @Mock RefundRecipientRepository recipientRepository;
    @Mock PaymentTransactionRepository transactionRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock RoomHoldRepository roomHoldRepository;
    @Mock VNPayRefundGateway vnPayGateway;
    @Mock VNPayConfig vnPayConfig;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PlatformTransactionManager transactionManager;
    @Mock RefundDataCipher refundDataCipher;
    @Mock ReservationAuditService reservationAuditService;
    @Mock MediaAssetService mediaAssetService;
    @InjectMocks PaymentRefundService refundService;

    @Test
    void unmatchedEventRefundDoesNotCreateSyntheticReservationOrPayment() {
        PaymentProviderEvent event = PaymentProviderEvent.builder()
                .id("unmatched-event")
                .provider(PaymentProvider.SEPAY)
                .status(PaymentProviderEventStatus.REVIEW_REQUIRED)
                .amount(220_000L)
                .build();
        when(refundRepository.findBySourceKey("unmatched:unmatched-event"))
                .thenReturn(Optional.empty());
        when(vnPayGateway.newRequestId()).thenReturn("REQUEST-UNMATCHED");
        when(refundRepository.save(any(PaymentRefund.class))).thenAnswer(invocation -> {
            PaymentRefund refund = invocation.getArgument(0);
            refund.setId("refund-unmatched");
            return refund;
        });

        var response = refundService.requestUnmatchedEventRefund(
                event,
                RefundChannel.MANUAL_BANK_TRANSFER,
                "Hoàn khoản không xác định booking",
                "review-staff");

        assertEquals("refund-unmatched", response.getRefundId());
        assertNull(response.getBookingId());
        assertNull(response.getTransactionId());
        assertEquals(RefundSourceType.UNMATCHED_TRANSFER, response.getSourceType());
        assertEquals(220_000L, response.getRequestedAmount());
        org.mockito.ArgumentCaptor<PaymentRefund> captor =
                org.mockito.ArgumentCaptor.forClass(PaymentRefund.class);
        verify(refundRepository).save(captor.capture());
        assertNull(captor.getValue().getReservation());
        assertNull(captor.getValue().getPaymentTransaction());
        assertSame(event, captor.getValue().getProviderEvent());
    }

    @Test
    void netPaidReservesBothProcessingAndCompletedRefunds() {
        Long reservationId = 11L;
        PaymentTransaction paid = PaymentTransaction.builder()
                .status(PaymentStatus.SUCCESS)
                .amount(100_000L)
                .build();
        PaymentRefund processing = PaymentRefund.builder()
                .status(RefundStatus.PROCESSING)
                .amount(30_000L)
                .build();
        PaymentRefund succeeded = PaymentRefund.builder()
                .status(RefundStatus.SUCCEEDED)
                .amount(20_000L)
                .build();

        when(transactionRepository.findByReservationId(reservationId)).thenReturn(List.of(paid));
        when(refundRepository.findByReservationId(reservationId))
                .thenReturn(List.of(processing, succeeded));

        assertEquals(50_000L, refundService.getNetPaidAmount(reservationId));
    }

    @Test
    void netPaidFallsBackToLegacyCompletedRefundWithoutSubtractingTwice() {
        Long reservationId = 12L;
        PaymentTransaction legacyRefunded = PaymentTransaction.builder()
                .id("legacy-payment")
                .status(PaymentStatus.REFUNDED)
                .amount(100_000L)
                .refundAmount(30_000L)
                .build();

        when(transactionRepository.findByReservationId(reservationId))
                .thenReturn(List.of(legacyRefunded));
        when(refundRepository.findByReservationId(reservationId)).thenReturn(List.of());

        assertEquals(70_000L, refundService.getNetPaidAmount(reservationId));
    }

    @Test
    void outstandingReservedAmountExcludesOnlyCompletedRefunds() {
        Long reservationId = 14L;
        when(refundRepository.findByReservationId(reservationId)).thenReturn(List.of(
                PaymentRefund.builder().status(RefundStatus.REQUESTED).amount(30_000L).build(),
                PaymentRefund.builder().status(RefundStatus.FAILED).amount(10_000L).build(),
                PaymentRefund.builder().status(RefundStatus.SUCCEEDED).amount(20_000L).build()));

        assertEquals(40_000L, refundService.getOutstandingReservedRefundAmount(reservationId));
    }

    @Test
    void completedPartialRefundKeepsPaymentSuccessful() {
        PaymentTransaction payment = PaymentTransaction.builder()
                .id("partial-refund-payment")
                .provider(PaymentProvider.SEPAY)
                .status(PaymentStatus.REFUND_PENDING)
                .amount(100_000L)
                .build();
        PaymentRefund completed = PaymentRefund.builder()
                .paymentTransaction(payment)
                .channel(com.hotel.backend.constant.RefundChannel.MANUAL_BANK_TRANSFER)
                .status(RefundStatus.SUCCEEDED)
                .amount(30_000L)
                .completedAt(java.time.LocalDateTime.now())
                .build();
        when(refundRepository.findByPaymentTransactionId(payment.getId()))
                .thenReturn(List.of(completed));

        refundService.syncLegacyPaymentState(payment);

        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
        assertEquals(30_000L, payment.getRefundAmount());
        assertNull(payment.getRefundCompletedAt());
        verify(transactionRepository).save(payment);
    }

    @Test
    void failedRefundKeepsPaymentRefundPending() {
        PaymentTransaction payment = PaymentTransaction.builder()
                .id("failed-refund-payment")
                .provider(PaymentProvider.VNPAY)
                .status(PaymentStatus.REFUND_PENDING)
                .amount(100_000L)
                .build();
        PaymentRefund failed = PaymentRefund.builder()
                .paymentTransaction(payment)
                .channel(com.hotel.backend.constant.RefundChannel.VNPAY_ORIGINAL)
                .status(RefundStatus.FAILED)
                .amount(30_000L)
                .build();
        when(refundRepository.findByPaymentTransactionId(payment.getId()))
                .thenReturn(List.of(failed));

        refundService.syncLegacyPaymentState(payment);

        assertEquals(PaymentStatus.REFUND_PENDING, payment.getStatus());
        assertEquals(30_000L, payment.getRefundAmount());
        assertNull(payment.getRefundCompletedAt());
        verify(transactionRepository).save(payment);
    }

    @Test
    void completedFullRefundMarksPaymentRefunded() {
        PaymentTransaction payment = PaymentTransaction.builder()
                .id("full-refund-payment")
                .provider(PaymentProvider.VNPAY)
                .status(PaymentStatus.REFUND_PENDING)
                .amount(100_000L)
                .build();
        java.time.LocalDateTime completedAt = java.time.LocalDateTime.now();
        PaymentRefund completed = PaymentRefund.builder()
                .paymentTransaction(payment)
                .channel(com.hotel.backend.constant.RefundChannel.VNPAY_ORIGINAL)
                .status(RefundStatus.SUCCEEDED)
                .amount(100_000L)
                .completedAt(completedAt)
                .build();
        when(refundRepository.findByPaymentTransactionId(payment.getId()))
                .thenReturn(List.of(completed));

        refundService.syncLegacyPaymentState(payment);

        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
        assertEquals(100_000L, payment.getRefundAmount());
        assertEquals(completedAt, payment.getRefundCompletedAt());
        verify(transactionRepository).save(payment);
    }

    @Test
    void publicRefundSummarySeparatesCompletedAndOutstandingAmounts() {
        String paymentId = "mixed-refund-payment";
        PaymentRefund completed = PaymentRefund.builder()
                .channel(com.hotel.backend.constant.RefundChannel.MANUAL_BANK_TRANSFER)
                .status(RefundStatus.SUCCEEDED)
                .amount(20_000L)
                .updatedAt(java.time.LocalDateTime.now().minusMinutes(1))
                .build();
        PaymentRefund pending = PaymentRefund.builder()
                .channel(com.hotel.backend.constant.RefundChannel.MANUAL_BANK_TRANSFER)
                .status(RefundStatus.READY_FOR_MANUAL_TRANSFER)
                .amount(10_000L)
                .updatedAt(java.time.LocalDateTime.now())
                .build();
        when(refundRepository.findByPaymentTransactionId(paymentId))
                .thenReturn(List.of(completed, pending));

        PaymentRefundService.PaymentRefundSummary summary =
                refundService.getPaymentRefundSummary(paymentId);

        assertEquals(20_000L, summary.completedAmount());
        assertEquals(10_000L, summary.outstandingAmount());
        assertEquals(com.hotel.backend.constant.RefundChannel.MANUAL_BANK_TRANSFER,
                summary.latestChannel());
        assertEquals(RefundStatus.READY_FOR_MANUAL_TRANSFER, summary.latestStatus());
    }

    @Test
    void allocationFailsClosedWhenLegacyCompletedRefundHasNoLedger() {
        Long reservationId = 13L;
        Reservation reservation = new Reservation();
        reservation.setId(reservationId);
        PaymentTransaction legacyRefunded = PaymentTransaction.builder()
                .id("legacy-unmigrated")
                .reservation(reservation)
                .txnRef("LEGACY-UNMIGRATED")
                .provider(PaymentProvider.VNPAY)
                .status(PaymentStatus.REFUNDED)
                .amount(100_000L)
                .refundAmount(30_000L)
                .build();

        when(reservationRepository.findByIdForUpdate(reservationId))
                .thenReturn(Optional.of(reservation));
        when(transactionRepository.findByReservationId(reservationId))
                .thenReturn(List.of(legacyRefunded));
        when(recipientRepository.findFirstByReservationIdAndStatusInOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(reservationId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        when(refundRepository.findByPaymentTransactionId(legacyRefunded.getId()))
                .thenReturn(List.of());

        assertThrows(RuntimeException.class, () -> refundService.requestReservationRefund(
                reservationId, 70_000L, PaymentProvider.VNPAY,
                "Không được hoàn trùng dữ liệu cũ", "unit_test"));
    }
}
