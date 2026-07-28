package com.hotel.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.constant.*;
import com.hotel.backend.dto.request.CloseBusinessDayRequest;
import com.hotel.backend.entity.*;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.*;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessDayCloseServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-28T14:00:00Z");
    private static final LocalDate PREVIOUS_DAY = LocalDate.of(2026, 7, 27);

    @Mock BusinessDayCloseRepository closeRepository;
    @Mock BusinessDayLockService businessDayLockService;
    @Mock FinancialJournalEntryRepository entryRepository;
    @Mock FinancialJournalLineRepository lineRepository;
    @Mock CashierShiftRepository shiftRepository;
    @Mock PaymentProviderEventRepository providerEventRepository;
    @Mock PaymentTransactionRepository paymentRepository;
    @Mock PaymentRefundRepository refundRepository;
    @Mock ReservationInvoiceRepository invoiceRepository;
    @Mock ReservationAuditService auditService;

    private BusinessDayCloseService service;
    private User admin;

    @BeforeEach
    void setUp() {
        service = new BusinessDayCloseService(
                closeRepository, businessDayLockService, entryRepository, lineRepository,
                shiftRepository, providerEventRepository, paymentRepository,
                refundRepository, invoiceRepository, auditService,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        admin = User.builder()
                .username("admin")
                .fullName("Quản trị viên")
                .type(UserType.ADMIN)
                .status(UserStatus.ACTIVE)
                .build();
        admin.setId(1L);
        emptyDay(PREVIOUS_DAY);
    }

    @Test
    void previewRejectsCurrentBusinessDateUntilItHasEnded() {
        LocalDate currentDay = LocalDate.of(2026, 7, 28);
        emptyDay(currentDay);

        var response = service.preview(currentDay, admin);

        assertFalse(response.closeAllowed());
        assertTrue(response.blockers().contains("DATE_NOT_FINISHED"));
    }

    @Test
    void previewExposesOperationalBlockersInsteadOfHidingThem() {
        when(shiftRepository.countByBusinessDateAndStatusIn(eq(PREVIOUS_DAY), any()))
                .thenReturn(1L);
        when(providerEventRepository.countUnresolvedInRange(any(), any(), any()))
                .thenReturn(2L);
        PaymentTransaction unposted = PaymentTransaction.builder()
                .id("payment-unposted")
                .build();
        when(paymentRepository.findByStatusInAndPaidAtUtcGreaterThanEqualAndPaidAtUtcLessThan(
                any(), any(), any())).thenReturn(List.of(unposted));
        when(entryRepository.existsByPaymentTransactionId("payment-unposted")).thenReturn(false);
        FinancialJournalEntry entry = journalEntry();
        FinancialJournalLine unreconciled = FinancialJournalLine.builder()
                .journalEntry(entry)
                .lineNumber(1)
                .accountCode(FinancialAccountCode.UNRECONCILED_FUNDS)
                .direction(FinancialEntryDirection.CREDIT)
                .amount(BigDecimal.valueOf(75_000L))
                .createdAtUtc(NOW)
                .build();
        when(lineRepository.findAllByJournalEntryBusinessDate(PREVIOUS_DAY))
                .thenReturn(List.of(unreconciled));

        var response = service.preview(PREVIOUS_DAY, admin);

        assertFalse(response.closeAllowed());
        assertTrue(response.blockers().contains("OPEN_CASHIER_SHIFTS:1"));
        assertTrue(response.blockers().contains("UNRESOLVED_PROVIDER_EVENTS:2"));
        assertTrue(response.blockers().contains("UNPOSTED_PAYMENTS:1"));
        assertTrue(response.blockers().contains("UNRECONCILED_FUNDS:75000"));
        verify(paymentRepository).findByStatusInAndPaidAtUtcGreaterThanEqualAndPaidAtUtcLessThan(
                argThat(statuses -> statuses.containsAll(List.of(
                        PaymentStatus.SUCCESS,
                        PaymentStatus.REFUND_PENDING,
                        PaymentStatus.REFUNDED))),
                any(), any());
    }

    @Test
    void closeRevalidatesUnderDateLockAndStoresImmutableSnapshot() {
        when(closeRepository.saveAndFlush(any(BusinessDayClose.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.close(
                PREVIOUS_DAY,
                new CloseBusinessDayRequest("Đã đối chiếu ca và ngân hàng"),
                admin);

        assertTrue(response.closed());
        assertEquals(BusinessDayCloseStatus.CLOSED, response.status());
        assertEquals("Đã đối chiếu ca và ngân hàng", response.note());
        verify(businessDayLockService).lock(PREVIOUS_DAY);
        ArgumentCaptor<BusinessDayClose> captor = ArgumentCaptor.forClass(BusinessDayClose.class);
        verify(closeRepository).saveAndFlush(captor.capture());
        assertEquals(64, captor.getValue().getSummaryHash().length());
        verify(auditService).recordTargetForUser(
                eq(admin), eq("BUSINESS_DAY"), eq(PREVIOUS_DAY.toString()),
                eq(ReservationAuditAction.BUSINESS_DAY_CLOSED), any(), any());
    }

    @Test
    void closeFailsWithoutWritingWhenPreviewHasBlockers() {
        when(shiftRepository.countByBusinessDateAndStatusIn(eq(PREVIOUS_DAY), any()))
                .thenReturn(1L);

        AppException exception = assertThrows(AppException.class, () -> service.close(
                PREVIOUS_DAY, new CloseBusinessDayRequest(null), admin));

        assertEquals(ErrorCode.BUSINESS_DAY_CLOSE_BLOCKED, exception.getErrorCode());
    }

    @Test
    void staffCannotReadFinancialClosePreview() {
        User staff = User.builder().type(UserType.STAFF).status(UserStatus.ACTIVE).build();
        staff.setId(2L);

        AppException exception = assertThrows(
                AppException.class,
                () -> service.preview(PREVIOUS_DAY, staff));

        assertEquals(ErrorCode.CASHIER_SHIFT_FORBIDDEN, exception.getErrorCode());
    }

    private void emptyDay(LocalDate businessDate) {
        lenient().when(closeRepository.findByBusinessDate(businessDate))
                .thenReturn(Optional.empty());
        lenient().when(entryRepository.findAllByBusinessDateOrderByPostedAtUtcAscIdAsc(businessDate))
                .thenReturn(List.of());
        lenient().when(lineRepository.findAllByJournalEntryBusinessDate(businessDate))
                .thenReturn(List.of());
        lenient().when(shiftRepository.countByBusinessDateAndStatusIn(eq(businessDate), any()))
                .thenReturn(0L);
        lenient().when(shiftRepository.findAllByBusinessDate(businessDate)).thenReturn(List.of());
        lenient().when(providerEventRepository.countUnresolvedInRange(any(), any(), any()))
                .thenReturn(0L);
        lenient().when(paymentRepository.findByStatusInAndPaidAtUtcGreaterThanEqualAndPaidAtUtcLessThan(
                any(), any(), any())).thenReturn(List.of());
        lenient().when(refundRepository.findByStatusAndCompletedAtUtcGreaterThanEqualAndCompletedAtUtcLessThan(
                eq(RefundStatus.SUCCEEDED), any(), any())).thenReturn(List.of());
        lenient().when(invoiceRepository.findByIssuedAtUtcGreaterThanEqualAndIssuedAtUtcLessThan(any(), any()))
                .thenReturn(List.of());
        lenient().when(refundRepository.findOperationalQueue(any())).thenReturn(List.of());
    }

    private FinancialJournalEntry journalEntry() {
        return FinancialJournalEntry.builder()
                .id(10L)
                .entryNumber("FJ-TEST")
                .businessDate(PREVIOUS_DAY)
                .originalBusinessDate(PREVIOUS_DAY)
                .occurredAtUtc(NOW)
                .postedAtUtc(NOW)
                .sourceType(FinancialSourceType.PAYMENT_PROVIDER_EVENT)
                .sourceId("event-test")
                .postingKind(FinancialPostingKind.PROVIDER_CASH_OBSERVED)
                .currency("VND")
                .description("test")
                .totalDebit(BigDecimal.valueOf(75_000L))
                .totalCredit(BigDecimal.valueOf(75_000L))
                .detailJson(new ObjectMapper().createObjectNode())
                .createdAtUtc(NOW)
                .build();
    }
}
