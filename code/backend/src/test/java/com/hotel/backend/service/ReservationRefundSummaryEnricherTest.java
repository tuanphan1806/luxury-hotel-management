package com.hotel.backend.service;

import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.constant.RefundDestinationStatus;
import com.hotel.backend.constant.RefundRecipientStatus;
import com.hotel.backend.constant.RefundRoute;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.entity.PaymentRefund;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.RefundRecipient;
import com.hotel.backend.repository.PaymentRefundRepository;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.RefundRecipientRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationRefundSummaryEnricherTest {

    @Mock
    private PaymentRefundRepository refundRepository;

    @Mock
    private PaymentTransactionRepository transactionRepository;

    @Mock
    private RefundRecipientRepository recipientRepository;

    @InjectMocks
    private ReservationRefundSummaryEnricher enricher;

    @Test
    void nullAndNonRefundableResponsesKeepTheExistingContract() {
        assertNull(enricher.apply(null));
        verifyNoInteractions(refundRepository, transactionRepository, recipientRepository);

        ReservationResponse response = ReservationResponse.builder()
                .id(91L)
                .status(ReservationStatus.CONFIRMED)
                .build();
        when(refundRepository.findByReservationId(91L)).thenReturn(List.of());

        assertSame(response, enricher.apply(response));
        verifyNoInteractions(transactionRepository, recipientRepository);
    }

    @Test
    void cancellationWithoutRefundRowsUsesPreSubmittedBankDestination() {
        ReservationResponse response = ReservationResponse.builder()
                .id(92L)
                .status(ReservationStatus.CANCELLATION_PENDING)
                .build();
        PaymentTransaction paid = PaymentTransaction.builder()
                .status(PaymentStatus.SUCCESS)
                .build();
        RefundRecipient recipient = RefundRecipient.builder()
                .status(RefundRecipientStatus.SUBMITTED)
                .bankName("TPBank")
                .accountNumberLast4("2857")
                .build();
        when(refundRepository.findByReservationId(92L)).thenReturn(List.of());
        when(transactionRepository.findByReservationId(92L)).thenReturn(List.of(paid));
        when(recipientRepository.findFirstByReservationIdAndStatusInOrderByCreatedAtDesc(
                eq(92L), any())).thenReturn(Optional.of(recipient));

        ReservationResponse result = enricher.apply(response);

        assertEquals(RefundRoute.MANUAL_BANK_TRANSFER, result.getRefundRoute());
        assertEquals(RefundDestinationStatus.SUBMITTED, result.getRefundDestinationStatus());
        assertEquals("TPBank ****2857", result.getRefundBankSummary());
    }

    @Test
    void existingRefundsPreserveMixedRouteAndVerifiedRecipientSummary() {
        ReservationResponse response = ReservationResponse.builder()
                .id(93L)
                .status(ReservationStatus.CANCELLATION_PENDING)
                .build();
        RefundRecipient recipient = RefundRecipient.builder()
                .status(RefundRecipientStatus.VERIFIED)
                .bankName("TPBank")
                .accountNumberLast4("2857")
                .build();
        PaymentRefund manual = PaymentRefund.builder()
                .channel(RefundChannel.MANUAL_BANK_TRANSFER)
                .status(RefundStatus.AWAITING_CUSTOMER_INFO)
                .amount(40_000L)
                .requestedAt(LocalDateTime.of(2026, 7, 23, 22, 0))
                .recipient(recipient)
                .build();
        PaymentRefund cash = PaymentRefund.builder()
                .channel(RefundChannel.CASH_AT_COUNTER)
                .status(RefundStatus.SUCCEEDED)
                .amount(10_000L)
                .requestedAt(LocalDateTime.of(2026, 7, 23, 21, 0))
                .build();
        when(refundRepository.findByReservationId(93L))
                .thenReturn(List.of(cash, manual));

        ReservationResponse result = enricher.apply(response);

        assertEquals(RefundRoute.MIXED, result.getRefundRoute());
        assertEquals(RefundDestinationStatus.VERIFIED, result.getRefundDestinationStatus());
        assertEquals("TPBank ****2857", result.getRefundBankSummary());
        assertEquals(2, result.getRefunds().size());
        assertEquals(RefundChannel.MANUAL_BANK_TRANSFER, result.getRefunds().get(0).getChannel());
    }
}
