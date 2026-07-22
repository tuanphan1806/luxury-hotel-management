package com.hotel.backend.integration;

import com.hotel.backend.constant.*;
import com.hotel.backend.dto.request.*;
import com.hotel.backend.dto.response.FinalPaymentResponse;
import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.dto.response.WalkInReservationResponse;
import com.hotel.backend.entity.*;
import com.hotel.backend.repository.*;
import com.hotel.backend.scheduled.RoomHoldExpiryScheduler;
import com.hotel.backend.service.PaymentRefundService;
import com.hotel.backend.service.PaymentService;
import com.hotel.backend.service.RefundRecipientService;
import com.hotel.backend.service.ReservationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the reservation business branches that are easy to miss when testing
 * only the HTTP happy path. Each test rolls back its own data so scenarios can
 * be run independently and in any order.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReservationScenarioIntegrationTest {

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired ReservationRoomRepository reservationRoomRepository;
    @Autowired RoomTypeRepository roomTypeRepository;
    @Autowired RoomRepository roomRepository;
    @Autowired RoomHoldRepository roomHoldRepository;
    @Autowired PaymentTransactionRepository paymentTransactionRepository;
    @Autowired PaymentRefundRepository paymentRefundRepository;
    @Autowired PaymentProviderEventRepository paymentProviderEventRepository;
    @Autowired MediaAssetRepository mediaAssetRepository;
    @Autowired GuestRepository guestRepository;
    @Autowired UserRepository userRepository;
    @Autowired CustomerProfileRepository customerProfileRepository;
    @Autowired ReservationInvoiceRepository reservationInvoiceRepository;
    @Autowired RoomHoldExpiryScheduler roomHoldExpiryScheduler;
    @Autowired PaymentRefundService paymentRefundService;
    @Autowired PaymentService paymentService;
    @Autowired RefundRecipientService refundRecipientService;
    @Autowired EntityManager entityManager;

    @Test
    void onlineReservationRejectsPastCheckIn() {
        RoomType roomType = roomType(2);
        User customer = customer();

        CreateReservationRequest request = onlineRequest(
                roomType, 1, 1,
                LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusHours(2));

        assertThrows(RuntimeException.class,
                () -> reservationService.createReservation(customer, request));
    }

    @Test
    void capacityRejectsGuestCountAboveSelectedRoomCapacity() {
        RoomType roomType = roomType(2);
        room(roomType);
        User customer = customer();

        CreateReservationRequest request = onlineRequest(
                roomType, 1, 3,
                LocalDateTime.now().plusHours(1),
                LocalDateTime.now().plusHours(3));

        assertThrows(RuntimeException.class,
                () -> reservationService.createReservation(customer, request));
    }

    @Test
    void reservationDoesNotHoldUntilQrAndFirstQrBlocksSecondQr() {
        RoomType roomType = roomType(2);
        room(roomType);
        LocalDateTime checkIn = LocalDateTime.now().plusHours(1);
        LocalDateTime checkOut = checkIn.plusHours(2);

        ReservationResponse first = reservationService.createReservation(
                customer(), onlineRequest(roomType, 1, 1, checkIn, checkOut));
        ReservationResponse second = reservationService.createReservation(
                customer(), onlineRequest(roomType, 1, 1,
                        checkIn.plusMinutes(30), checkOut.plusMinutes(30)));
        assertEquals(ReservationStatus.PAYMENT_PENDING, first.getStatus());
        assertNull(holdOfOrNull(first.getId()),
                "Nhập xong thông tin nhưng chưa mở QR thì không được giữ phòng");
        assertNull(holdOfOrNull(second.getId()));

        reservationService.activatePaymentHolds(first.getId(), LocalDateTime.now().plusMinutes(5));
        assertEquals(HoldStatus.ACTIVE, holdOf(first.getId()).getStatus());
        assertThrows(RuntimeException.class, () -> reservationService.activatePaymentHolds(
                second.getId(), LocalDateTime.now().plusMinutes(5)));
    }

    @Test
    void qrHoldUsesFiveMinutesAndAbandonReleasesImmediately() {
        RoomType roomType = roomType(2);
        room(roomType);
        User customer = customer();
        ReservationResponse created = createOnline(customer, roomType, 1, 1);
        assertNull(holdOfOrNull(created.getId()));

        PaymentRequest request = new PaymentRequest();
        request.setBookingId(created.getId());
        request.setProvider(PaymentProvider.SEPAY);
        request.setPurpose(PaymentPurpose.DEPOSIT);
        LocalDateTime beforeQr = LocalDateTime.now();
        var payment = paymentService.createPayment(
                request, new org.springframework.mock.web.MockHttpServletRequest(), customer, null);
        reloadPersistenceContext();

        RoomHold activeHold = holdOf(created.getId());
        assertEquals(HoldStatus.ACTIVE, activeHold.getStatus());
        assertFalse(activeHold.getExpiresAt().isBefore(beforeQr.plusMinutes(4).plusSeconds(50)));
        assertFalse(activeHold.getExpiresAt().isAfter(beforeQr.plusMinutes(5).plusSeconds(10)));

        paymentService.abandonPendingPayment(payment.getTransactionId());
        reloadPersistenceContext();

        assertEquals(HoldStatus.RELEASED, holdOf(created.getId()).getStatus());
        assertEquals(PaymentStatus.CANCELLED,
                paymentTransactionRepository.findById(payment.getTransactionId()).orElseThrow().getStatus());
        assertEquals(ReservationStatus.CANCELLED,
                reservationRepository.findById(created.getId()).orElseThrow().getStatus());
    }

    /**
     * Khách được chọn cọc 50% hoặc trả trước 100%; backend phải snapshot đúng
     * số tiền cần thu để QR và callback không tự tính lại theo dữ liệu frontend.
     */
    @Test
    void onlinePaymentPlanSnapshotsFiftyPercentOrFullTotal() {
        RoomType roomType = roomType(2);
        room(roomType);
        LocalDateTime checkIn = LocalDateTime.now().plusHours(1);
        LocalDateTime checkOut = checkIn.plusHours(2);

        CreateReservationRequest depositRequest = onlineRequest(
                roomType, 1, 1, checkIn, checkOut);
        ReservationResponse deposit = reservationService.createReservation(customer(), depositRequest);
        assertEquals(PaymentPlan.DEPOSIT_50, deposit.getPaymentPlan());
        assertEquals(deposit.getTotalAmount().multiply(BigDecimal.valueOf(0.5))
                        .setScale(0, RoundingMode.CEILING),
                deposit.getRequiredInitialPayment().setScale(0, RoundingMode.CEILING));

        CreateReservationRequest fullRequest = onlineRequest(
                roomType, 1, 1, checkIn.plusMinutes(5), checkOut.plusMinutes(5));
        fullRequest.setPaymentPlan(PaymentPlan.PREPAY_100);
        ReservationResponse full = reservationService.createReservation(customer(), fullRequest);
        assertEquals(PaymentPlan.PREPAY_100, full.getPaymentPlan());
        assertEquals(full.getTotalAmount().setScale(0, RoundingMode.CEILING),
                full.getRequiredInitialPayment().setScale(0, RoundingMode.CEILING));
    }

    @Test
    void confirmRequiresACompletedDeposit() {
        RoomType roomType = roomType(2);
        room(roomType);
        ReservationResponse created = createOnline(customer(), roomType, 1, 1);

        assertEquals(ReservationStatus.PAYMENT_PENDING, created.getStatus());
        assertThrows(RuntimeException.class,
                () -> reservationService.confirmReservation(created.getId()));
    }

    @Test
    void successfulDepositConvertsHoldAndAllowsConfirmation() {
        RoomType roomType = roomType(2);
        room(roomType);
        User customer = customer();
        ReservationResponse created = createOnline(customer, roomType, 1, 1);
        Reservation reservation = reservationRepository.findById(created.getId()).orElseThrow();
        successfulPayment(reservation, depositAmount(reservation), PaymentPurpose.DEPOSIT);

        reloadPersistenceContext();
        reservationService.convertHoldsAfterPayment(reservation.getId());
        reloadPersistenceContext();
        ReservationResponse confirmed = reservationService.confirmReservation(reservation.getId());
        reloadPersistenceContext();

        assertEquals(ReservationStatus.CONFIRMED, confirmed.getStatus());
        assertEquals(1, reservationService.getMyReservations(customer).size());
        assertEquals(HoldStatus.CONVERTED, holdOf(reservation.getId()).getStatus());
    }

    @Test
    void draftUpdateRejectsGuestCountAboveBookedCapacity() {
        RoomType roomType = roomType(2);
        room(roomType);
        User customer = customer();
        ReservationResponse created = createOnline(customer, roomType, 1, 1);
        Reservation reservation = reservationRepository.findById(created.getId()).orElseThrow();
        successfulPayment(reservation, depositAmount(reservation), PaymentPurpose.DEPOSIT);
        reloadPersistenceContext();
        reservationService.convertHoldsAfterPayment(reservation.getId());
        reloadPersistenceContext();

        UpdateReservationRequest update = UpdateReservationRequest.builder()
                .guestCount(3)
                .note("Không được vượt capacity")
                .build();

        assertThrows(RuntimeException.class,
                () -> reservationService.updateReservation(reservation.getId(), update, customer));
    }

    @Test
    void cancellationRequestCanBeRejectedThenApprovedWithRefund() {
        RoomType roomType = roomType(2);
        room(roomType);
        User customer = customer();
        Reservation reservation = confirmedReservation(customer, roomType, 1, 1);
        long paid = depositAmount(reservation);

        ReservationResponse pending = reservationService.cancelReservation(
                reservation.getId(), cancellation("Khách đổi lịch", false), customer, null);
        assertEquals(ReservationStatus.CANCELLATION_PENDING, pending.getStatus());

        assertEquals(ReservationStatus.CONFIRMED,
                reservationService.rejectCancellation(reservation.getId()).getStatus());

        reservationService.cancelReservation(
                reservation.getId(), cancellation("Khách không thể đến", false), customer, null);
        ReservationResponse refundPending = reservationService.approveCancellation(
                reservation.getId(), cancellation("Chấp nhận hoàn tiền", true));
        reloadPersistenceContext();

        assertEquals(ReservationStatus.CANCELLATION_PENDING, refundPending.getStatus());
        assertTrue(Boolean.TRUE.equals(refundPending.getCancellationRefundPending()));
        PaymentRefund refund = paymentRefundRepository.findByReservationId(reservation.getId())
                .stream().findFirst().orElseThrow();
        assertEquals(RefundChannel.MANUAL_BANK_TRANSFER, refund.getChannel());
        assertEquals(RefundStatus.REQUESTED, refund.getStatus());
        assertEquals(paid, refund.getAmount());
        assertEquals(HoldStatus.CONVERTED, holdOf(reservation.getId()).getStatus());

        User refundStaff = userRepository.save(staff());
        String cancellationProofSuffix = suffix();
        MediaAsset proof = mediaAssetRepository.save(MediaAsset.builder()
                .url("https://cdn.example.test/refund_proofs/cancel-"
                        + cancellationProofSuffix + ".webp")
                .objectKey("refund_proofs/cancel-" + cancellationProofSuffix + ".webp")
                .purpose(UploadFolder.REFUND_PROOFS)
                .status(MediaAssetStatus.TEMPORARY)
                .contentType("image/webp")
                .fileSize(24_000L)
                .width(1200)
                .height(900)
                .uploadedByUserId(refundStaff.getId())
                .build());
        paymentRefundService.attachManualTransferProof(refund.getId(), proof.getId(), refundStaff);
        reloadPersistenceContext();
        assertEquals(ReservationStatus.CANCELLATION_PENDING,
                reservationRepository.findById(reservation.getId()).orElseThrow().getStatus(),
                "Upload proof alone must not finalize the reservation");

        var details = paymentRefundService.getManualDetails(refund.getId());
        refund = paymentRefundRepository.findById(refund.getId()).orElseThrow();
        refund.setManualFallbackAvailableAtUtc(Instant.now().minusSeconds(1));
        paymentRefundRepository.save(refund);
        reloadPersistenceContext();
        ManualRefundCompleteRequest completion = new ManualRefundCompleteRequest();
        completion.setRecipientId(details.getRecipientId());
        completion.setRecipientVersion(details.getRecipientVersion());
        completion.setTransferredAt(LocalDateTime.now());
        completion.setFallbackReason("SePay không gửi webhook sau thời gian chờ");
        paymentRefundService.completeManualTransfer(refund.getId(), completion, refundStaff);
        reloadPersistenceContext();

        assertEquals(ReservationStatus.CANCELLED,
                reservationRepository.findById(reservation.getId()).orElseThrow().getStatus());
        assertEquals(HoldStatus.RELEASED, holdOf(reservation.getId()).getStatus());
    }

    @Test
    void staffQrCancellationKeepsPreviousStatusUntilSePayOutgoingIsConfirmed() {
        RoomType roomType = roomType(2);
        room(roomType);
        User customer = customer();
        Reservation reservation = confirmedReservation(customer, roomType, 1, 1);

        ReservationResponse pending = reservationService.cancelByStaff(
                reservation.getId(), cancellation("Staff hủy và hoàn QR", true));
        reloadPersistenceContext();

        assertEquals(ReservationStatus.CONFIRMED, pending.getStatus());
        assertTrue(Boolean.TRUE.equals(pending.getCancellationRefundPending()));
        assertEquals(HoldStatus.CONVERTED, holdOf(reservation.getId()).getStatus());

        PaymentRefund awaitingCustomer = paymentRefundRepository.findByReservationId(reservation.getId()).stream()
                .filter(row -> row.getStatus() == RefundStatus.AWAITING_CUSTOMER_INFO)
                .findFirst().orElseThrow();
        var queuedBeforeRecipient = paymentRefundService.getOperationalQueue().stream()
                .filter(row -> row.getRefundId().equals(awaitingCustomer.getId()))
                .findFirst().orElseThrow();
        assertTrue(queuedBeforeRecipient.isRecipientRequired());
        assertFalse(queuedBeforeRecipient.isCanCompleteManually());
        assertThrows(RuntimeException.class,
                () -> paymentRefundService.getManualDetails(awaitingCustomer.getId()),
                "Staff must not access QR or unmasked payout details before the customer submits a recipient");

        RefundRecipientRequest recipient = new RefundRecipientRequest();
        recipient.setBankCode("CTG");
        recipient.setBankName("VietinBank");
        recipient.setAccountNumber("012345678901");
        recipient.setAccountHolderName("CUSTOMER TEST");
        refundRecipientService.submit(reservation.getId(), recipient, customer, null);
        reloadPersistenceContext();

        PaymentRefund refund = paymentRefundRepository.findByReservationId(reservation.getId()).stream()
                .filter(row -> row.getStatus() == RefundStatus.REQUESTED)
                .findFirst().orElseThrow();

        assertEquals(ReservationStatus.CONFIRMED,
                reservationRepository.findById(reservation.getId()).orElseThrow().getStatus());

        String eventSuffix = suffix();
        PaymentProviderEvent outgoing = paymentProviderEventRepository.save(
                PaymentProviderEvent.builder()
                        .provider(PaymentProvider.SEPAY)
                        .providerEventId("refund-out-" + eventSuffix)
                        .providerTxnId("bank-out-" + eventSuffix)
                        .bankReferenceCode("BANK-REF-" + eventSuffix)
                        .dedupKey("sepay:event:refund-out-" + eventSuffix)
                        .status(PaymentProviderEventStatus.RECEIVED)
                        .payloadHash("a".repeat(64))
                        .transferType("out")
                        .amount(refund.getAmount())
                        .paymentCode(refund.getRefundCode())
                        .providerOccurredAtUtc(Instant.now())
                        .receivedAtUtc(Instant.now())
                        .processingAttempts(1)
                        .build());
        paymentRefundService.completeFromSePayOutgoing(refund.getId(), outgoing, refund.getAmount());
        reloadPersistenceContext();

        PaymentRefund completed = paymentRefundRepository.findById(refund.getId()).orElseThrow();
        assertEquals(RefundStatus.SUCCEEDED, completed.getStatus());
        assertEquals(RefundCompletionMethod.SEPAY_WEBHOOK, completed.getCompletionMethod());
        assertEquals(outgoing.getId(), completed.getCompletionProviderEvent().getId());
        assertEquals(ReservationStatus.CANCELLED,
                reservationRepository.findById(reservation.getId()).orElseThrow().getStatus());
        assertEquals(HoldStatus.RELEASED, holdOf(reservation.getId()).getStatus());
    }

    @Test
    void staffCashCancellationKeepsPreviousStatusUntilCashHandoverIsConfirmed() {
        RoomType roomType = roomType(2);
        room(roomType);
        User customer = customer();
        Reservation reservation = confirmedReservation(customer, roomType, 1, 1);

        CancelReservationRequest cashCancellation = CancelReservationRequest.builder()
                .cancellationReason("Staff hủy và hoàn tiền mặt")
                .refundPayment(true)
                .refundChannel(RefundChannel.CASH_AT_COUNTER)
                .build();
        ReservationResponse pending = reservationService.cancelByStaff(
                reservation.getId(), cashCancellation);
        reloadPersistenceContext();

        assertEquals(ReservationStatus.CONFIRMED, pending.getStatus());
        assertTrue(Boolean.TRUE.equals(pending.getCancellationRefundPending()));
        assertEquals(ReservationStatus.CONFIRMED,
                reservationRepository.findById(reservation.getId()).orElseThrow().getStatus(),
                "Closing the cash confirmation modal must leave the reservation unchanged");
        assertEquals(HoldStatus.CONVERTED, holdOf(reservation.getId()).getStatus());

        PaymentRefund cashRefund = paymentRefundRepository.findByReservationId(reservation.getId()).stream()
                .filter(row -> row.getChannel() == RefundChannel.CASH_AT_COUNTER)
                .findFirst().orElseThrow();
        assertEquals(RefundStatus.REQUESTED, cashRefund.getStatus());
        assertNull(cashRefund.getProofAsset());
        assertNull(cashRefund.getManualTransferReference());
        assertNull(cashRefund.getProviderRefundTxnId());

        User operator = userRepository.save(staff());
        CashRefundCompleteRequest completion = new CashRefundCompleteRequest();
        completion.setConfirmed(true);
        paymentRefundService.completeCashAtCounter(cashRefund.getId(), completion, operator);
        reloadPersistenceContext();

        PaymentRefund completed = paymentRefundRepository.findById(cashRefund.getId()).orElseThrow();
        assertEquals(RefundStatus.SUCCEEDED, completed.getStatus());
        assertEquals(completed.getAmount(), completed.getActualRefundAmount());
        assertNull(completed.getProofAsset());
        assertNull(completed.getManualTransferReference());
        assertNull(completed.getProviderRefundTxnId());
        assertEquals(ReservationStatus.CANCELLED,
                reservationRepository.findById(reservation.getId()).orElseThrow().getStatus());
        assertEquals(HoldStatus.RELEASED, holdOf(reservation.getId()).getStatus());
    }

    @Test
    void staffCancellationDerivesRefundFromLedgerMinusEnteredPenalty() {
        RoomType roomType = roomType(2);
        room(roomType);
        User customer = customer();
        Reservation reservation = confirmedReservation(customer, roomType, 1, 1);
        long originalAmount = depositAmount(reservation);
        long penaltyAmount = Math.max(1L, originalAmount / 4L);
        long expectedRefund = originalAmount - penaltyAmount;

        CancelReservationRequest request = CancelReservationRequest.builder()
                .cancellationReason("Khách hủy sát ngày nhận phòng")
                .refundPayment(true)
                .refundChannel(RefundChannel.CASH_AT_COUNTER)
                .cancellationPenaltyAmount(penaltyAmount)
                .penaltyReasonCode("LATE_CANCELLATION")
                .penaltyNote("Áp dụng điều khoản hủy sát ngày đã công bố")
                .build();

        ReservationResponse pending = reservationService.cancelByStaff(
                reservation.getId(), request);
        reloadPersistenceContext();

        assertEquals(ReservationStatus.CONFIRMED, pending.getStatus());
        assertEquals(BigDecimal.valueOf(penaltyAmount), pending.getCancellationFee());
        assertEquals(BigDecimal.valueOf(expectedRefund), pending.getRefundableAmount());
        PaymentRefund refund = paymentRefundRepository.findByReservationId(reservation.getId())
                .stream().findFirst().orElseThrow();
        assertEquals(expectedRefund, refund.getAmount());
        assertEquals(originalAmount,
                refund.getRefundDetailJson().get("originalAmount").longValue());
        assertEquals(penaltyAmount,
                refund.getRefundDetailJson().get("penaltyAmount").longValue());
        assertEquals(expectedRefund,
                refund.getRefundDetailJson().get("refundAmount").longValue());
        assertEquals("LATE_CANCELLATION",
                refund.getRefundDetailJson().get("policyApplied").textValue());
    }

    @Test
    void cancellationRejectsPenaltyGreaterThanCanonicalPaidLedger() {
        RoomType roomType = roomType(2);
        room(roomType);
        User customer = customer();
        Reservation reservation = confirmedReservation(customer, roomType, 1, 1);
        long paid = depositAmount(reservation);
        CancelReservationRequest request = CancelReservationRequest.builder()
                .cancellationReason("Sai tiền phạt")
                .refundPayment(true)
                .refundChannel(RefundChannel.CASH_AT_COUNTER)
                .cancellationPenaltyAmount(paid + 1L)
                .penaltyReasonCode("MANUAL_POLICY_EXCEPTION")
                .penaltyNote("Không được vượt tiền đã thu")
                .build();

        assertThrows(RuntimeException.class,
                () -> reservationService.cancelByStaff(reservation.getId(), request));
        reloadPersistenceContext();
        assertTrue(paymentRefundRepository.findByReservationId(reservation.getId()).isEmpty());
        assertEquals(ReservationStatus.CONFIRMED,
                reservationRepository.findById(reservation.getId()).orElseThrow().getStatus());
    }

    @Test
    void onlineGuestCancellationRejectsCashAndKeepsQrAsTheOnlyRefundChannel() {
        RoomType roomType = roomType(2);
        room(roomType);
        User customer = customer();
        Reservation reservation = confirmedReservation(customer, roomType, 1, 1);

        CancelReservationRequest guestCancellation = cancellation("Khách đổi kế hoạch", false);
        reservationService.cancelReservation(
                reservation.getId(), guestCancellation, customer, null);

        CancelReservationRequest cashApproval = CancelReservationRequest.builder()
                .cancellationReason("Duyệt hủy và hoàn tiền mặt")
                .refundPayment(true)
                .refundChannel(RefundChannel.CASH_AT_COUNTER)
                .build();
        assertThrows(RuntimeException.class, () -> reservationService.approveCancellation(
                reservation.getId(), cashApproval));
        reloadPersistenceContext();

        assertEquals(ReservationStatus.CANCELLATION_PENDING,
                reservationRepository.findById(reservation.getId()).orElseThrow().getStatus());
        assertTrue(paymentRefundRepository.findByReservationId(reservation.getId()).isEmpty());
        assertEquals(HoldStatus.CONVERTED, holdOf(reservation.getId()).getStatus());
    }

    @Test
    void walkInUsesCurrentTimeHasNoHoldAndStoresGuestsPerRoom() {
        RoomType roomType = roomType(2);
        Room room = room(roomType);
        LocalDateTime beforeCreate = LocalDateTime.now();
        CreateWalkInReservationRequest request = CreateWalkInReservationRequest.builder()
                .customer(CustomerProfileRequest.builder()
                        .fullName("Khách walk-in " + suffix())
                        .phone(uniquePhone())
                        .email(uniqueEmail())
                        .build())
                .checkOut(LocalDateTime.now().plusHours(3))
                .guestCount(1)
                .roomTypes(List.of(RoomTypeItemRequest.builder()
                        .roomTypeId(roomType.getId()).quantity(1).build()))
                .build();

        ReservationResponse created = reservationService.createWalkInReservation(request);
        Reservation persisted = reservationRepository.findById(created.getId()).orElseThrow();

        assertEquals(ReservationStatus.CONFIRMED, persisted.getStatus());
        assertFalse(persisted.getCheckIn().isBefore(beforeCreate));
        assertNull(holdOfOrNull(persisted.getId()));

        reservationService.checkIn(persisted.getId(), List.of(assignment(room.getId(), "Khách chính")));

        assertEquals(ReservationStatus.CHECKED_IN,
                reservationRepository.findById(persisted.getId()).orElseThrow().getStatus());
        assertEquals(1, guestRepository.findAllByReservationId(persisted.getId()).size());
        assertEquals(room.getId(), reservationRoomRepository
                .findAllByReservationId(persisted.getId()).get(0).getRoom().getId());
    }

    @Test
    void atomicWalkInUnpaidCreatesAssignedStayWithoutIntermediateConfirmedState() {
        RoomType roomType = roomType(2);
        Room room = room(roomType);
        User staff = userRepository.save(staff());
        LocalDateTime before = LocalDateTime.now();

        CreateWalkInCheckedInRequest request = CreateWalkInCheckedInRequest.builder()
                .customer(CustomerProfileRequest.builder()
                        .fullName("Atomic unpaid " + suffix())
                        .phone(uniquePhone())
                        .idCardNumber("012345678901")
                        .build())
                .checkOut(LocalDateTime.now().plusHours(3))
                .guestCount(1)
                .rooms(List.of(assignment(room.getId(), "Khách atomic")))
                .paymentOption(WalkInPaymentOption.UNPAID)
                .build();

        WalkInReservationResponse response = reservationService.createWalkInCheckedIn(
                request, staff, "127.0.0.1");
        LocalDateTime afterCreate = LocalDateTime.now();
        reloadPersistenceContext();

        Reservation persisted = reservationRepository
                .findById(response.getReservation().getId()).orElseThrow();
        ReservationRoom assigned = reservationRoomRepository
                .findAllByReservationId(persisted.getId()).stream().findFirst().orElseThrow();
        assertTrue(response.isReservationCreated());
        assertEquals("NOT_REQUESTED", response.getPaymentCreationStatus());
        assertEquals(ReservationStatus.CHECKED_IN, persisted.getStatus());
        // H2/PostgreSQL may persist LocalDateTime with lower precision than the JVM
        // clock, so keep a small boundary tolerance while still proving that
        // the backend owns the walk-in timestamp.
        assertFalse(persisted.getActualCheckIn().isBefore(before.minusSeconds(1)));
        assertFalse(persisted.getActualCheckIn().isAfter(afterCreate.plusSeconds(1)));
        assertEquals(AssignStatus.CHECKED_IN, assigned.getStatus());
        assertEquals(room.getId(), assigned.getRoom().getId());
        assertEquals(RoomStatus.CHECKED_IN,
                roomRepository.findById(room.getId()).orElseThrow().getStatus());
        assertEquals(1, guestRepository.findAllByReservationId(persisted.getId()).size());
        assertTrue(paymentTransactionRepository.findByReservationId(persisted.getId()).isEmpty());
        assertNull(holdOfOrNull(persisted.getId()));
    }

    @Test
    void atomicWalkInCashPersistsAcceptedReceiptInSameAggregate() {
        RoomType roomType = roomType(2);
        Room room = room(roomType);
        User staff = userRepository.save(staff());
        long collected = 50_000L;
        CreateWalkInCheckedInRequest request = CreateWalkInCheckedInRequest.builder()
                .customer(CustomerProfileRequest.builder()
                        .fullName("Atomic cash " + suffix())
                        .phone(uniquePhone())
                        .idCardNumber("012345678901")
                        .build())
                .checkOut(LocalDateTime.now().plusHours(3))
                .guestCount(1)
                .rooms(List.of(assignment(room.getId(), "Khách trả tiền mặt")))
                .paymentOption(WalkInPaymentOption.CASH)
                .paymentAmount(collected)
                .build();

        WalkInReservationResponse response = reservationService.createWalkInCheckedIn(
                request, staff, "127.0.0.1");
        reloadPersistenceContext();

        PaymentTransaction payment = paymentTransactionRepository
                .findByReservationId(response.getReservation().getId()).stream()
                .findFirst().orElseThrow();
        assertEquals(ReservationStatus.CHECKED_IN,
                reservationRepository.findById(response.getReservation().getId()).orElseThrow().getStatus());
        assertEquals("SUCCESS", response.getPaymentCreationStatus());
        assertEquals(PaymentProvider.CASH, payment.getProvider());
        assertEquals(PaymentPurpose.WALK_IN, payment.getPurpose());
        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
        assertEquals(collected, payment.getExpectedAmount());
        assertEquals(collected, payment.getReceivedAmount());
        assertEquals(collected, payment.getAcceptedAmount());
        assertEquals(0L, payment.getRefundRequiredAmount());
    }

    @Test
    void checkInRejectsWhenSubmittedGuestsDoNotMatchReservationGuestCount() {
        RoomType roomType = roomType(2);
        Room room = room(roomType);
        Reservation reservation = confirmedReservation(customer(), roomType, 1, 2);

        assertThrows(RuntimeException.class, () -> reservationService.checkIn(
                reservation.getId(), List.of(assignment(room.getId(), "Chỉ có một khách"))));
    }

    @Test
    void checkoutIsBlockedUntilOutstandingBalanceIsPaid() {
        RoomType roomType = roomType(2);
        Room room = room(roomType);
        Reservation reservation = confirmedReservation(customer(), roomType, 1, 1);
        reservationService.checkIn(reservation.getId(), List.of(assignment(room.getId(), "Khách chính")));

        assertThrows(RuntimeException.class,
                () -> reservationService.checkOut(reservation.getId()));
    }

    @Test
    void overpaymentIsRefundedBeforeCheckoutAndRoomBecomesDirty() {
        RoomType roomType = roomType(2);
        Room room = room(roomType);
        User customer = customer();
        Reservation reservation = confirmedReservation(customer, roomType, 1, 1);
        reservationService.checkIn(reservation.getId(), List.of(assignment(room.getId(), "Khách chính")));

        reservation = reservationRepository.findById(reservation.getId()).orElseThrow();
        long alreadyPaid = paymentTransactionRepository.findByReservationId(reservation.getId())
                .stream().filter(tx -> tx.getStatus() == PaymentStatus.SUCCESS)
                .mapToLong(PaymentTransaction::getAmount).sum();
        long scheduledTotal = reservation.getTotalAmount().longValue();
        if (scheduledTotal > alreadyPaid) {
            successfulPayment(reservation, scheduledTotal - alreadyPaid, PaymentPurpose.FINAL_PAYMENT);
        }

        User staff = staff();
        FinalPaymentResponse settlement = reservationService.calculateFinalPayment(reservation.getId(), staff);
        assertTrue(settlement.getRefundableAmount() > 0,
                () -> "Early checkout should produce an overpayment: " + settlement);

        Long reservationId = reservation.getId();
        reservationService.requestCheckoutRefund(reservationId, refundRequest(RefundChannel.CASH_AT_COUNTER));
        reloadPersistenceContext();
        int refundLedgerSize = paymentRefundRepository.findByReservationId(reservationId).size();
        reservationService.requestCheckoutRefund(reservationId, refundRequest(RefundChannel.CASH_AT_COUNTER));
        reloadPersistenceContext();
        assertEquals(refundLedgerSize,
                paymentRefundRepository.findByReservationId(reservationId).size(),
                "Gửi lại yêu cầu hoàn checkout không được tạo ledger thứ hai");
        assertTrue(paymentRefundRepository.findByReservationId(reservationId).stream()
                .anyMatch(refund -> refund.getChannel() == RefundChannel.CASH_AT_COUNTER
                        && refund.getStatus() == RefundStatus.REQUESTED),
                "Cash refund must wait for staff handover confirmation");
        assertThrows(RuntimeException.class, () -> reservationService.checkOut(reservationId),
                "Checkout phải bị chặn khi tiền mặt chưa thực sự giao cho khách");

        PaymentRefund cashRefund = paymentRefundRepository.findByReservationId(reservationId)
                .stream().findFirst().orElseThrow();
        CashRefundCompleteRequest cashComplete = new CashRefundCompleteRequest();
        cashComplete.setConfirmed(true);
        paymentRefundService.completeCashAtCounter(cashRefund.getId(), cashComplete, staff);
        ReservationResponse checkedOut = reservationService.checkOut(reservationId);

        assertEquals(ReservationStatus.CHECKED_OUT, checkedOut.getStatus());
        Room releasedRoom = roomRepository.findById(room.getId()).orElseThrow();
        assertEquals(RoomStatus.AVAILABLE, releasedRoom.getStatus());
        assertEquals(CleaningStatus.DIRTY, releasedRoom.getCleaningStatus());
        assertNotNull(guestRepository.findAllByReservationId(reservation.getId()).get(0).getCheckedOutAt());
        assertTrue(reservationInvoiceRepository.findByReservationId(reservation.getId()).isPresent());
    }

    /**
     * Khoản thu tiền mặt thừa phải chờ khách cung cấp tài khoản, sau đó staff
     * xác nhận mã chuyển khoản thực tế trước khi checkout.
     */
    @Test
    void checkoutCashOverpaymentUsesManualBankTransferWorkflow() {
        RoomType roomType = roomType(2);
        Room room = room(roomType);
        User customer = customer();
        Reservation reservation = confirmedReservation(customer, roomType, 1, 1);
        reservationService.checkIn(reservation.getId(), List.of(assignment(room.getId(), "Khách chính")));

        Long reservationId = reservation.getId();
        FinalPaymentResponse beforeCash = reservationService.calculateFinalPayment(reservationId, staff());
        assertEquals(0L, beforeCash.getRefundableAmount());
        reloadPersistenceContext();
        reservation = reservationRepository.findById(reservationId).orElseThrow();
        long cashOverpayment = Math.max(0L, beforeCash.getRemainingAmount()) + 10_000L;
        PaymentTransaction cashPayment = paymentTransactionRepository.save(PaymentTransaction.builder()
                .reservation(reservation)
                .txnRef("CASH-OVERPAY-" + suffix())
                .provider(PaymentProvider.CASH)
                .purpose(PaymentPurpose.FINAL_PAYMENT)
                .status(PaymentStatus.SUCCESS)
                .amount(cashOverpayment)
                .currency("VND")
                .paidAt(LocalDateTime.now())
                .message("Thanh toán tiền mặt thừa trong kịch bản đối soát")
                .build());
        entityManager.flush();
        String cashPaymentId = cashPayment.getId();
        reloadPersistenceContext();

        FinalPaymentResponse settlement = reservationService.calculateFinalPayment(reservationId, staff());
        assertEquals(10_000L, settlement.getRefundableAmount());
        reservationService.requestCheckoutRefund(reservationId,
                refundRequest(RefundChannel.MANUAL_BANK_TRANSFER));
        reloadPersistenceContext();

        PaymentRefund pending = paymentRefundRepository.findByReservationId(reservationId).stream()
                .filter(refund -> refund.getPaymentTransaction().getId().equals(cashPaymentId))
                .findFirst()
                .orElseThrow();
        assertEquals(RefundChannel.MANUAL_BANK_TRANSFER, pending.getChannel());
        assertEquals(RefundStatus.AWAITING_CUSTOMER_INFO, pending.getStatus());
        assertEquals(10_000L, pending.getAmount());
        assertEquals(PaymentStatus.REFUND_PENDING,
                paymentTransactionRepository.findById(cashPaymentId).orElseThrow().getStatus());

        RefundRecipientRequest recipientRequest = new RefundRecipientRequest();
        // Legacy frontend aliases are normalized to VietQR's official code.
        recipientRequest.setBankCode("CTG");
        recipientRequest.setBankName("VietinBank");
        recipientRequest.setAccountNumber("012345678901");
        recipientRequest.setAccountHolderName("CUSTOMER TEST");
        var recipientResponse = refundRecipientService.submit(
                reservationId, recipientRequest, customer, null);
        assertEquals(RefundRecipientStatus.SUBMITTED, recipientResponse.getStatus());
        reloadPersistenceContext();

        PaymentRefund ready = paymentRefundRepository.findById(pending.getId()).orElseThrow();
        assertEquals(RefundStatus.REQUESTED, ready.getStatus());
        assertNotNull(ready.getRecipient());
        var manualDetails = paymentRefundService.getManualDetails(ready.getId());
        assertEquals("012345678901", manualDetails.getAccountNumber());
        assertEquals("CUSTOMER TEST", manualDetails.getAccountHolderName());
        assertEquals("ICB", manualDetails.getBankCode());
        assertNotNull(manualDetails.getRefundQrCodeUrl());
        assertTrue(manualDetails.getRefundQrCodeUrl().startsWith("https://vietqr.app/img?"));
        assertTrue(manualDetails.getRefundQrCodeUrl().contains("bank=ICB"));
        assertTrue(manualDetails.getRefundQrCodeUrl().contains("amount=10000"));
        assertTrue(manualDetails.getRefundQrCodeUrl().contains("des=SEVQR%20HOAN%20"));
        assertTrue(manualDetails.getTransferContent().startsWith("SEVQR HOAN "));

        ManualRefundCompleteRequest completeRequest = new ManualRefundCompleteRequest();
        completeRequest.setRecipientId(manualDetails.getRecipientId());
        completeRequest.setRecipientVersion(manualDetails.getRecipientVersion());
        completeRequest.setTransferredAt(LocalDateTime.now());
        completeRequest.setFallbackReason("Không nhận được webhook SePay sau thời gian chờ");
        User refundStaff = userRepository.save(staff());
        String readyRefundId = ready.getId();

        // Chuyển khoản ngân hàng không được xác nhận thủ công khi vẫn còn trong
        // thời gian chờ SePay, dù staff đã điền đủ thông tin fallback.
        assertThrows(RuntimeException.class,
                () -> paymentRefundService.completeManualTransfer(readyRefundId, completeRequest, refundStaff));

        ManualRefundFallbackOpenRequest openFallback = new ManualRefundFallbackOpenRequest();
        openFallback.setReason("Admin xác minh ngân hàng không gửi webhook");
        var opened = paymentRefundService.openManualFallback(
                readyRefundId, openFallback, userRepository.save(admin()));
        assertNotNull(opened.getManualFallbackOpenedAtUtc());
        reloadPersistenceContext();
        assertEquals(RefundStatus.REQUESTED,
                paymentRefundRepository.findById(readyRefundId).orElseThrow().getStatus(),
                "Admin mở fallback chỉ cấp quyền xác nhận, không tự hoàn tất refund");
        paymentRefundService.completeManualTransfer(readyRefundId, completeRequest, refundStaff);
        reloadPersistenceContext();

        PaymentRefund completed = paymentRefundRepository.findById(ready.getId()).orElseThrow();
        assertEquals(RefundStatus.SUCCEEDED, completed.getStatus());
        assertEquals(RefundChannel.MANUAL_BANK_TRANSFER, completed.getChannel());
        assertNull(completed.getManualTransferReference());
        assertNull(completed.getProviderRefundTxnId());
        assertNull(completed.getProofAsset(), "Ảnh minh chứng chỉ là tùy chọn ở fallback");
        assertEquals(RefundCompletionMethod.MANUAL_FALLBACK, completed.getCompletionMethod());
        assertEquals(RefundRecipientStatus.VERIFIED, completed.getRecipient().getStatus());
        assertNotNull(completed.getRecipient().getVerifiedAt());

        // Response của chính khách chỉ công khai số tiền, trạng thái và URL
        // minh chứng; không đưa số tài khoản hoặc mã giao dịch ngân hàng ra ngoài.
        ReservationResponse customerView = reservationService.getReservation(reservationId, customer, null);
        assertEquals(1, customerView.getRefunds().size());
        assertNull(customerView.getRefunds().get(0).getProofImageUrl());
        assertEquals(RefundStatus.SUCCEEDED, customerView.getRefunds().get(0).getStatus());
        PaymentTransaction refundedCash = paymentTransactionRepository.findById(cashPaymentId).orElseThrow();
        assertEquals(PaymentProvider.CASH, refundedCash.getRefundProvider());
        // Chỉ 10.000đ tiền thừa được hoàn: payment vẫn là khoản thu
        // hợp lệ cho reservation, không được đánh dấu đã hoàn toàn bộ.
        assertEquals(PaymentStatus.SUCCESS, refundedCash.getStatus());
        assertEquals(10_000L, refundedCash.getRefundAmount());
        assertNull(refundedCash.getRefundCompletedAt());
        assertEquals(ReservationStatus.CHECKED_OUT,
                reservationService.checkOut(reservationId).getStatus());
    }

    @Test
    void noShowCannotBeMarkedOnArrivalDay() {
        Reservation reservation = directConfirmedReservation(LocalDateTime.now().minusHours(1));

        assertThrows(RuntimeException.class,
                () -> reservationService.markNoShow(reservation.getId()));
    }

    @Test
    void noShowCanBeMarkedAfterArrivalDay() {
        Reservation reservation = directConfirmedReservation(LocalDateTime.now().minusDays(1));

        assertEquals(ReservationStatus.NO_SHOW,
                reservationService.markNoShow(reservation.getId()).getStatus());
    }

    @Test
    void noShowCanBeMarkedOnArrivalDayAfterConfiguredGracePeriod() {
        Reservation reservation = directConfirmedReservation(LocalDateTime.now().minusHours(7));

        assertEquals(ReservationStatus.NO_SHOW,
                reservationService.markNoShow(reservation.getId()).getStatus());
    }

    @Test
    void noShowRejectsCorruptConfirmedReservationThatAlreadyHasActualCheckIn() {
        Reservation reservation = directConfirmedReservation(LocalDateTime.now().minusDays(1));
        reservation.setActualCheckIn(LocalDateTime.now().minusHours(20));
        reservationRepository.saveAndFlush(reservation);

        assertThrows(RuntimeException.class,
                () -> reservationService.markNoShow(reservation.getId()));
        assertEquals(ReservationStatus.CONFIRMED,
                reservationRepository.findById(reservation.getId()).orElseThrow().getStatus());
    }

    @Test
    void anotherCustomerCannotReadReservation() {
        RoomType roomType = roomType(2);
        room(roomType);
        ReservationResponse created = createOnline(customer(), roomType, 1, 1);

        assertThrows(RuntimeException.class,
                () -> reservationService.getReservation(created.getId(), customer(), null));
    }

    @Test
    void invoiceIsUnavailableBeforeCheckout() {
        RoomType roomType = roomType(2);
        room(roomType);
        Reservation reservation = confirmedReservation(customer(), roomType, 1, 1);

        assertThrows(RuntimeException.class,
                () -> reservationService.getInvoice(reservation.getId(), staff()));
    }

    @Test
    void expiredUnpaidHoldCancelsPaymentSession() {
        RoomType roomType = roomType(2);
        room(roomType);
        ReservationResponse created = createOnline(customer(), roomType, 1, 1);
        reservationService.activatePaymentHolds(created.getId(), LocalDateTime.now().plusMinutes(5));
        RoomHold hold = holdOf(created.getId());
        hold.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        roomHoldRepository.save(hold);
        entityManager.flush();
        entityManager.clear();

        roomHoldExpiryScheduler.expireHolds();
        entityManager.flush();
        entityManager.clear();

        assertEquals(HoldStatus.EXPIRED, holdOf(created.getId()).getStatus());
        assertEquals(ReservationStatus.CANCELLED,
                reservationRepository.findById(created.getId()).orElseThrow().getStatus());
    }

    /**
     * Regression: once a successful payment callback wins the reservation lock,
     * the expiry scheduler must not overwrite the converted hold or cancel the
     * paid DRAFT reservation, even when the original expiry instant has passed.
     */
    @Test
    void paidAndConvertedReservationIsNotExpiredByScheduler() {
        RoomType roomType = roomType(2);
        room(roomType);
        ReservationResponse created = createOnline(customer(), roomType, 1, 1);
        Reservation reservation = reservationRepository.findById(created.getId()).orElseThrow();
        reservationService.activatePaymentHolds(created.getId(), LocalDateTime.now().plusMinutes(5));
        RoomHold hold = holdOf(created.getId());
        successfulPayment(reservation, depositAmount(reservation), PaymentPurpose.DEPOSIT);
        reloadPersistenceContext();

        reservationService.convertHoldsAfterPayment(reservation.getId());
        RoomHold converted = holdOf(created.getId());
        converted.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        roomHoldRepository.save(converted);
        roomHoldExpiryScheduler.expireHolds();
        reloadPersistenceContext();

        assertEquals(ReservationStatus.DRAFT,
                reservationRepository.findById(reservation.getId()).orElseThrow().getStatus());
        assertEquals(HoldStatus.CONVERTED, holdOf(reservation.getId()).getStatus());
    }

    private ReservationResponse createOnline(User customer, RoomType roomType, int quantity, int guests) {
        LocalDateTime checkIn = LocalDateTime.now().plusMinutes(30);
        return reservationService.createReservation(
                customer,
                onlineRequest(roomType, quantity, guests, checkIn, checkIn.plusHours(3)));
    }

    private Reservation confirmedReservation(User customer, RoomType roomType, int quantity, int guests) {
        ReservationResponse created = createOnline(customer, roomType, quantity, guests);
        Reservation reservation = reservationRepository.findById(created.getId()).orElseThrow();
        successfulPayment(reservation, depositAmount(reservation), PaymentPurpose.DEPOSIT);
        reloadPersistenceContext();
        reservationService.convertHoldsAfterPayment(reservation.getId());
        reloadPersistenceContext();
        reservationService.confirmReservation(reservation.getId());
        reloadPersistenceContext();
        return reservationRepository.findById(reservation.getId()).orElseThrow();
    }

    private CreateReservationRequest onlineRequest(
            RoomType roomType, int quantity, int guests,
            LocalDateTime checkIn, LocalDateTime checkOut) {
        String suffix = suffix();
        return CreateReservationRequest.builder()
                .customer(CustomerProfileRequest.builder()
                        .fullName("Khách " + suffix)
                        .phone(uniquePhone())
                        .email("guest-" + suffix + "@example.com")
                        .build())
                .checkIn(checkIn)
                .checkOut(checkOut)
                .guestCount(guests)
                .roomTypes(List.of(RoomTypeItemRequest.builder()
                        .roomTypeId(roomType.getId()).quantity(quantity).build()))
                .build();
    }

    private Reservation directConfirmedReservation(LocalDateTime checkIn) {
        CustomerProfile profile = customerProfileRepository.save(CustomerProfile.builder()
                .fullName("Khách no-show " + suffix())
                .phone(uniquePhone())
                .email(uniqueEmail())
                .source(CustomerProfileSource.ONLINE)
                .build());
        return reservationRepository.save(Reservation.builder()
                .reservationCode("RES-TEST-" + suffix())
                .customerProfile(profile)
                .checkIn(checkIn)
                .checkOut(checkIn.plusHours(3))
                .guestCount(1)
                .totalAmount(BigDecimal.valueOf(100_000))
                .status(ReservationStatus.CONFIRMED)
                .build());
    }

    private PaymentTransaction successfulPayment(
            Reservation reservation, long amount, PaymentPurpose purpose) {
        if (purpose == PaymentPurpose.DEPOSIT
                && reservation.getStatus() == ReservationStatus.PAYMENT_PENDING
                && holdOfOrNull(reservation.getId()) == null) {
            reservationService.activatePaymentHolds(
                    reservation.getId(), LocalDateTime.now().plusMinutes(5));
        }
        return paymentTransactionRepository.save(PaymentTransaction.builder()
                .reservation(reservation)
                .txnRef("TEST-" + suffix())
                .provider(PaymentProvider.SEPAY)
                .purpose(purpose)
                .status(PaymentStatus.SUCCESS)
                .amount(amount)
                .currency("VND")
                .providerCreateDate(LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")))
                .paidAt(LocalDateTime.now())
                .build());
    }

    private long depositAmount(Reservation reservation) {
        return reservation.getTotalAmount()
                .multiply(BigDecimal.valueOf(0.5))
                .setScale(0, RoundingMode.CEILING)
                .longValueExact();
    }

    private RoomType roomType(int maxGuests) {
        return roomTypeRepository.save(RoomType.builder()
                .typeName("Scenario " + suffix())
                .description("Reservation scenario test")
                .price(BigDecimal.valueOf(100_000))
                .maxGuests(maxGuests)
                .build());
    }

    private Room room(RoomType roomType) {
        return roomRepository.save(Room.builder()
                .roomName("TEST-" + suffix())
                .roomType(roomType)
                .floor(1)
                .status(RoomStatus.AVAILABLE)
                .cleaningStatus(CleaningStatus.CLEAN)
                .build());
    }

    private User customer() {
        String suffix = suffix();
        return userRepository.save(User.builder()
                .fullName("Customer " + suffix)
                .username("customer-" + suffix)
                .email("customer-" + suffix + "@example.com")
                .phone(uniquePhone())
                .password("test")
                .type(UserType.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private User staff() {
        String suffix = suffix();
        return User.builder()
                .fullName("Staff " + suffix)
                .username("staff-" + suffix)
                .email("staff-" + suffix + "@example.com")
                .phone(uniquePhone())
                .type(UserType.STAFF)
                .status(UserStatus.ACTIVE)
                .build();
    }

    private User admin() {
        String suffix = suffix();
        return User.builder()
                .fullName("Admin " + suffix)
                .username("admin-" + suffix)
                .email("admin-" + suffix + "@example.com")
                .phone(uniquePhone())
                .type(UserType.ADMIN)
                .status(UserStatus.ACTIVE)
                .build();
    }

    private AssignRoomRequest assignment(Long roomId, String guestName) {
        return AssignRoomRequest.builder()
                .roomId(roomId)
                .guests(List.of(GuestRequest.builder()
                        .fullName(guestName)
                        .isPrimary(true)
                        .build()))
                .build();
    }

    private CancelReservationRequest cancellation(String reason, boolean refund) {
        RefundRecipientRequest recipient = new RefundRecipientRequest();
        recipient.setBankCode("NCB");
        recipient.setBankName("National Citizen Bank");
        recipient.setAccountNumber("012345678901");
        recipient.setAccountHolderName("CUSTOMER TEST");
        return CancelReservationRequest.builder()
                .cancellationReason(reason)
                .refundPayment(refund)
                .refundChannel(refund ? RefundChannel.MANUAL_BANK_TRANSFER : null)
                .refundRecipient(recipient)
                .build();
    }

    private ReservationRefundRequest refundRequest(RefundChannel channel) {
        ReservationRefundRequest request = new ReservationRefundRequest();
        request.setRefundChannel(channel);
        return request;
    }

    private RoomHold holdOf(Long reservationId) {
        RoomHold hold = holdOfOrNull(reservationId);
        assertNotNull(hold, "Expected reservation to have a room hold");
        return hold;
    }

    private void reloadPersistenceContext() {
        entityManager.flush();
        entityManager.clear();
    }

    private RoomHold holdOfOrNull(Long reservationId) {
        return roomHoldRepository.findAll().stream()
                .filter(hold -> hold.getReservationRoomType().getReservation().getId().equals(reservationId))
                .findFirst()
                .orElse(null);
    }

    private String uniquePhone() {
        String digits = UUID.randomUUID().toString().replace("-", "")
                .replaceAll("[a-f]", "1").substring(0, 8);
        return "09" + digits;
    }

    private String uniqueEmail() {
        return "guest-" + suffix() + "@example.com";
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
