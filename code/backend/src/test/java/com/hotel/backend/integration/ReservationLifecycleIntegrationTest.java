package com.hotel.backend.integration;

import com.hotel.backend.constant.*;
import com.hotel.backend.dto.request.*;
import com.hotel.backend.dto.response.ReservationInvoiceResponse;
import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.Room;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.*;
import com.hotel.backend.service.PaymentService;
import com.hotel.backend.service.PaymentRefundService;
import com.hotel.backend.service.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ReservationLifecycleIntegrationTest {

    @Autowired ReservationService reservationService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentRefundService paymentRefundService;
    @Autowired RoomTypeRepository roomTypeRepository;
    @Autowired RoomRepository roomRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired PaymentTransactionRepository paymentTransactionRepository;
    @Autowired PaymentRefundRepository paymentRefundRepository;
    @Autowired ReservationInvoiceRepository reservationInvoiceRepository;
    @Autowired ReservationRoomRepository reservationRoomRepository;
    @Autowired RoomHoldRepository roomHoldRepository;
    @Autowired ReservationAuditLogRepository auditLogRepository;
    @Autowired UserRepository userRepository;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void depositConfirmMultiRoomCheckInFeeFinalPaymentCheckoutAndInvoice() {
        RoomType roomType = roomTypeRepository.save(RoomType.builder()
                .typeName("Integration Family")
                .description("Integration test room")
                .price(BigDecimal.valueOf(100_000))
                .maxGuests(2)
                .build());
        Room room101 = roomRepository.save(readyRoom("IT-101", roomType));
        Room room102 = roomRepository.save(readyRoom("IT-102", roomType));

        LocalDateTime checkIn = LocalDateTime.now().plusMinutes(30);
        LocalDateTime checkOut = checkIn.plusHours(2);
        CreateReservationRequest createRequest = CreateReservationRequest.builder()
                .customer(CustomerProfileRequest.builder()
                        .fullName("Khách integration")
                        .phone("0900000001")
                        .email("integration@example.com")
                        .build())
                .checkIn(checkIn)
                .checkOut(checkOut)
                .guestCount(2)
                .roomTypes(List.of(RoomTypeItemRequest.builder()
                        .roomTypeId(roomType.getId()).quantity(2).build()))
                .build();

        User customer = userRepository.save(User.builder().fullName("Khách integration")
                .username("integration-customer").email("integration@example.com")
                .phone("0900000001").password("test")
                .type(UserType.CUSTOMER).status(UserStatus.ACTIVE).build());
        ReservationResponse created = reservationService.createReservation(customer, createRequest);
        assertEquals(ReservationStatus.PAYMENT_PENDING, created.getStatus());
        assertTrue(reservationService.getMyReservations(customer).isEmpty(),
                "PAYMENT_PENDING must not appear as a booking in My Booking");

        var reservation = reservationRepository.findById(created.getId()).orElseThrow();
        reservationService.activatePaymentHolds(reservation.getId(), LocalDateTime.now().plusMinutes(5));
        long deposit = reservation.getTotalAmount().multiply(BigDecimal.valueOf(0.5)).longValue();
        paymentTransactionRepository.save(PaymentTransaction.builder()
                .reservation(reservation)
                .txnRef("IT-DEPOSIT-" + reservation.getId())
                .provider(PaymentProvider.SEPAY)
                .purpose(PaymentPurpose.DEPOSIT)
                .status(PaymentStatus.SUCCESS)
                .amount(deposit)
                .currency("VND")
                .paidAt(LocalDateTime.now())
                .build());

        reservationService.convertHoldsAfterPayment(reservation.getId());
        assertEquals(1, reservationService.getMyReservations(customer).size());
        assertEquals(ReservationStatus.CONFIRMED,
                reservationService.confirmReservation(reservation.getId()).getStatus());

        List<AssignRoomRequest> assignments = List.of(
                assignment(room101.getId(), "Khách chính"),
                assignment(room102.getId(), "Khách đi cùng"));
        assertEquals(ReservationStatus.CHECKED_IN,
                reservationService.checkIn(reservation.getId(), assignments).getStatus());

        CheckoutRefundRequest feeRequest = new CheckoutRefundRequest();
        feeRequest.setAdditionalFee(5_000L);
        feeRequest.setReasonCode("INTEGRATION_TEST");
        feeRequest.setReason("Kiểm tra vòng đời checkout");
        reservationService.updateCheckoutAdditionalFee(reservation.getId(), feeRequest);

        User staff = User.builder().fullName("Nhân viên integration")
                .username("integration-staff").email("integration-staff@example.com")
                .phone("0900000002").type(UserType.STAFF).status(UserStatus.ACTIVE).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(staff, null, staff.getAuthorities()));

        var settlement = reservationService.calculateFinalPayment(reservation.getId(), staff);
        assertTrue(settlement.getRemainingAmount() > 0,
                () -> "Expected remaining payment, settlement=" + settlement);

        PaymentRequest finalPayment = new PaymentRequest();
        finalPayment.setBookingId(reservation.getId());
        finalPayment.setProvider(PaymentProvider.CASH);
        finalPayment.setPurpose(PaymentPurpose.FINAL_PAYMENT);
        assertEquals(PaymentStatus.SUCCESS,
                paymentService.createCashPayment(finalPayment, new MockHttpServletRequest(), staff).getStatus());

        ReservationResponse checkedOut = reservationService.checkOut(reservation.getId());
        assertEquals(ReservationStatus.CHECKED_OUT, checkedOut.getStatus());

        long auditCountBeforeInvoice = auditLogRepository
                .findByReservationIdOrderByCreatedAtDesc(reservation.getId()).size();
        ReservationInvoiceResponse invoice = reservationService.getInvoice(reservation.getId(), staff);
        assertEquals(reservation.getReservationCode(), invoice.getReservationCode());
        assertEquals(2, invoice.getGuestCount());
        assertEquals(1, invoice.getRoomTypes().size());
        assertEquals(2, invoice.getRoomTypes().get(0).getQuantity());
        assertNotNull(invoice.getIssuedAtUtc());
        assertEquals(invoice.getRoomCharge(), invoice.getActualRoomCharge());
        assertEquals(invoice.getRefundedAmount(), invoice.getCompletedRefundAmount());
        assertEquals(invoice.getBalanceAmount(), invoice.getRemainingAmount());
        long auditCountAfterInvoice = auditLogRepository
                .findByReservationIdOrderByCreatedAtDesc(reservation.getId()).size();
        assertEquals(auditCountBeforeInvoice, auditCountAfterInvoice,
                "Mở hoặc in hóa đơn không được tạo thêm audit log vận hành");
        assertTrue(auditCountAfterInvoice >= 5);
    }

    @Test
    void oneReservationCanCheckInMultipleRoomTypesAndQuantities() {
        String suffix = shortSuffix();
        RoomType standard = roomTypeRepository.save(RoomType.builder()
                .typeName("Standard multi " + suffix)
                .description("Multi type standard")
                .price(BigDecimal.valueOf(100_000))
                .maxGuests(2)
                .build());
        RoomType suite = roomTypeRepository.save(RoomType.builder()
                .typeName("Suite multi " + suffix)
                .description("Multi type suite")
                .price(BigDecimal.valueOf(180_000))
                .maxGuests(3)
                .build());
        Room standardOne = roomRepository.save(readyRoom("MULTI-S-1-" + suffix, standard));
        Room standardTwo = roomRepository.save(readyRoom("MULTI-S-2-" + suffix, standard));
        Room suiteOne = roomRepository.save(readyRoom("MULTI-U-1-" + suffix, suite));
        User customer = customer("multi-type-" + suffix);
        LocalDateTime checkIn = LocalDateTime.now().plusMinutes(30);
        ReservationResponse created = reservationService.createReservation(customer,
                CreateReservationRequest.builder()
                        .customer(CustomerProfileRequest.builder()
                                .fullName(customer.getFullName())
                                .phone(customer.getPhone())
                                .email(customer.getEmail())
                                .build())
                        .checkIn(checkIn)
                        .checkOut(checkIn.plusHours(2))
                        .guestCount(3)
                        .roomTypes(List.of(
                                RoomTypeItemRequest.builder()
                                        .roomTypeId(standard.getId()).quantity(2).build(),
                                RoomTypeItemRequest.builder()
                                        .roomTypeId(suite.getId()).quantity(1).build()))
                        .build());
        Reservation reservation = reservationRepository.findById(created.getId()).orElseThrow();
        reservationService.activatePaymentHolds(
                reservation.getId(), LocalDateTime.now().plusMinutes(5));
        successfulPayment(reservation.getId(),
                reservation.getTotalAmount().multiply(BigDecimal.valueOf(0.5)).longValue(),
                PaymentPurpose.DEPOSIT, "MULTI-DEPOSIT-" + suffix);
        reservationService.convertHoldsAfterPayment(reservation.getId());
        reservationService.confirmReservation(reservation.getId());

        ReservationResponse checkedIn = reservationService.checkIn(reservation.getId(), List.of(
                assignment(standardOne.getId(), "Standard guest 1"),
                assignment(standardTwo.getId(), "Standard guest 2"),
                assignment(suiteOne.getId(), "Suite guest")));
        ReservationResponse checkedInDetail = reservationService.getReservation(
                reservation.getId(), customer, null);

        assertEquals(ReservationStatus.CHECKED_IN, checkedIn.getStatus());
        assertEquals(3, reservationRoomRepository.findAllByReservationId(reservation.getId()).stream()
                .filter(room -> room.getStatus() == AssignStatus.CHECKED_IN)
                .count());
        assertEquals(3, checkedInDetail.getRoomTypes().stream()
                .mapToInt(item -> item.getQuantity()).sum());
        assertEquals(2, checkedInDetail.getRoomTypes().size());
    }

    @Test
    void concurrentCheckoutFeeUpdatesAreSerializedWithoutLostTotals() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        RoomType roomType = roomTypeRepository.save(RoomType.builder()
                .typeName("Concurrent " + suffix)
                .description("Concurrent checkout test")
                .price(BigDecimal.valueOf(100_000))
                .maxGuests(2)
                .build());
        Room room = roomRepository.save(readyRoom("LOCK-" + suffix, roomType));
        User customer = userRepository.save(User.builder()
                .fullName("Concurrent customer")
                .username("concurrent-" + suffix)
                .email("concurrent-" + suffix + "@example.com")
                .phone("091" + Math.abs(suffix.hashCode() % 10_000_000))
                .password("test")
                .type(UserType.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .build());
        LocalDateTime checkIn = LocalDateTime.now().plusMinutes(30);
        ReservationResponse created = reservationService.createReservation(customer,
                CreateReservationRequest.builder()
                        .customer(CustomerProfileRequest.builder()
                                .fullName(customer.getFullName())
                                .phone(customer.getPhone())
                                .email(customer.getEmail())
                                .build())
                        .checkIn(checkIn)
                        .checkOut(checkIn.plusHours(2))
                        .guestCount(1)
                        .roomTypes(List.of(RoomTypeItemRequest.builder()
                                .roomTypeId(roomType.getId()).quantity(1).build()))
                        .build());
        var reservation = reservationRepository.findById(created.getId()).orElseThrow();
        reservationService.activatePaymentHolds(reservation.getId(), LocalDateTime.now().plusMinutes(5));
        paymentTransactionRepository.save(PaymentTransaction.builder()
                .reservation(reservation)
                .txnRef("LOCK-DEPOSIT-" + suffix)
                .provider(PaymentProvider.SEPAY)
                .purpose(PaymentPurpose.DEPOSIT)
                .status(PaymentStatus.SUCCESS)
                .amount(reservation.getTotalAmount().multiply(BigDecimal.valueOf(0.5)).longValue())
                .currency("VND")
                .paidAt(LocalDateTime.now())
                .build());
        reservationService.convertHoldsAfterPayment(reservation.getId());
        reservationService.confirmReservation(reservation.getId());
        reservationService.checkIn(reservation.getId(), List.of(assignment(room.getId(), "Concurrent guest")));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = executor.submit(() -> updateFeeAfterSignal(reservation.getId(), 1_000L, ready, start));
            Future<?> second = executor.submit(() -> updateFeeAfterSignal(reservation.getId(), 2_000L, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        var updated = reservationRepository.findById(reservation.getId()).orElseThrow();
        long finalFee = updated.getCheckoutAdditionalFee().longValue();
        assertTrue(finalFee == 1_000L || finalFee == 2_000L);
        assertEquals(100_000L, updated.getTotalAmount().longValue() - finalFee,
                "Concurrent replacement fees must not be added twice or lose the room total");
    }

    @Test
    void concurrentLastRoomDepositQrCreatesOnlyOneActiveHold() throws Exception {
        String suffix = shortSuffix();
        RoomType roomType = roomTypeRepository.save(RoomType.builder()
                .typeName("Last room " + suffix)
                .description("Concurrent hold test")
                .price(BigDecimal.valueOf(100_000))
                .maxGuests(2)
                .build());
        roomRepository.save(readyRoom("LAST-" + suffix, roomType));
        LocalDateTime checkIn = LocalDateTime.now().plusMinutes(30);
        LocalDateTime checkOut = checkIn.plusHours(2);
        Reservation first = pendingReservation(roomType, "hold-a-" + suffix, checkIn, checkOut);
        Reservation second = pendingReservation(roomType, "hold-b-" + suffix, checkIn, checkOut);

        ConcurrentResult result = runConcurrently(
                () -> reservationService.activatePaymentHolds(
                        first.getId(), LocalDateTime.now().plusMinutes(5)),
                () -> reservationService.activatePaymentHolds(
                        second.getId(), LocalDateTime.now().plusMinutes(5)));

        assertEquals(1, result.successes());
        assertEquals(1, result.failures().size());
        long activeHolds = List.of(first.getId(), second.getId()).stream()
                .flatMap(id -> roomHoldRepository.findByReservationId(id).stream())
                .filter(hold -> hold.getStatus() == HoldStatus.ACTIVE)
                .count();
        assertEquals(1L, activeHolds,
                "The final sellable room must be held by exactly one deposit QR");
    }

    @Test
    void concurrentCheckInCannotAssignSamePhysicalRoomToTwoReservations() throws Exception {
        String suffix = shortSuffix();
        RoomType roomType = roomTypeRepository.save(RoomType.builder()
                .typeName("Room assignment " + suffix)
                .description("Concurrent room assignment test")
                .price(BigDecimal.valueOf(100_000))
                .maxGuests(2)
                .build());
        Room contested = roomRepository.save(readyRoom("CONTESTED-" + suffix, roomType));
        roomRepository.save(readyRoom("SPARE-" + suffix, roomType));
        Reservation first = confirmedReservation(roomType, "assign-a-" + suffix);
        Reservation second = confirmedReservation(roomType, "assign-b-" + suffix);

        ConcurrentResult result = runConcurrently(
                () -> reservationService.checkIn(first.getId(),
                        List.of(assignment(contested.getId(), "Guest A"))),
                () -> reservationService.checkIn(second.getId(),
                        List.of(assignment(contested.getId(), "Guest B"))));

        assertEquals(1, result.successes());
        assertEquals(1, result.failures().size());
        long assigned = reservationRoomRepository.findByStatus(AssignStatus.CHECKED_IN).stream()
                .filter(item -> item.getRoom() != null
                        && contested.getId().equals(item.getRoom().getId()))
                .count();
        assertEquals(1L, assigned,
                "A physical room must belong to only one active stay");
    }

    @Test
    void concurrentOverpaymentRefundCreatesOneLedgerObligation() throws Exception {
        String suffix = shortSuffix();
        RoomType roomType = roomTypeRepository.save(RoomType.builder()
                .typeName("Refund race " + suffix)
                .description("Concurrent refund test")
                .price(BigDecimal.valueOf(100_000))
                .maxGuests(2)
                .build());
        Room room = roomRepository.save(readyRoom("REFUND-" + suffix, roomType));
        Reservation reservation = confirmedReservation(roomType, "refund-" + suffix);
        reservationService.checkIn(reservation.getId(),
                List.of(assignment(room.getId(), "Refund guest")));
        User staff = staff("refund-staff-" + suffix);
        long remaining = reservationService.calculateFinalPayment(
                reservation.getId(), staff).getRemainingAmount();
        successfulPayment(reservation.getId(), Math.max(0L, remaining) + 20_000L,
                PaymentPurpose.FINAL_PAYMENT, "OVERPAY-" + suffix);
        ReservationRefundRequest request = new ReservationRefundRequest();
        request.setRefundChannel(RefundChannel.CASH_AT_COUNTER);

        ConcurrentResult result = runConcurrently(
                () -> reservationService.requestCheckoutRefund(reservation.getId(), request),
                () -> reservationService.requestCheckoutRefund(reservation.getId(), request));

        assertEquals(2, result.successes(),
                "The duplicate command should replay the existing obligation safely");
        assertTrue(result.failures().isEmpty());
        assertEquals(1, paymentRefundRepository.findByReservationId(reservation.getId()).size(),
                "Concurrent overpayment processing must create one refund ledger row");
    }

    @Test
    void concurrentCashRefundCompletionHasOneLedgerEffect() throws Exception {
        String suffix = shortSuffix();
        RoomType roomType = roomTypeRepository.save(RoomType.builder()
                .typeName("Refund completion " + suffix)
                .description("Concurrent refund completion test")
                .price(BigDecimal.valueOf(100_000))
                .maxGuests(2)
                .build());
        Room room = roomRepository.save(readyRoom("REFUND-DONE-" + suffix, roomType));
        Reservation reservation = confirmedReservation(roomType, "refund-done-" + suffix);
        reservationService.checkIn(reservation.getId(),
                List.of(assignment(room.getId(), "Refund completion guest")));
        User staff = staff("refund-complete-staff-" + suffix);
        long remaining = reservationService.calculateFinalPayment(
                reservation.getId(), staff).getRemainingAmount();
        successfulPayment(reservation.getId(), Math.max(0L, remaining) + 20_000L,
                PaymentPurpose.FINAL_PAYMENT, "CASH-OVERPAY-" + suffix);
        ReservationRefundRequest refundRequest = new ReservationRefundRequest();
        refundRequest.setRefundChannel(RefundChannel.CASH_AT_COUNTER);
        reservationService.requestCheckoutRefund(reservation.getId(), refundRequest);
        var refund = paymentRefundRepository.findByReservationId(reservation.getId()).stream()
                .findFirst().orElseThrow();

        CashRefundCompleteRequest completeRequest = new CashRefundCompleteRequest();
        completeRequest.setConfirmed(true);
        completeRequest.setRefundedAt(LocalDateTime.now());
        ConcurrentResult result = runConcurrently(
                () -> paymentRefundService.completeCashAtCounter(
                        refund.getId(), completeRequest, staff),
                () -> paymentRefundService.completeCashAtCounter(
                        refund.getId(), completeRequest, staff));

        assertEquals(2, result.successes(),
                () -> "The second completion must replay the completed ledger row safely; failures="
                        + result.failures());
        assertTrue(result.failures().isEmpty());
        var completed = paymentRefundRepository.findById(refund.getId()).orElseThrow();
        assertEquals(RefundStatus.SUCCEEDED, completed.getStatus());
        assertEquals(completed.getRequestedAmount(), completed.getActualRefundAmount());
        assertEquals(1, paymentRefundRepository.findByReservationId(
                reservation.getId()).size());
    }

    @Test
    void concurrentCheckoutCreatesOneInvoiceAndOneTerminalTransition() throws Exception {
        String suffix = shortSuffix();
        RoomType roomType = roomTypeRepository.save(RoomType.builder()
                .typeName("Checkout race " + suffix)
                .description("Concurrent checkout test")
                .price(BigDecimal.valueOf(100_000))
                .maxGuests(2)
                .build());
        Room room = roomRepository.save(readyRoom("CHECKOUT-" + suffix, roomType));
        Reservation reservation = confirmedReservation(roomType, "checkout-" + suffix);
        reservationService.checkIn(reservation.getId(),
                List.of(assignment(room.getId(), "Checkout guest")));
        User staff = staff("checkout-staff-" + suffix);
        long remaining = reservationService.calculateFinalPayment(
                reservation.getId(), staff).getRemainingAmount();
        if (remaining > 0L) {
            successfulPayment(reservation.getId(), remaining,
                    PaymentPurpose.FINAL_PAYMENT, "SETTLE-" + suffix);
        }

        ConcurrentResult result = runConcurrently(
                () -> reservationService.checkOut(reservation.getId()),
                () -> reservationService.checkOut(reservation.getId()));

        assertEquals(1, result.successes());
        assertEquals(1, result.failures().size());
        assertEquals(ReservationStatus.CHECKED_OUT,
                reservationRepository.findById(reservation.getId()).orElseThrow().getStatus());
        assertTrue(reservationInvoiceRepository.findByReservationId(
                reservation.getId()).isPresent());
        long checkoutAudits = auditLogRepository
                .findByReservationIdOrderByCreatedAtDesc(reservation.getId()).stream()
                .filter(log -> log.getAction() == ReservationAuditAction.CHECK_OUT)
                .count();
        assertEquals(1L, checkoutAudits,
                "Concurrent checkout must create one terminal audit/invoice snapshot");
    }

    private void updateFeeAfterSignal(
            Long reservationId, long fee, CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start concurrent fee update");
            }
            CheckoutRefundRequest request = new CheckoutRefundRequest();
            request.setAdditionalFee(fee);
            request.setReasonCode("CONCURRENCY_TEST");
            request.setReason("Kiểm tra cập nhật phụ phí đồng thời");
            reservationService.updateCheckoutAdditionalFee(reservationId, request);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Reservation pendingReservation(
            RoomType roomType, String suffix,
            LocalDateTime checkIn, LocalDateTime checkOut) {
        User customer = customer(suffix);
        ReservationResponse response = reservationService.createReservation(customer,
                CreateReservationRequest.builder()
                        .customer(CustomerProfileRequest.builder()
                                .fullName(customer.getFullName())
                                .phone(customer.getPhone())
                                .email(customer.getEmail())
                                .build())
                        .checkIn(checkIn)
                        .checkOut(checkOut)
                        .guestCount(1)
                        .roomTypes(List.of(RoomTypeItemRequest.builder()
                                .roomTypeId(roomType.getId()).quantity(1).build()))
                        .build());
        return reservationRepository.findById(response.getId()).orElseThrow();
    }

    private Reservation confirmedReservation(RoomType roomType, String suffix) {
        LocalDateTime checkIn = LocalDateTime.now().plusMinutes(30);
        Reservation reservation = pendingReservation(
                roomType, suffix, checkIn, checkIn.plusHours(2));
        reservationService.activatePaymentHolds(
                reservation.getId(), LocalDateTime.now().plusMinutes(5));
        successfulPayment(
                reservation.getId(),
                reservation.getTotalAmount().multiply(BigDecimal.valueOf(0.5)).longValue(),
                PaymentPurpose.DEPOSIT,
                "DEPOSIT-" + suffix);
        reservationService.convertHoldsAfterPayment(reservation.getId());
        reservationService.confirmReservation(reservation.getId());
        return reservationRepository.findById(reservation.getId()).orElseThrow();
    }

    private PaymentTransaction successfulPayment(
            Long reservationId, long amount, PaymentPurpose purpose, String txnRef) {
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        return paymentTransactionRepository.save(PaymentTransaction.builder()
                .reservation(reservation)
                .txnRef(txnRef)
                .provider(PaymentProvider.SEPAY)
                .purpose(purpose)
                .status(PaymentStatus.SUCCESS)
                .amount(amount)
                .expectedAmount(amount)
                .receivedAmount(amount)
                .acceptedAmount(amount)
                .currency("VND")
                .paidAt(LocalDateTime.now())
                .paidAtUtc(Instant.now())
                .build());
    }

    private User customer(String suffix) {
        String normalized = suffix.replaceAll("[^A-Za-z0-9]", "");
        String phoneSuffix = String.format("%07d",
                Math.floorMod(normalized.hashCode(), 10_000_000));
        return userRepository.save(User.builder()
                .fullName("Customer " + suffix)
                .username("customer-" + suffix)
                .email("customer-" + suffix + "@example.com")
                .phone("092" + phoneSuffix)
                .password("test")
                .type(UserType.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private User staff(String suffix) {
        return User.builder()
                .fullName("Staff " + suffix)
                .username(suffix)
                .email(suffix + "@example.com")
                .phone("093" + String.format("%07d",
                        Math.floorMod(suffix.hashCode(), 10_000_000)))
                .type(UserType.STAFF)
                .status(UserStatus.ACTIVE)
                .build();
    }

    private ConcurrentResult runConcurrently(Runnable firstAction, Runnable secondAction)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        try {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> runAfterSignal(
                    firstAction, ready, start, successes, failures)));
            futures.add(executor.submit(() -> runAfterSignal(
                    secondAction, ready, start, successes, failures)));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        return new ConcurrentResult(successes.get(), List.copyOf(failures));
    }

    private void runAfterSignal(
            Runnable action,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicInteger successes,
            ConcurrentLinkedQueue<Throwable> failures) {
        try {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent start");
            }
            action.run();
            successes.incrementAndGet();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failures.add(exception);
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    private String shortSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record ConcurrentResult(int successes, List<Throwable> failures) {}

    private Room readyRoom(String name, RoomType roomType) {
        return Room.builder().roomName(name).roomType(roomType).floor(1)
                .status(RoomStatus.AVAILABLE).cleaningStatus(CleaningStatus.CLEAN).build();
    }

    private AssignRoomRequest assignment(Long roomId, String guestName) {
        return AssignRoomRequest.builder().roomId(roomId)
                .guests(List.of(GuestRequest.builder().fullName(guestName).isPrimary(true).build()))
                .build();
    }
}
