package com.hotel.backend.integration;

import com.hotel.backend.constant.*;
import com.hotel.backend.dto.request.*;
import com.hotel.backend.dto.response.FinalPaymentResponse;
import com.hotel.backend.dto.response.CheckoutReconciliationResponse;
import com.hotel.backend.dto.response.PaymentResponse;
import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.Room;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.*;
import com.hotel.backend.service.PaymentService;
import com.hotel.backend.service.PaymentSessionExpiryService;
import com.hotel.backend.service.ReservationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ma trận test nghiệp vụ check-in -> payment -> checkout.
 *
 * <p>Mỗi test được viết theo Given/When/Then để đồng thời là
 * tài liệu cho nhân viên vận hành và test hồi quy cho backend.</p>
 *
 * <ul>
 *   <li>Check-in: sớm quá 2 giờ, sớm trong giới hạn, trễ cùng ngày, quá ngày, phòng bẩn.</li>
 *   <li>Checkout: sớm, trong cùng kỳ tính giá, muộn và thao tác lặp.</li>
 *   <li>Payment: cọc, thanh toán cuối, thiếu/đủ tiền, sai purpose và giao dịch VNPay đang chờ.</li>
 *   <li>Phụ phí: cho phép sửa nhiều lần nhưng không cộng dồn giá trị cũ.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReservationOperationalFlowIntegrationTest {

    @Autowired ReservationService reservationService;
    @Autowired PaymentService paymentService;
    @Autowired PaymentSessionExpiryService paymentSessionExpiryService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired RoomTypeRepository roomTypeRepository;
    @Autowired RoomRepository roomRepository;
    @Autowired PaymentTransactionRepository paymentTransactionRepository;
    @Autowired RoomHoldRepository roomHoldRepository;
    @Autowired ReservationAuditLogRepository auditLogRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * TC-CI-01 - Từ chối check-in sớm quá giới hạn.
     *
     * <p>Reservation đã được xác nhận nhưng thời điểm hiện tại còn cách giờ nhận
     * phòng hơn 2 giờ. Kết quả mong đợi: backend báo lỗi, reservation vẫn
     * CONFIRMED và phòng vẫn AVAILABLE.</p>
     */
    @Test
    void checkInMoreThanTwoHoursEarlyIsRejected() {
        // Given: reservation đã CONFIRMED nhưng còn hơn 2 giờ nữa mới đến giờ nhận phòng.
        Fixture fixture = confirmedFixture(
                LocalDateTime.now().plusHours(2).plusMinutes(10), 3);

        // When/Then: backend từ chối và không thay đổi trạng thái reservation/phòng.
        assertThrows(RuntimeException.class, () -> checkIn(fixture));
        assertEquals(ReservationStatus.CONFIRMED,
                reservationRepository.findById(fixture.reservationId()).orElseThrow().getStatus());
        assertEquals(RoomStatus.AVAILABLE,
                roomRepository.findById(fixture.roomId()).orElseThrow().getStatus());
    }

    /**
     * TC-CI-02 - Cho phép check-in sớm trong giới hạn 2 giờ.
     *
     * <p>Khách đến sớm 1 giờ 59 phút. Kết quả mong đợi: check-in thành công,
     * backend tự ghi actualCheckIn và phòng chuyển sang CHECKED_IN.</p>
     */
    @Test
    void checkInWithinTwoHourWindowIsAllowedAndStoresActualTime() {
        // Given: khách đến sớm 1 giờ 59 phút, nằm trong giới hạn cho phép.
        Fixture fixture = confirmedFixture(
                LocalDateTime.now().plusHours(1).plusMinutes(59), 3);
        LocalDateTime before = LocalDateTime.now();

        // When: staff gán phòng sạch và check-in.
        ReservationResponse response = checkIn(fixture);

        // Then: thời gian thực tế do backend ghi nhận, phòng chuyển CHECKED_IN.
        assertEquals(ReservationStatus.CHECKED_IN, response.getStatus());
        Reservation persisted = reservationRepository.findById(fixture.reservationId()).orElseThrow();
        assertNotNull(persisted.getActualCheckIn());
        assertFalse(persisted.getActualCheckIn().isBefore(before));
        assertEquals(RoomStatus.CHECKED_IN,
                roomRepository.findById(fixture.roomId()).orElseThrow().getStatus());
    }

    /**
     * TC-CI-03 - Cho phép khách check-in trễ nhưng vẫn trong ngày nhận phòng.
     *
     * <p>Việc đến sau giờ dự kiến không làm reservation tự động thành no-show
     * khi ngày nhận phòng chưa kết thúc.</p>
     */
    @Test
    void lateCheckInOnTheSameDayIsAllowed() {
        // Given: reservation đã qua giờ nhận phòng 90 phút nhưng vẫn trong cùng ngày.
        Fixture fixture = confirmedFixture(LocalDateTime.now().plusMinutes(30), 4);
        LocalDateTime startOfToday = LocalDateTime.now().toLocalDate().atStartOfDay();
        moveSchedule(fixture.reservationId(),
                startOfToday, LocalDateTime.now().plusHours(3));

        // When: khách đến trễ và staff thực hiện check-in.
        ReservationResponse response = checkIn(fixture);

        // Then: check-in vẫn thành công, không tự động chuyển no-show.
        assertEquals(ReservationStatus.CHECKED_IN, response.getStatus());
    }

    /**
     * TC-CI-04 - Không cho check-in trực tiếp khi đã qua ngày nhận phòng.
     *
     * <p>Reservation phải giữ nguyên CONFIRMED để staff xử lý theo quy trình
     * no-show, thay vì tạo lịch sử check-in sai ngày.</p>
     */
    @Test
    void checkInAfterTheArrivalDateIsRejectedForStaffNoShowHandling() {
        // Given: đã sang ngày khác so với ngày check-in, dù check-out dự kiến chưa qua.
        Fixture fixture = confirmedFixture(LocalDateTime.now().plusMinutes(30), 4);
        moveSchedule(fixture.reservationId(),
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusHours(2));

        // When/Then: check-in bị chặn để staff xử lý NO_SHOW theo flow riêng.
        assertThrows(RuntimeException.class, () -> checkIn(fixture));
        assertEquals(ReservationStatus.CONFIRMED,
                reservationRepository.findById(fixture.reservationId()).orElseThrow().getStatus());
    }

    /**
     * TC-CI-05 - Không xếp khách vào phòng chưa được dọn sạch.
     *
     * <p>Dù reservation hợp lệ và đã CONFIRMED, phòng có cleaningStatus DIRTY
     * vẫn phải bị loại khỏi thao tác check-in.</p>
     */
    @Test
    void checkInRejectsDirtyRoomEvenWhenReservationIsConfirmed() {
        // Given: reservation hợp lệ nhưng phòng vật lý chưa dọn xong.
        Fixture fixture = confirmedFixture(LocalDateTime.now().plusMinutes(30), 3);
        Room room = roomRepository.findById(fixture.roomId()).orElseThrow();
        room.setCleaningStatus(CleaningStatus.DIRTY);
        roomRepository.save(room);

        // When/Then: không cho nhận khách vào phòng DIRTY.
        assertThrows(RuntimeException.class, () -> checkIn(fixture));
    }

    /**
     * TC-CO-01 - Checkout sớm phải tính lại tiền phòng theo thời gian thực tế.
     *
     * <p>Khách đặt 4 giờ nhưng trả trong giờ đầu. Kết quả mong đợi: tiền phòng
     * thực tế còn 100.000 VND và phần đã thanh toán thừa được đưa vào số tiền
     * có thể hoàn, không được coi là số dư phải thu.</p>
     */
    @Test
    void earlyCheckoutRepricesActualUsageAndExposesRefundAmount() {
        // Given: khách đặt 4 giờ (130.000), check-in ngay và đã thanh toán đủ giá dự kiến.
        Fixture fixture = confirmedFixture(LocalDateTime.now().plusMinutes(30), 4);
        checkIn(fixture);
        payRemainingDirectly(fixture.reservationId(), PaymentProvider.VNPAY);

        // When: staff mở đối soát ngay trong giờ đầu.
        FinalPaymentResponse settlement = reservationService.calculateFinalPayment(
                fixture.reservationId(), staff());

        // Then: tiền phòng thực tế còn 100.000, giảm 30.000 và hoàn 30.000.
        assertEquals(130_000L, settlement.getPlannedRoomCharge());
        assertEquals(100_000L, settlement.getRoomCharge());
        assertEquals(30_000L, settlement.getEarlyCheckoutAdjustment());
        assertEquals(30_000L, settlement.getRefundableAmount());
        assertEquals(0L, settlement.getRemainingAmount());
    }

    /**
     * TC-CO-02 - Checkout trong cùng đơn vị giờ tính giá không bị giảm hoặc
     * cộng thêm tiền.
     *
     * <p>Test bảo vệ quy tắc làm tròn giờ: khách vẫn sử dụng trong giờ đầu thì
     * tiền phòng là 100.000 VND, early adjustment và late fee đều bằng 0.</p>
     */
    @Test
    void checkoutWithinTheSameBillableHourKeepsRoomPriceAndHasNoLateFee() {
        // Given: actual check-in và check-out vẫn nằm trong cùng 1 giờ tính giá.
        Fixture fixture = confirmedFixture(LocalDateTime.now().plusMinutes(30), 1);
        checkIn(fixture);
        Reservation reservation = reservationRepository.findById(fixture.reservationId()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        reservation.setCheckIn(now.minusMinutes(55));
        reservation.setActualCheckIn(now.minusMinutes(55));
        reservation.setCheckOut(now.plusMinutes(4));
        reservationRepository.save(reservation);
        reload();

        // When: mở đối soát gần thời điểm trả phòng dự kiến.
        FinalPaymentResponse settlement = reservationService.calculateFinalPayment(
                fixture.reservationId(), staff());

        // Then: không giảm giá sớm và không tính phí trễ.
        assertEquals(100_000L, settlement.getTotalAmount());
        assertEquals(0L, settlement.getEarlyCheckoutAdjustment());
        assertEquals(0L, settlement.getLateCheckoutFee());
    }

    /**
     * TC-CO-02B - Preview checkout sớm không được trở thành giảm giá vĩnh viễn.
     *
     * <p>Staff có thể mở màn hình đối soát khi khách định trả sớm, nhưng khách
     * sau đó ở qua giờ trả phòng. Backend phải đảo khoản giảm đã preview rồi mới
     * cộng phí checkout muộn, nếu không khách sạn sẽ bị tính thiếu tiền phòng.</p>
     */
    @Test
    void earlySettlementIsReversedBeforeLateCheckoutRecalculation() {
        // Given: booking 4 giờ đã preview checkout trong giờ đầu, giảm từ 130.000 còn 100.000.
        Fixture fixture = confirmedFixture(LocalDateTime.now().plusMinutes(30), 4);
        checkIn(fixture);
        FinalPaymentResponse early = reservationService.calculateFinalPayment(
                fixture.reservationId(), staff());
        assertEquals(30_000L, early.getEarlyCheckoutAdjustment());
        assertEquals(100_000L, early.getTotalAmount());

        // When: khách ở lại và thời gian hiện tại đã muộn hơn checkout dự kiến 1 phút.
        moveSchedule(fixture.reservationId(),
                LocalDateTime.now().minusHours(4), LocalDateTime.now().minusMinutes(1));
        FinalPaymentResponse late = reservationService.calculateFinalPayment(
                fixture.reservationId(), staff());

        // Then: hoàn nguyên giá dự kiến 130.000, xóa giảm sớm và cộng 10.000 phí muộn.
        assertEquals(0L, late.getEarlyCheckoutAdjustment());
        assertEquals(10_000L, late.getLateCheckoutFee());
        assertEquals(140_000L, late.getTotalAmount());
        assertEquals(130_000L, late.getRoomCharge());
    }

    /**
     * TC-CO-03 - Checkout muộn phải tính phí trễ và thu đúng phần còn thiếu.
     *
     * <p>Khách trả muộn 61 phút nên được làm tròn thành 2 giờ phụ phí. Sau khi
     * thanh toán tiền mặt, remainingAmount phải về 0 và giao dịch phải mang
     * purpose FINAL_PAYMENT.</p>
     */
    @Test
    void lateCheckoutAddsFeeAndCashPaymentCollectsExactShortfall() {
        // Given: reservation 1 phòng đã check-in, sau đó trả muộn 61 phút.
        Fixture fixture = confirmedFixture(LocalDateTime.now().plusMinutes(30), 4);
        checkIn(fixture);
        moveSchedule(fixture.reservationId(),
                LocalDateTime.now().minusHours(4),
                LocalDateTime.now().minusMinutes(61).withNano(0));

        // When: backend đối soát; 61 phút được làm tròn thành 2 giờ trễ.
        FinalPaymentResponse beforePayment = reservationService.calculateFinalPayment(
                fixture.reservationId(), staff());

        // Then: phụ phí trễ = 2 x 10.000 cho 1 phòng.
        assertEquals(20_000L, beforePayment.getLateCheckoutFee());
        assertEquals(150_000L, beforePayment.getTotalAmount());
        assertEquals(85_000L, beforePayment.getRemainingAmount());

        // When: staff thu tiền mặt; PaymentService phải tự lấy đúng phần còn thiếu.
        PaymentResponse payment = createCashFinalPayment(fixture.reservationId(), PaymentPurpose.FINAL_PAYMENT);

        // Then: thông tin giao dịch và số dư đều chính xác.
        assertEquals(PaymentProvider.CASH, payment.getProvider());
        assertEquals(PaymentPurpose.FINAL_PAYMENT, payment.getPurpose());
        assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
        assertEquals(85_000L, payment.getAmount());
        assertEquals(0L, reservationService.calculateFinalPayment(
                fixture.reservationId(), staff()).getRemainingAmount());
    }

    /**
     * TC-FEE-01 - Cho phép staff sửa phụ phí đối soát nhiều lần.
     *
     * <p>Mỗi lần cập nhật phải thay thế giá trị trước đó, không cộng dồn ngoài
     * ý muốn. Đồng thời mỗi lần sửa đều phải để lại audit log.</p>
     */
    @Test
    void additionalFeeCanBeEditedRepeatedlyWithoutAccumulatingOldValue() {
        // Given: checkout sớm trong giờ đầu nên tiền phòng thực tế là 100.000.
        Fixture fixture = confirmedFixture(LocalDateTime.now().plusMinutes(30), 3);
        checkIn(fixture);
        reservationService.calculateFinalPayment(fixture.reservationId(), staff());

        // When: staff nhập phụ phí 30.000, sau đó sửa lại còn 10.000.
        updateAdditionalFee(fixture.reservationId(), 30_000L);
        updateAdditionalFee(fixture.reservationId(), 10_000L);

        // Then: tổng = 100.000 + 10.000, không phải 100.000 + 30.000 + 10.000.
        FinalPaymentResponse settlement = reservationService.calculateFinalPayment(
                fixture.reservationId(), staff());
        assertEquals(10_000L, settlement.getCheckoutAdditionalFee());
        assertEquals(110_000L, settlement.getTotalAmount());
        assertEquals(2L, auditLogRepository.findByReservationIdOrderByCreatedAtDesc(fixture.reservationId())
                .stream().filter(log -> log.getAction() == ReservationAuditAction.UPDATE_CHECKOUT_FEE).count());
    }

    /**
     * TC-REC-01 - GET reconciliation là projection thuần, không được ghi giá preview.
     */
    @Test
    void checkoutReconciliationReadDoesNotMutateReservationOrAudit() {
        Fixture fixture = confirmedFixture(LocalDateTime.now().plusMinutes(30), 4);
        checkIn(fixture);
        entityManager.flush();
        entityManager.clear();

        Reservation before = reservationRepository.findById(fixture.reservationId()).orElseThrow();
        BigDecimal totalBefore = before.getTotalAmount();
        BigDecimal earlyBefore = before.getEarlyCheckoutAdjustment();
        BigDecimal lateBefore = before.getLateCheckoutFee();
        BigDecimal additionalBefore = before.getCheckoutAdditionalFee();
        long passedBefore = auditLogRepository
                .findByReservationIdOrderByCreatedAtDesc(fixture.reservationId()).stream()
                .filter(log -> log.getAction() == ReservationAuditAction.CHECKOUT_RECONCILIATION_PASSED)
                .count();

        CheckoutReconciliationResponse first = reservationService
                .getCheckoutReconciliation(fixture.reservationId(), staff());
        CheckoutReconciliationResponse replay = reservationService
                .getCheckoutReconciliation(fixture.reservationId(), staff());
        assertEquals(first.getRequiredAmount(), replay.getRequiredAmount());

        entityManager.flush();
        entityManager.clear();
        Reservation after = reservationRepository.findById(fixture.reservationId()).orElseThrow();
        assertEquals(totalBefore, after.getTotalAmount());
        assertEquals(earlyBefore, after.getEarlyCheckoutAdjustment());
        assertEquals(lateBefore, after.getLateCheckoutFee());
        assertEquals(additionalBefore, after.getCheckoutAdditionalFee());
        assertEquals(passedBefore, auditLogRepository
                .findByReservationIdOrderByCreatedAtDesc(fixture.reservationId()).stream()
                .filter(log -> log.getAction() == ReservationAuditAction.CHECKOUT_RECONCILIATION_PASSED)
                .count());
    }

    /**
     * TC-PAY-01 - Thanh toán đủ số tiền cuối cho phép checkout đúng một lần.
     *
     * <p>Test kiểm tra cả dữ liệu tài chính của transaction và tính idempotent
     * nghiệp vụ: checkout lần hai phải bị từ chối để không phát hành hóa đơn
     * hoặc giải phóng phòng lặp.</p>
     */
    @Test
    void exactFinalPaymentAllowsCheckoutAndRepeatedCheckoutIsRejected() {
        // Given: khách đã check-in; backend tính lại giá và staff thu đúng phần còn thiếu.
        Fixture fixture = confirmedFixture(LocalDateTime.now().plusMinutes(30), 3);
        checkIn(fixture);
        FinalPaymentResponse before = reservationService.calculateFinalPayment(
                fixture.reservationId(), staff());
        PaymentResponse payment = createCashFinalPayment(
                fixture.reservationId(), PaymentPurpose.FINAL_PAYMENT);

        // Then: PaymentResponse và entity transaction phải lưu đủ dữ liệu tài chính.
        assertEquals(before.getRemainingAmount(), payment.getAmount());
        PaymentTransaction transaction = paymentTransactionRepository.findById(payment.getTransactionId()).orElseThrow();
        assertEquals("VND", transaction.getCurrency());
        assertEquals(PaymentProvider.CASH, transaction.getProvider());
        assertEquals(PaymentPurpose.FINAL_PAYMENT, transaction.getPurpose());
        assertEquals(PaymentStatus.SUCCESS, transaction.getStatus());
        assertNotNull(transaction.getPaidAt());

        // When: checkout lần đầu thành công.
        ReservationResponse checkedOut = reservationService.checkOut(fixture.reservationId());
        assertEquals(ReservationStatus.CHECKED_OUT, checkedOut.getStatus());
        assertEquals(1L, auditLogRepository
                .findByReservationIdOrderByCreatedAtDesc(fixture.reservationId()).stream()
                .filter(log -> log.getAction() == ReservationAuditAction.CHECKOUT_RECONCILIATION_PASSED)
                .count());

        // Then: thao tác lặp bị từ chối, tránh tạo hóa đơn/giải phóng phòng hai lần.
        assertThrows(RuntimeException.class,
                () -> reservationService.checkOut(fixture.reservationId()));
    }

    /**
     * TC-PAY-02 - Không tạo thêm giao dịch khi VNPay final payment đang chờ.
     *
     * <p>Quy tắc này ngăn khách hoặc staff thanh toán hai lần trong khoảng thời
     * gian backend chưa nhận được callback cho giao dịch PENDING hiện tại.</p>
     */
    @Test
    void pendingVnPayFinalPaymentBlocksCreatingAnotherPayment() {
        // Given: reservation CHECKED_IN đang có một giao dịch VNPay PENDING.
        Fixture fixture = confirmedFixture(LocalDateTime.now().plusMinutes(30), 3);
        checkIn(fixture);
        Reservation reservation = reservationRepository.findById(fixture.reservationId()).orElseThrow();
        paymentTransactionRepository.save(PaymentTransaction.builder()
                .reservation(reservation)
                .txnRef("PENDING-FINAL-" + suffix())
                .provider(PaymentProvider.VNPAY)
                .purpose(PaymentPurpose.FINAL_PAYMENT)
                .status(PaymentStatus.PENDING)
                .amount(35_000L)
                .currency("VND")
                .build());

        // When/Then: request mới dùng provider active SEPAY vẫn bị chặn khi payment lịch sử đang chờ.
        PaymentRequest request = new PaymentRequest();
        request.setBookingId(fixture.reservationId());
        request.setProvider(PaymentProvider.SEPAY);
        request.setPurpose(PaymentPurpose.FINAL_PAYMENT);
        assertThrows(RuntimeException.class, () -> paymentService.createPayment(
                request, new MockHttpServletRequest(), staff(), null));
    }

    /**
     * TC-PAY-03 - Không thu final payment bằng tiền mặt trước check-in.
     *
     * <p>Reservation CONFIRMED chưa có thời gian sử dụng thực tế nên chưa thể
     * thực hiện đối soát và ghi nhận khoản thanh toán cuối.</p>
     */
    @Test
    void cashPaymentBeforeCheckInIsRejected() {
        // Given: reservation mới CONFIRMED, khách chưa nhận phòng.
        Fixture fixture = confirmedFixture(LocalDateTime.now().plusMinutes(30), 3);

        // When/Then: tiền mặt chỉ dùng cho thanh toán cuối sau check-in.
        assertThrows(RuntimeException.class, () -> createCashFinalPayment(
                fixture.reservationId(), PaymentPurpose.FINAL_PAYMENT));
    }

    /**
     * TC-PAY-04 - Purpose thanh toán phải phù hợp vòng đời reservation.
     *
     * <p>Sau khi reservation đã CHECKED_IN, khoản thu tiếp theo không được ghi
     * nhầm thành DEPOSIT; backend phải từ chối purpose không hợp lệ.</p>
     */
    @Test
    void paymentPurposeMustMatchReservationLifecycle() {
        // Given: reservation đã CHECKED_IN nên payment tiếp theo phải là FINAL_PAYMENT/WALK_IN.
        Fixture fixture = confirmedFixture(LocalDateTime.now().plusMinutes(30), 3);
        checkIn(fixture);

        // When/Then: không cho ghi nhận nhầm khoản thanh toán cuối thành DEPOSIT.
        assertThrows(RuntimeException.class, () -> createCashFinalPayment(
                fixture.reservationId(), PaymentPurpose.DEPOSIT));
    }

    @Test
    void manualRoomHoldReleaseRequiresActiveUnpaidAggregateAndCapturesStaffActor() {
        Long reservationId = paymentPendingFixture();
        User staff = userRepository.save(User.builder()
                .fullName("Staff release hold")
                .username("hold-staff-" + suffix())
                .email("hold-staff-" + suffix() + "@example.com")
                .phone(uniquePhone())
                .password("test")
                .type(UserType.STAFF)
                .status(UserStatus.ACTIVE)
                .build());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        staff, null, staff.getAuthorities()));
        ManualRoomHoldReleaseRequest request = new ManualRoomHoldReleaseRequest();
        request.setReasonCode("GUEST_CANCELLED_BEFORE_PAYMENT");
        request.setNote("Khách yêu cầu hủy trước khi chuyển tiền cọc");

        ReservationResponse result = paymentSessionExpiryService
                .releaseRoomHoldsManually(reservationId, request);

        assertEquals(ReservationStatus.CANCELLED, result.getStatus());
        assertTrue(roomHoldRepository.findByReservationId(reservationId).stream()
                .allMatch(hold -> hold.getStatus() == HoldStatus.RELEASED));
        var audit = auditLogRepository.findByReservationIdOrderByCreatedAtDesc(reservationId)
                .stream()
                .filter(log -> log.getAction()
                        == ReservationAuditAction.ROOM_HOLD_RELEASED_MANUALLY)
                .findFirst().orElseThrow();
        assertEquals(staff.getId(), audit.getActorUserId());
        assertEquals("STAFF", audit.getActorRole());
        assertThrows(RuntimeException.class, () -> paymentSessionExpiryService
                .releaseRoomHoldsManually(reservationId, request));
    }

    @Test
    void expiredRoomHoldCleanupUsesSystemActorSharedCorrelationAndIsRetrySafe() {
        Long reservationId = paymentPendingFixture();
        roomHoldRepository.findByReservationId(reservationId).forEach(hold -> {
            hold.setExpiresAt(LocalDateTime.now().minusMinutes(1));
            roomHoldRepository.save(hold);
        });
        reload();

        assertTrue(paymentSessionExpiryService.timeoutDepositReservation(reservationId));
        assertFalse(paymentSessionExpiryService.timeoutDepositReservation(reservationId));

        var logs = auditLogRepository.findByReservationIdOrderByCreatedAtDesc(reservationId)
                .stream()
                .filter(log -> log.getAction() == ReservationAuditAction.ROOM_HOLD_AUTO_EXPIRED
                        || log.getAction() == ReservationAuditAction.RESERVATION_AUTO_CANCELLED)
                .toList();
        assertEquals(2, logs.size());
        assertTrue(logs.stream().allMatch(log -> log.getActorUserId() == null));
        assertTrue(logs.stream().allMatch(log -> "SYSTEM".equals(log.getActorRole())));
        assertEquals(1L, logs.stream().map(log -> log.getCorrelationId()).distinct().count());
    }

    private Fixture confirmedFixture(LocalDateTime checkIn, int bookedHours) {
        RoomType roomType = roomTypeRepository.save(RoomType.builder()
                .typeName("Operational " + suffix())
                .description("Operational flow integration test")
                .price(BigDecimal.valueOf(100_000))
                .maxGuests(2)
                .build());
        Room room = roomRepository.save(Room.builder()
                .roomName("OPS-" + suffix())
                .roomType(roomType)
                .floor(1)
                .status(RoomStatus.AVAILABLE)
                .cleaningStatus(CleaningStatus.CLEAN)
                .build());
        User customer = customer();
        ReservationResponse created = reservationService.createReservation(customer,
                CreateReservationRequest.builder()
                        .customer(CustomerProfileRequest.builder()
                                .fullName(customer.getFullName())
                                .phone(customer.getPhone())
                                .email(customer.getEmail())
                                .build())
                        .checkIn(checkIn)
                        .checkOut(checkIn.plusHours(bookedHours))
                        .guestCount(1)
                        .roomTypes(List.of(RoomTypeItemRequest.builder()
                                .roomTypeId(roomType.getId()).quantity(1).build()))
                        .build());
        Reservation reservation = reservationRepository.findById(created.getId()).orElseThrow();
        reservationService.activatePaymentHolds(reservation.getId(), LocalDateTime.now().plusMinutes(5));
        saveSuccessfulPayment(reservation, requiredDeposit(reservation), PaymentProvider.VNPAY, PaymentPurpose.DEPOSIT);
        reload();
        reservationService.convertHoldsAfterPayment(reservation.getId());
        reload();
        reservationService.confirmReservation(reservation.getId());
        reload();
        return new Fixture(reservation.getId(), room.getId(), customer);
    }

    private Long paymentPendingFixture() {
        RoomType roomType = roomTypeRepository.save(RoomType.builder()
                .typeName("Pending hold " + suffix())
                .description("Manual and automatic hold integration test")
                .price(BigDecimal.valueOf(100_000))
                .maxGuests(2)
                .build());
        roomRepository.save(Room.builder()
                .roomName("HOLD-" + suffix())
                .roomType(roomType)
                .floor(1)
                .status(RoomStatus.AVAILABLE)
                .cleaningStatus(CleaningStatus.CLEAN)
                .build());
        User customer = customer();
        LocalDateTime checkIn = LocalDateTime.now().plusHours(3);
        ReservationResponse created = reservationService.createReservation(customer,
                CreateReservationRequest.builder()
                        .customer(CustomerProfileRequest.builder()
                                .fullName(customer.getFullName())
                                .phone(customer.getPhone())
                                .email(customer.getEmail())
                                .build())
                        .checkIn(checkIn)
                        .checkOut(checkIn.plusHours(3))
                        .guestCount(1)
                        .roomTypes(List.of(RoomTypeItemRequest.builder()
                                .roomTypeId(roomType.getId()).quantity(1).build()))
                        .build());
        reservationService.activatePaymentHolds(
                created.getId(), LocalDateTime.now().plusMinutes(5));
        reload();
        return created.getId();
    }

    private ReservationResponse checkIn(Fixture fixture) {
        return reservationService.checkIn(fixture.reservationId(), List.of(
                AssignRoomRequest.builder()
                        .roomId(fixture.roomId())
                        .guests(List.of(GuestRequest.builder()
                                .fullName("Khách chính")
                                .phone("0900000000")
                                .isPrimary(true)
                                .build()))
                        .build()));
    }

    private PaymentResponse createCashFinalPayment(Long reservationId, PaymentPurpose purpose) {
        PaymentRequest request = new PaymentRequest();
        request.setBookingId(reservationId);
        request.setProvider(PaymentProvider.CASH);
        request.setPurpose(purpose);
        return paymentService.createCashPayment(
                request, new MockHttpServletRequest(), staff());
    }

    private void payRemainingDirectly(Long reservationId, PaymentProvider provider) {
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        long paid = paymentTransactionRepository.findByReservationId(reservationId).stream()
                .filter(transaction -> transaction.getStatus() == PaymentStatus.SUCCESS)
                .mapToLong(PaymentTransaction::getAmount)
                .sum();
        long remaining = reservation.getTotalAmount().longValue() - paid;
        if (remaining > 0) {
            saveSuccessfulPayment(reservation, remaining, provider, PaymentPurpose.FINAL_PAYMENT);
        }
    }

    private PaymentTransaction saveSuccessfulPayment(
            Reservation reservation, long amount, PaymentProvider provider, PaymentPurpose purpose) {
        return paymentTransactionRepository.save(PaymentTransaction.builder()
                .reservation(reservation)
                .txnRef("OPS-PAY-" + suffix())
                .provider(provider)
                .purpose(purpose)
                .status(PaymentStatus.SUCCESS)
                .amount(amount)
                .currency("VND")
                .paidAt(LocalDateTime.now())
                .message("Operational test payment")
                .build());
    }

    private void updateAdditionalFee(Long reservationId, long amount) {
        CheckoutRefundRequest request = new CheckoutRefundRequest();
        request.setAdditionalFee(amount);
        request.setReasonCode("INTEGRATION_TEST");
        request.setReason("Kiểm tra cập nhật phụ phí có audit");
        reservationService.updateCheckoutAdditionalFee(reservationId, request);
    }

    private void moveSchedule(Long reservationId, LocalDateTime checkIn, LocalDateTime checkOut) {
        Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
        reservation.setCheckIn(checkIn);
        reservation.setCheckOut(checkOut);
        reservationRepository.save(reservation);
        reload();
    }

    private long requiredDeposit(Reservation reservation) {
        return reservation.getTotalAmount().multiply(BigDecimal.valueOf(0.5))
                .setScale(0, RoundingMode.CEILING).longValueExact();
    }

    private User customer() {
        String suffix = suffix();
        return userRepository.save(User.builder()
                .fullName("Operational customer")
                .username("ops-customer-" + suffix)
                .email("ops-" + suffix + "@example.com")
                .phone(uniquePhone())
                .password("test")
                .type(UserType.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private User staff() {
        return User.builder()
                .fullName("Operational staff")
                .username("ops-staff-" + suffix())
                .email("ops-staff-" + suffix() + "@example.com")
                .type(UserType.STAFF)
                .status(UserStatus.ACTIVE)
                .build();
    }

    private String uniquePhone() {
        String digits = UUID.randomUUID().toString().replace("-", "")
                .replaceAll("[a-f]", "1").substring(0, 8);
        return "09" + digits;
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private void reload() {
        entityManager.flush();
        entityManager.clear();
    }

    private record Fixture(Long reservationId, Long roomId, User customer) {}
}
