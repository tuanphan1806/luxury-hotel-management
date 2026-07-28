package com.hotel.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.constant.*;
import com.hotel.backend.entity.*;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.BusinessDayCloseRepository;
import com.hotel.backend.repository.FinancialJournalEntryRepository;
import com.hotel.backend.repository.FinancialJournalLineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinancialJournalServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-28T14:00:00Z");

    @Mock FinancialJournalEntryRepository entryRepository;
    @Mock FinancialJournalLineRepository lineRepository;
    @Mock BusinessDayCloseRepository closeRepository;
    @Mock BusinessDayLockService businessDayLockService;
    @Mock ReservationAuditService auditService;

    private FinancialJournalService service;

    @BeforeEach
    void setUp() {
        service = new FinancialJournalService(
                entryRepository,
                lineRepository,
                closeRepository,
                businessDayLockService,
                auditService,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(entryRepository.saveAndFlush(any(FinancialJournalEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void cashPaymentDebitsCashAndCreditsCustomerDeposit() {
        PaymentTransaction payment = payment(PaymentProvider.CASH, 120_000L, 120_000L, 0L);

        FinancialJournalEntry entry = service.postPayment(payment);

        assertEquals(FinancialPostingKind.PAYMENT_RECEIVED, entry.getPostingKind());
        assertEquals(BigDecimal.valueOf(120_000L), entry.getTotalDebit());
        assertEquals(entry.getTotalDebit(), entry.getTotalCredit());
        List<FinancialJournalLine> lines = capturedLines();
        assertLine(lines, FinancialAccountCode.CASH_ON_HAND, FinancialEntryDirection.DEBIT, 120_000L);
        assertLine(lines, FinancialAccountCode.CUSTOMER_DEPOSIT, FinancialEntryDirection.CREDIT, 120_000L);
    }

    @Test
    void sePayOverpaymentSplitsAcceptedAndRefundPayable() {
        PaymentTransaction payment = payment(PaymentProvider.SEPAY, 120_000L, 100_000L, 20_000L);
        PaymentProviderEvent event = providerEvent("event-1", "in", 120_000L, payment.getPaidAtUtc());

        FinancialJournalEntry entry = service.postSePayPayment(event, payment);

        assertEquals(BigDecimal.valueOf(120_000L), entry.getTotalDebit());
        List<FinancialJournalLine> lines = capturedLines();
        assertLine(lines, FinancialAccountCode.BANK_SEPAY, FinancialEntryDirection.DEBIT, 120_000L);
        assertLine(lines, FinancialAccountCode.CUSTOMER_DEPOSIT, FinancialEntryDirection.CREDIT, 100_000L);
        assertLine(lines, FinancialAccountCode.REFUND_PAYABLE, FinancialEntryDirection.CREDIT, 20_000L);
    }

    @Test
    void matchedPreviouslyObservedProviderCashReclassifiesWithoutDebitingBankTwice() {
        PaymentTransaction payment = payment(PaymentProvider.SEPAY, 120_000L, 100_000L, 20_000L);
        PaymentProviderEvent event = providerEvent("event-2", "in", 120_000L, payment.getPaidAtUtc());
        when(entryRepository.existsBySourceTypeAndSourceIdAndPostingKind(
                FinancialSourceType.PAYMENT_PROVIDER_EVENT,
                "event-2",
                FinancialPostingKind.PROVIDER_CASH_OBSERVED)).thenReturn(true);

        FinancialJournalEntry entry = service.postSePayPayment(event, payment);

        assertEquals(FinancialPostingKind.PAYMENT_ALLOCATED, entry.getPostingKind());
        List<FinancialJournalLine> lines = capturedLines();
        assertLine(lines, FinancialAccountCode.UNRECONCILED_FUNDS,
                FinancialEntryDirection.DEBIT, 120_000L);
        assertTrue(lines.stream().noneMatch(line -> line.getAccountCode()
                == FinancialAccountCode.BANK_SEPAY));
    }

    @Test
    void unmatchedIncomingProviderMovementRemainsVisible() {
        PaymentProviderEvent event = providerEvent("event-3", "in", 75_000L, NOW);

        FinancialJournalEntry entry = service.postUnmatchedProviderMovement(event);

        assertEquals(FinancialPostingKind.PROVIDER_CASH_OBSERVED, entry.getPostingKind());
        List<FinancialJournalLine> lines = capturedLines();
        assertLine(lines, FinancialAccountCode.BANK_SEPAY, FinancialEntryDirection.DEBIT, 75_000L);
        assertLine(lines, FinancialAccountCode.UNRECONCILED_FUNDS,
                FinancialEntryDirection.CREDIT, 75_000L);
    }

    @Test
    void completedCashRefundReducesCustomerDepositAndCash() {
        PaymentTransaction payment = payment(PaymentProvider.CASH, 100_000L, 100_000L, 0L);
        PaymentRefund refund = PaymentRefund.builder()
                .id("refund-1")
                .paymentTransaction(payment)
                .reservation(payment.getReservation())
                .sourceType(RefundSourceType.ACCEPTED_ALLOCATION)
                .provider(PaymentProvider.CASH)
                .channel(RefundChannel.CASH_AT_COUNTER)
                .status(RefundStatus.SUCCEEDED)
                .amount(40_000L)
                .requestedAmount(40_000L)
                .actualRefundAmount(40_000L)
                .requestId("request-1")
                .refundCode("RF0000000000000001")
                .completedAtUtc(NOW)
                .build();

        service.postRefund(refund);

        List<FinancialJournalLine> lines = capturedLines();
        assertLine(lines, FinancialAccountCode.CUSTOMER_DEPOSIT,
                FinancialEntryDirection.DEBIT, 40_000L);
        assertLine(lines, FinancialAccountCode.CASH_ON_HAND,
                FinancialEntryDirection.CREDIT, 40_000L);
    }

    @Test
    void immutableInvoicePostsBalancedRevenueBreakdown() {
        Reservation reservation = reservation();
        ReservationInvoice invoice = ReservationInvoice.builder()
                .id(51L)
                .reservation(reservation)
                .invoiceNumber("INV-RES-TEST")
                .issuedAtUtc(NOW)
                .totalAmount(BigDecimal.valueOf(500_000L))
                .actualRoomCharge(BigDecimal.valueOf(430_000L))
                .additionalFee(BigDecimal.valueOf(20_000L))
                .addOnServiceAmount(BigDecimal.valueOf(40_000L))
                .discountAmount(BigDecimal.valueOf(10_000L))
                .taxAmount(BigDecimal.valueOf(20_000L))
                .snapshotJson("{}")
                .snapshotHash("a".repeat(64))
                .build();

        FinancialJournalEntry entry = service.postInvoice(invoice);

        assertEquals(BigDecimal.valueOf(510_000L), entry.getTotalDebit());
        assertEquals(entry.getTotalDebit(), entry.getTotalCredit());
        List<FinancialJournalLine> lines = capturedLines();
        assertLine(lines, FinancialAccountCode.ROOM_REVENUE,
                FinancialEntryDirection.CREDIT, 430_000L);
        assertLine(lines, FinancialAccountCode.SERVICE_REVENUE,
                FinancialEntryDirection.CREDIT, 60_000L);
        assertLine(lines, FinancialAccountCode.TAX_PAYABLE,
                FinancialEntryDirection.CREDIT, 20_000L);
        assertLine(lines, FinancialAccountCode.DISCOUNT,
                FinancialEntryDirection.DEBIT, 10_000L);
    }

    @Test
    void closedDayRejectsOperatorPostingButLateProviderUsesCurrentDay() {
        Instant oldOccurredAt = Instant.parse("2026-07-26T12:00:00Z");
        LocalDate oldBusinessDate = LocalDate.of(2026, 7, 26);
        when(closeRepository.existsByBusinessDate(oldBusinessDate)).thenReturn(true);
        PaymentTransaction cash = payment(PaymentProvider.CASH, 50_000L, 50_000L, 0L);
        cash.setPaidAtUtc(oldOccurredAt);

        AppException exception = assertThrows(AppException.class, () -> service.postPayment(cash));
        assertEquals(ErrorCode.BUSINESS_DAY_CLOSED, exception.getErrorCode());

        PaymentTransaction sePay = payment(PaymentProvider.SEPAY, 50_000L, 50_000L, 0L);
        sePay.setPaidAtUtc(oldOccurredAt);
        PaymentProviderEvent event = providerEvent("event-late", "in", 50_000L, oldOccurredAt);
        FinancialJournalEntry lateEntry = service.postSePayPayment(event, sePay);

        assertTrue(lateEntry.isLatePosting());
        assertEquals(LocalDate.of(2026, 7, 28), lateEntry.getBusinessDate());
        assertEquals(oldBusinessDate, lateEntry.getOriginalBusinessDate());
        verify(auditService).recordSystem(
                any(), any(), any(),
                eq(ReservationAuditAction.FINANCIAL_LATE_POSTING),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void concurrentRetryReturnsEntryCreatedWhileWaitingForBusinessDateLock() {
        PaymentTransaction payment = payment(PaymentProvider.CASH, 90_000L, 90_000L, 0L);
        FinancialJournalEntry concurrent = FinancialJournalEntry.builder()
                .id(77L)
                .sourceType(FinancialSourceType.PAYMENT_TRANSACTION)
                .sourceId(payment.getId())
                .postingKind(FinancialPostingKind.PAYMENT_RECEIVED)
                .build();
        when(entryRepository.findBySourceTypeAndSourceIdAndPostingKind(
                FinancialSourceType.PAYMENT_TRANSACTION,
                payment.getId(),
                FinancialPostingKind.PAYMENT_RECEIVED))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(concurrent));

        FinancialJournalEntry result = service.postPayment(payment);

        assertEquals(concurrent, result);
        verify(businessDayLockService).lock(LocalDate.of(2026, 7, 28));
        verify(entryRepository, never()).saveAndFlush(any(FinancialJournalEntry.class));
        verify(lineRepository, never()).saveAllAndFlush(any());
    }

    @SuppressWarnings("unchecked")
    private List<FinancialJournalLine> capturedLines() {
        ArgumentCaptor<List<FinancialJournalLine>> captor = ArgumentCaptor.forClass(List.class);
        verify(lineRepository).saveAllAndFlush(captor.capture());
        return captor.getValue();
    }

    private void assertLine(
            List<FinancialJournalLine> lines,
            FinancialAccountCode account,
            FinancialEntryDirection direction,
            long amount) {
        assertTrue(lines.stream().anyMatch(line -> line.getAccountCode() == account
                && line.getDirection() == direction
                && line.getAmount().compareTo(BigDecimal.valueOf(amount)) == 0),
                () -> "Missing line " + direction + " " + account + " " + amount);
    }

    private PaymentTransaction payment(
            PaymentProvider provider,
            long received,
            long accepted,
            long refundRequired) {
        return PaymentTransaction.builder()
                .id("payment-" + provider + "-" + received + "-" + accepted)
                .reservation(reservation())
                .txnRef("TXN" + provider + received + accepted)
                .provider(provider)
                .purpose(PaymentPurpose.FINAL_PAYMENT)
                .status(PaymentStatus.SUCCESS)
                .amount(received)
                .expectedAmount(received)
                .receivedAmount(received)
                .acceptedAmount(accepted)
                .refundRequiredAmount(refundRequired)
                .currency("VND")
                .paidAtUtc(NOW)
                .build();
    }

    private PaymentProviderEvent providerEvent(
            String id,
            String transferType,
            long amount,
            Instant occurredAt) {
        return PaymentProviderEvent.builder()
                .id(id)
                .provider(PaymentProvider.SEPAY)
                .providerEventId("provider-" + id)
                .providerReference("reference-" + id)
                .dedupKey("dedup-" + id)
                .status(PaymentProviderEventStatus.REVIEW_REQUIRED)
                .payloadHash("b".repeat(64))
                .transferType(transferType)
                .amount(amount)
                .providerOccurredAtUtc(occurredAt)
                .receivedAtUtc(NOW)
                .build();
    }

    private Reservation reservation() {
        Reservation reservation = Reservation.builder()
                .reservationCode("RES-TEST")
                .status(ReservationStatus.CHECKED_IN)
                .totalAmount(BigDecimal.valueOf(500_000L))
                .build();
        reservation.setId(101L);
        return reservation;
    }
}
