package com.hotel.backend.service;

import com.hotel.backend.constant.CashMovementDirection;
import com.hotel.backend.constant.CashMovementSourceType;
import com.hotel.backend.constant.CashMovementType;
import com.hotel.backend.constant.CashierShiftStatus;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.request.CloseCashierShiftRequest;
import com.hotel.backend.dto.request.CashMovementRequest;
import com.hotel.backend.dto.request.OpenCashierShiftRequest;
import com.hotel.backend.dto.response.MoneyReportResponse;
import com.hotel.backend.entity.CashMovement;
import com.hotel.backend.entity.CashierShift;
import com.hotel.backend.entity.PaymentRefund;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.CashMovementRepository;
import com.hotel.backend.repository.CashierShiftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CashierShiftServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-28T13:00:00Z");

    @Mock CashierShiftRepository shiftRepository;
    @Mock CashMovementRepository movementRepository;
    @Mock ReservationAuditService auditService;
    @Mock MoneyReportService moneyReportService;

    private CashierShiftService service;
    private User staff;

    @BeforeEach
    void setUp() {
        service = new CashierShiftService(
                shiftRepository,
                movementRepository,
                auditService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        staff = User.builder()
                .fullName("Nhân viên ca sáng")
                .username("cashier")
                .email("cashier@example.com")
                .type(UserType.STAFF)
                .status(UserStatus.ACTIVE)
                .build();
        staff.setId(7L);
    }

    @Test
    void openStartsAutomaticShiftWithoutOpeningFloat() {
        when(shiftRepository.findActiveByUserIdForUpdate(eq(7L), any()))
                .thenReturn(Optional.empty());
        when(shiftRepository.saveAndFlush(any(CashierShift.class)))
                .thenAnswer(invocation -> {
                    CashierShift shift = invocation.getArgument(0);
                    shift.setId(11L);
                    return shift;
                });
        when(movementRepository.calculateExpectedCash(11L))
                .thenReturn(BigDecimal.ZERO);
        when(movementRepository.findAllByCashierShiftIdOrderByOccurredAtUtcAscIdAsc(11L))
                .thenReturn(List.of());

        OpenCashierShiftRequest request = new OpenCashierShiftRequest();
        request.setOpeningCashAmount(BigDecimal.valueOf(500_000L));
        request.setNote("Ca sáng");

        var response = service.open(request, staff);

        assertEquals(CashierShiftStatus.OPEN, response.status());
        assertEquals(LocalDate.of(2026, 7, 28), response.businessDate());
        assertEquals(BigDecimal.ZERO, response.openingCashAmount());
        assertEquals(BigDecimal.ZERO, response.expectedCashAmount());
        verify(movementRepository, never()).saveAndFlush(any());
        verify(auditService).recordTargetForUser(
                eq(staff), eq("CASHIER_SHIFT"), eq("11"),
                eq(ReservationAuditAction.CASHIER_SHIFT_OPENED), any(), any());
    }

    @Test
    void recordCashPaymentRequiresOpenShift() {
        when(shiftRepository.findActiveByUserIdForUpdate(eq(7L), any()))
                .thenReturn(Optional.empty());
        PaymentTransaction payment = cashPayment(91L, 120_000L);

        AppException exception = assertThrows(
                AppException.class,
                () -> service.recordCashPayment(payment, staff));

        assertEquals(ErrorCode.CASHIER_SHIFT_REQUIRED, exception.getErrorCode());
        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void recordCashPaymentAllowsAdminWithoutOpeningCashierShift() {
        User admin = User.builder()
                .fullName("Quản trị viên")
                .username("admin")
                .email("admin@example.com")
                .type(UserType.ADMIN)
                .status(UserStatus.ACTIVE)
                .build();
        admin.setId(8L);
        when(shiftRepository.findActiveByUserIdForUpdate(eq(8L), any()))
                .thenReturn(Optional.empty());

        service.recordCashPayment(cashPayment(92L, 135_000L), admin);

        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void recordCashPaymentAppendsReceivedAmountExactlyOnce() {
        CashierShift shift = openShift();
        PaymentTransaction payment = cashPayment(91L, 120_000L);
        when(shiftRepository.findActiveByUserIdForUpdate(eq(7L), any()))
                .thenReturn(Optional.of(shift));
        when(movementRepository.findBySourceTypeAndSourceIdAndMovementType(
                CashMovementSourceType.PAYMENT_TRANSACTION,
                payment.getId(),
                CashMovementType.CASH_PAYMENT))
                .thenReturn(Optional.empty());
        when(movementRepository.saveAndFlush(any(CashMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.recordCashPayment(payment, staff);

        ArgumentCaptor<CashMovement> captor = ArgumentCaptor.forClass(CashMovement.class);
        verify(movementRepository).saveAndFlush(captor.capture());
        CashMovement movement = captor.getValue();
        assertEquals(BigDecimal.valueOf(120_000L), movement.getAmount());
        assertEquals(CashMovementType.CASH_PAYMENT, movement.getMovementType());
        assertEquals(payment, movement.getPaymentTransaction());
        assertEquals(payment.getReservation(), movement.getReservation());
    }

    @Test
    void recordCashRefundAppendsActualAmountAsCashOut() {
        CashierShift shift = openShift();
        PaymentTransaction payment = cashPayment(91L, 120_000L);
        PaymentRefund refund = PaymentRefund.builder()
                .id("refund-cash-1")
                .paymentTransaction(payment)
                .reservation(payment.getReservation())
                .provider(PaymentProvider.CASH)
                .channel(RefundChannel.CASH_AT_COUNTER)
                .status(RefundStatus.SUCCEEDED)
                .amount(80_000L)
                .requestedAmount(80_000L)
                .actualRefundAmount(75_000L)
                .requestId("refund-request-1")
                .refundCode("REFUND-CASH-1")
                .build();
        when(shiftRepository.findActiveByUserIdForUpdate(eq(7L), any()))
                .thenReturn(Optional.of(shift));
        when(movementRepository.findBySourceTypeAndSourceIdAndMovementType(
                CashMovementSourceType.PAYMENT_REFUND,
                refund.getId(),
                CashMovementType.CASH_REFUND))
                .thenReturn(Optional.empty());
        when(movementRepository.saveAndFlush(any(CashMovement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.recordCashRefund(refund, staff);

        ArgumentCaptor<CashMovement> captor = ArgumentCaptor.forClass(CashMovement.class);
        verify(movementRepository).saveAndFlush(captor.capture());
        CashMovement movement = captor.getValue();
        assertEquals(BigDecimal.valueOf(75_000L), movement.getAmount());
        assertEquals(CashMovementType.CASH_REFUND, movement.getMovementType());
        assertEquals(CashMovementDirection.OUT, movement.getDirection());
        assertEquals(refund, movement.getRefund());
    }

    @Test
    void recordCashRefundAllowsAdminWithoutOpeningCashierShift() {
        User admin = User.builder()
                .fullName("Quản trị viên")
                .username("admin")
                .email("admin@example.com")
                .type(UserType.ADMIN)
                .status(UserStatus.ACTIVE)
                .build();
        admin.setId(8L);
        PaymentTransaction payment = cashPayment(93L, 80_000L);
        PaymentRefund refund = PaymentRefund.builder()
                .id("refund-admin-cash")
                .paymentTransaction(payment)
                .reservation(payment.getReservation())
                .provider(PaymentProvider.CASH)
                .channel(RefundChannel.CASH_AT_COUNTER)
                .status(RefundStatus.SUCCEEDED)
                .amount(80_000L)
                .actualRefundAmount(80_000L)
                .requestId("refund-admin-request")
                .refundCode("REFUND-ADMIN-CASH")
                .build();
        when(shiftRepository.findActiveByUserIdForUpdate(eq(8L), any()))
                .thenReturn(Optional.empty());

        service.recordCashRefund(refund, admin);

        verify(movementRepository, never()).saveAndFlush(any());
    }

    @Test
    void listUsesOneBatchMovementSummaryForThePage() {
        CashierShift shift = openShift();
        CashMovementRepository.CashShiftMovementSummary summary =
                org.mockito.Mockito.mock(CashMovementRepository.CashShiftMovementSummary.class);
        when(summary.getShiftId()).thenReturn(shift.getId());
        when(summary.getMovementCount()).thenReturn(3L);
        when(summary.getExpectedCash()).thenReturn(BigDecimal.valueOf(620_000L));
        when(shiftRepository.findAllByOpenedById(eq(staff.getId()), any()))
                .thenReturn(new PageImpl<>(List.of(shift)));
        when(movementRepository.summarizeByCashierShiftIds(List.of(shift.getId())))
                .thenReturn(List.of(summary));

        var response = service.list(org.springframework.data.domain.PageRequest.of(0, 20), staff);

        assertEquals(1, response.getTotalElements());
        assertEquals(3L, response.getContent().get(0).movementCount());
        assertEquals(BigDecimal.valueOf(620_000L),
                response.getContent().get(0).expectedCashAmount());
        verify(movementRepository).summarizeByCashierShiftIds(List.of(shift.getId()));
    }

    @Test
    void currentIncludesOnlineMoneyForTheExactShiftWindow() {
        CashierShift shift = openShift();
        CashierShiftService serviceWithMoney = new CashierShiftService(
                shiftRepository,
                movementRepository,
                auditService,
                moneyReportService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(shiftRepository
                .findFirstByOpenedByIdAndStatusInOrderByOpenedAtUtcDesc(
                        eq(7L), any()))
                .thenReturn(Optional.of(shift));
        when(movementRepository.calculateExpectedCash(11L))
                .thenReturn(BigDecimal.ZERO);
        when(movementRepository
                .findAllByCashierShiftIdOrderByOccurredAtUtcAscIdAsc(11L))
                .thenReturn(List.of());
        when(moneyReportService.summarizeWindow(
                shift.getOpenedAtUtc(), NOW))
                .thenReturn(new MoneyReportResponse.Breakdown(
                        BigDecimal.ZERO,
                        BigDecimal.valueOf(240_000L),
                        BigDecimal.valueOf(240_000L),
                        BigDecimal.ZERO,
                        BigDecimal.valueOf(35_000L),
                        BigDecimal.valueOf(35_000L),
                        BigDecimal.valueOf(205_000L),
                        2L,
                        1L));

        var response = serviceWithMoney.current(staff);

        assertEquals(
                BigDecimal.valueOf(240_000L),
                response.transferIncomeAmount());
        assertEquals(
                BigDecimal.valueOf(35_000L),
                response.transferRefundAmount());
        assertEquals(BigDecimal.valueOf(205_000L), response.netAmount());
    }

    @Test
    void repeatedManualCashOutReturnsExistingMovementWithoutAppendingAgain() {
        CashierShift shift = openShift();
        CashMovement existing = CashMovement.builder()
                .id(73L)
                .cashierShift(shift)
                .movementType(CashMovementType.CASH_OUT)
                .direction(CashMovementDirection.OUT)
                .amount(BigDecimal.valueOf(50_000L))
                .sourceType(CashMovementSourceType.MANUAL)
                .sourceId("11:cash-out-key")
                .createdBy(staff)
                .createdByName(staff.getFullName())
                .createdByRole(UserType.STAFF.name())
                .reason("Chi mua vật tư lễ tân")
                .occurredAtUtc(NOW)
                .build();
        when(shiftRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(shift));
        when(movementRepository.findBySourceTypeAndSourceIdAndMovementType(
                CashMovementSourceType.MANUAL,
                "11:cash-out-key",
                CashMovementType.CASH_OUT))
                .thenReturn(Optional.of(existing));
        CashMovementRequest request = new CashMovementRequest();
        request.setAmount(BigDecimal.valueOf(50_000L));
        request.setReason("Chi mua vật tư lễ tân");

        var response = service.addCashOut(11L, request, "cash-out-key", staff);

        assertEquals(73L, response.id());
        verify(movementRepository, never()).saveAndFlush(any());
        verify(auditService, never()).recordTargetForUser(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void closeUsesAutomaticallyCalculatedAmountAndIgnoresLegacyCountFields() {
        CashierShift shift = openShift();
        when(shiftRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(shift));
        when(movementRepository.calculateExpectedCash(11L))
                .thenReturn(BigDecimal.valueOf(620_000L));
        when(shiftRepository.saveAndFlush(any(CashierShift.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(movementRepository.findAllByCashierShiftIdOrderByOccurredAtUtcAscIdAsc(11L))
                .thenReturn(List.of());
        CloseCashierShiftRequest request = new CloseCashierShiftRequest();
        request.setCountedCashAmount(BigDecimal.valueOf(610_000L));
        request.setVarianceReason("Giá trị legacy không còn được sử dụng");
        request.setNote("Kết thúc ca sáng");

        var response = service.close(11L, request, staff);

        assertEquals(CashierShiftStatus.CLOSED, response.status());
        assertEquals(BigDecimal.valueOf(620_000L), response.countedCashAmount());
        assertEquals(BigDecimal.ZERO, response.varianceAmount());
        assertEquals("Kết thúc ca sáng", response.closeNote());
        verify(auditService).recordTargetForUser(
                eq(staff), eq("CASHIER_SHIFT"), eq("11"),
                eq(ReservationAuditAction.CASHIER_SHIFT_CLOSED), any(), any());
    }

    private CashierShift openShift() {
        return CashierShift.builder()
                .id(11L)
                .version(0L)
                .shiftCode("CS-20260728-7-ABCDEFGH")
                .businessDate(LocalDate.of(2026, 7, 28))
                .status(CashierShiftStatus.OPEN)
                .openedBy(staff)
                .openedByName(staff.getFullName())
                .openedByRole(UserType.STAFF.name())
                .openedAtUtc(NOW.minusSeconds(3600))
                .openingCashAmount(BigDecimal.ZERO)
                .build();
    }

    private PaymentTransaction cashPayment(Long reservationId, long amount) {
        Reservation reservation = Reservation.builder()
                .reservationCode("RES-CASH-TEST")
                .status(ReservationStatus.CHECKED_IN)
                .build();
        reservation.setId(reservationId);
        return PaymentTransaction.builder()
                .id("payment-cash-1")
                .reservation(reservation)
                .txnRef("CASH-TEST")
                .provider(PaymentProvider.CASH)
                .status(PaymentStatus.SUCCESS)
                .amount(amount)
                .receivedAmount(amount)
                .acceptedAmount(amount)
                .currency("VND")
                .build();
    }
}
