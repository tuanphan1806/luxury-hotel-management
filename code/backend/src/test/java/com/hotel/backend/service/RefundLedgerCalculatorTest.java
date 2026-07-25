package com.hotel.backend.service;

import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.constant.RefundSourceType;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.entity.PaymentRefund;
import com.hotel.backend.entity.PaymentTransaction;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RefundLedgerCalculatorTest {

    private static final EnumSet<RefundStatus> RESERVED = EnumSet.of(
            RefundStatus.AWAITING_CUSTOMER_INFO,
            RefundStatus.READY_FOR_MANUAL_TRANSFER,
            RefundStatus.REQUESTED,
            RefundStatus.PROCESSING,
            RefundStatus.SUCCEEDED,
            RefundStatus.FAILED,
            RefundStatus.MANUAL_REVIEW);

    private final RefundLedgerCalculator calculator = new RefundLedgerCalculator();

    @Test
    void summarizeKeepsCompletedOutstandingAndLatestSemantics() {
        PaymentRefund completed = refund(
                "completed", RefundStatus.SUCCEEDED, 20_000L,
                RefundChannel.CASH_AT_COUNTER,
                LocalDateTime.of(2026, 7, 23, 20, 0));
        PaymentRefund pending = refund(
                "pending", RefundStatus.PROCESSING, 30_000L,
                RefundChannel.MANUAL_BANK_TRANSFER,
                LocalDateTime.of(2026, 7, 23, 21, 0));

        RefundLedgerCalculator.RefundSummary summary =
                calculator.summarize(List.of(completed, pending), RESERVED);

        assertEquals(20_000L, summary.completedAmount());
        assertEquals(30_000L, summary.outstandingAmount());
        assertEquals(RefundChannel.MANUAL_BANK_TRANSFER, summary.latestChannel());
        assertEquals(RefundStatus.PROCESSING, summary.latestStatus());
    }

    @Test
    void netPaidSubtractsReservedReservationRefundsButNotUnacceptedMoneyRefunds() {
        PaymentTransaction payment = PaymentTransaction.builder()
                .id("payment-1")
                .status(PaymentStatus.SUCCESS)
                .amount(100_000L)
                .acceptedAmount(100_000L)
                .build();
        PaymentRefund cancellationRefund = refund(
                "refund-1", RefundStatus.PROCESSING, 30_000L,
                RefundChannel.MANUAL_BANK_TRANSFER, LocalDateTime.now());
        cancellationRefund.setSourceType(RefundSourceType.ACCEPTED_ALLOCATION);
        PaymentRefund unacceptedMoneyRefund = refund(
                "refund-2", RefundStatus.PROCESSING, 10_000L,
                RefundChannel.MANUAL_BANK_TRANSFER, LocalDateTime.now());
        unacceptedMoneyRefund.setSourceType(RefundSourceType.UNACCEPTED_PAYMENT);

        long netPaid = calculator.getNetPaidAmount(
                List.of(payment),
                List.of(cancellationRefund, unacceptedMoneyRefund),
                RESERVED);

        assertEquals(70_000L, netPaid);
    }

    @Test
    void legacyRefundGapIsOnlySubtractedBeyondRecordedSucceededRefunds() {
        PaymentTransaction payment = PaymentTransaction.builder()
                .id("payment-legacy")
                .status(PaymentStatus.REFUNDED)
                .amount(100_000L)
                .acceptedAmount(100_000L)
                .refundAmount(40_000L)
                .build();
        PaymentRefund recorded = refund(
                "recorded", RefundStatus.SUCCEEDED, 25_000L,
                RefundChannel.MANUAL_BANK_TRANSFER, LocalDateTime.now());
        recorded.setPaymentTransaction(payment);
        recorded.setSourceType(RefundSourceType.UNACCEPTED_PAYMENT);

        long netPaid = calculator.getNetPaidAmount(
                List.of(payment), List.of(recorded), RESERVED);

        assertEquals(85_000L, netPaid);
    }

    @Test
    void outstandingReservedExcludesSucceededAndCancelledRows() {
        PaymentRefund processing = refund(
                "processing", RefundStatus.PROCESSING, 30_000L,
                RefundChannel.MANUAL_BANK_TRANSFER, LocalDateTime.now());
        PaymentRefund completed = refund(
                "completed", RefundStatus.SUCCEEDED, 20_000L,
                RefundChannel.CASH_AT_COUNTER, LocalDateTime.now());
        PaymentRefund cancelled = refund(
                "cancelled", RefundStatus.CANCELLED, 10_000L,
                RefundChannel.CASH_AT_COUNTER, LocalDateTime.now());

        assertEquals(30_000L, calculator.getOutstandingReservedRefundAmount(
                List.of(processing, completed, cancelled), RESERVED));
    }

    private PaymentRefund refund(
            String id,
            RefundStatus status,
            long amount,
            RefundChannel channel,
            LocalDateTime updatedAt) {
        return PaymentRefund.builder()
                .id(id)
                .status(status)
                .amount(amount)
                .channel(channel)
                .updatedAt(updatedAt)
                .build();
    }
}
