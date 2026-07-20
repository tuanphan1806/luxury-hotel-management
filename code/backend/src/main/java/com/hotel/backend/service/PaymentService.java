package com.hotel.backend.service;
import com.hotel.backend.dto.request.PaymentRequest;
import com.hotel.backend.dto.request.RefundRequest;
import com.hotel.backend.dto.response.PaymentResponse;
import com.hotel.backend.dto.response.PaymentRefundResponse;

import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.PaymentPurpose;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.HoldStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.repository.RoomHoldRepository;
import com.hotel.backend.repository.ReservationRoomTypeRepository;
import com.hotel.backend.util.VNPayUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SePayService sePayService;
    private final PaymentTransactionRepository transactionRepository;
    private final ReservationRepository reservationRepository;
    private final RoomHoldRepository roomHoldRepository;
    private final ReservationRoomTypeRepository reservationRoomTypeRepository;
    private final ReservationService reservationService;
    private final ReservationAuditService reservationAuditService;
    private final PaymentRefundService paymentRefundService;
    private final PaymentSessionExpiryService paymentSessionExpiryService;
    // ==================== TẠO GIAO DỊCH MỚI ====================

    @Transactional
    public PaymentResponse createPayment(
            PaymentRequest request,
            HttpServletRequest httpRequest,
            User currentUser,
            String guestToken) {
        String ipAddress = VNPayUtil.getClientIp(httpRequest);
        // Lock the payment aggregate before validation and insert. This prevents
        // concurrent requests from both observing an unpaid balance and creating
        // duplicate deposit/final-payment transactions.
        Reservation reservation = reservationRepository.findByIdForUpdate(request.getBookingId())
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        ensureCanAccessReservation(currentUser, reservation, guestToken);
        validateReservationCanAcceptPayment(reservation);
        validateOnlinePaymentAllowed(reservation);

        PaymentProvider requestedProvider = request.getProvider() != null
                ? request.getProvider() : PaymentProvider.SEPAY;
        if (requestedProvider == PaymentProvider.CASH) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Thanh toán tiền mặt vui lòng dùng endpoint /api/payments/cash");
        }
        if (requestedProvider != PaymentProvider.SEPAY) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Thanh toán online mới chỉ dùng SePay VietQR; VNPay chỉ được giữ cho giao dịch lịch sử");
        }

        // Số tiền và mục đích phải khớp với phiên cũ trước khi cho
        // phép idempotent reuse; không trả nhầm QR đã cũ khi phụ phí thay đổi.
        PaymentBalance balance = resolvePaymentBalance(reservation);
        long amount = balance.defaultAmount();
        validatePaymentAmount(amount, balance.remainingAmount());
        PaymentPurpose purpose = resolvePaymentPurpose(reservation, request.getPurpose());
        PaymentTransaction reusablePayment = findReusablePendingPayment(reservation, amount, purpose);
        if (reusablePayment != null) {
            validateDepositHoldIsActive(reservation);
            log.info("Trả lại phiên SePay đang chờ: txnRef={}, reservationId={}",
                    reusablePayment.getTxnRef(), reservation.getId());
            return toSePayCreateResponse(reusablePayment, "Phiên VietQR đang chờ thanh toán");
        }
        validateDraftDepositPaymentNotCreated(reservation);
        validateNoPendingFinalPayment(reservation);

        sePayService.validateCheckoutConfig();
        LocalDateTime paymentExpiresAt = sePayService.newExpiryTime();
        if (purpose == PaymentPurpose.DEPOSIT) {
            // Reservation mới chỉ là dữ liệu nhập tạm. Tồn kho chỉ bị khóa khi
            // backend chuẩn bị phát hành QR và hold dùng đúng hạn của QR.
            reservationService.activatePaymentHolds(reservation.getId(), paymentExpiresAt);
            validateDepositHoldIsActive(reservation);
        }
        String txnRef = sePayService.newPaymentCode(request.getBookingId());
        String orderInfo = request.getOrderInfo() != null
                ? request.getOrderInfo()
                : "Thanh toan dat phong " + request.getBookingId();

        // Lưu giao dịch PENDING vào DB trước
        PaymentTransaction transaction = PaymentTransaction.builder()
                .reservation(reservation)
                .txnRef(txnRef)
                .provider(PaymentProvider.SEPAY)
                .purpose(purpose)
                .status(PaymentStatus.PENDING)
                .amount(amount)
                .expectedAmount(amount)
                .currency("VND")
                .orderInfo(orderInfo)
                .ipAddress(ipAddress)
                .expiresAt(paymentExpiresAt)
                .expiresAtUtc(paymentExpiresAt.atZone(HOTEL_ZONE).toInstant())
                .build();

        transaction = transactionRepository.save(transaction);
        log.info("Tạo giao dịch mới: txnRef={}, provider={}, amount={}",
                txnRef, PaymentProvider.SEPAY, amount);

        return toSePayCreateResponse(transaction, "Đã tạo mã SePay VietQR");
    }

    /**
     * Creates the optional SePay collection session after a walk-in aggregate
     * has already committed as CHECKED_IN. This transaction never creates a
     * RoomHold and expiry therefore cannot roll back or cancel the stay.
     */
    // A rejected optional SePay setup must not mark the surrounding atomic
    // walk-in command rollback-only; all validation AppExceptions occur before
    // this method persists a payment row.
    @Transactional(noRollbackFor = AppException.class)
    public PaymentResponse createWalkInSePayPayment(
            Long reservationId,
            Long requestedAmount,
            HttpServletRequest httpRequest,
            User currentUser) {
        String ipAddress = VNPayUtil.getClientIp(httpRequest);
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        ensureCanAccessReservation(currentUser, reservation);
        if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ tạo SePay walk-in sau khi reservation đã CHECKED_IN");
        }

        PaymentBalance balance = resolvePaymentBalance(reservation);
        long amount = requestedAmount != null ? requestedAmount : balance.defaultAmount();
        validatePaymentAmount(amount, balance.remainingAmount());
        PaymentTransaction reusable = findReusablePendingPayment(
                reservation, amount, PaymentPurpose.WALK_IN);
        if (reusable != null) {
            return toSePayCreateResponse(reusable, "Phiên VietQR walk-in đang chờ thanh toán");
        }
        validateNoPendingFinalPayment(reservation);
        sePayService.validateCheckoutConfig();

        LocalDateTime walkInExpiresAt = sePayService.newExpiryTime();
        PaymentTransaction transaction = transactionRepository.save(
                PaymentTransaction.builder()
                        .reservation(reservation)
                        .txnRef(sePayService.newPaymentCode(reservationId))
                        .provider(PaymentProvider.SEPAY)
                        .purpose(PaymentPurpose.WALK_IN)
                        .status(PaymentStatus.PENDING)
                        .amount(amount)
                        .expectedAmount(amount)
                        .currency("VND")
                        .orderInfo("Thanh toan walk-in " + reservation.getReservationCode())
                        .ipAddress(ipAddress)
                        .expiresAt(walkInExpiresAt)
                        .expiresAtUtc(walkInExpiresAt.atZone(HOTEL_ZONE).toInstant())
                        .message("Đang chờ thanh toán SePay walk-in")
                        .build());
        reservationAuditService.record(reservation, ReservationAuditAction.PAYMENT_SESSION_CREATED,
                "Tạo SePay QR walk-in " + amount + " VND");
        return toSePayCreateResponse(transaction, "Đã tạo mã SePay VietQR walk-in");
    }

    /**
     * Người dùng chủ động rời trang QR: hủy transaction đang chờ và nhả hold
     * ngay. UUID transaction là capability khó đoán; thao tác idempotent và chỉ
     * tác động khi tiền chưa được backend ghi nhận.
     */
    public void abandonPendingPayment(String transactionId) {
        paymentSessionExpiryService.abandon(transactionId);
    }

    @Transactional
    public PaymentResponse createCashPayment(PaymentRequest request, HttpServletRequest httpRequest, User currentUser) {
        String ipAddress = VNPayUtil.getClientIp(httpRequest);
        // Cash collection shares the same critical section as online payment so
        // two front-desk requests cannot collect the same remaining balance.
        Reservation reservation = reservationRepository.findByIdForUpdate(request.getBookingId())
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        ensureCanAccessReservation(currentUser, reservation);
        validateReservationCanAcceptPayment(reservation);
        validateCashPaymentAllowed(reservation);
        PaymentBalance balance = resolvePaymentBalance(reservation);
        long amount = balance.defaultAmount();
        validatePaymentAmount(amount, balance.remainingAmount());

        String txnRef = VNPayUtil.generateTxnRef("CASH-" + request.getBookingId());
        String orderInfo = request.getOrderInfo() != null
                ? request.getOrderInfo()
                : "Thanh toan tien mat dat phong " + request.getBookingId();

        LocalDateTime paidAt = LocalDateTime.now();
        PaymentTransaction transaction = PaymentTransaction.builder()
                .reservation(reservation)
                .txnRef(txnRef)
                .provider(PaymentProvider.CASH)
                .purpose(resolvePaymentPurpose(reservation, request.getPurpose()))
                .status(PaymentStatus.SUCCESS)
                .amount(amount)
                .expectedAmount(amount)
                .receivedAmount(amount)
                .acceptedAmount(amount)
                .refundRequiredAmount(0L)
                .currency("VND")
                .orderInfo(orderInfo)
                .ipAddress(ipAddress)
                .paidAt(paidAt)
                .paidAtUtc(paidAt.atZone(HOTEL_ZONE).toInstant())
                .message("Thanh toán tiền mặt thành công")
                .build();

        transaction = transactionRepository.save(transaction);
        reservationService.convertHoldsAfterPayment(reservation.getId());

        log.info("Tạo giao dịch tiền mặt: txnRef={}, reservationId={}, amount={}",
                txnRef, reservation.getId(), amount);

        return PaymentResponse.builder()
                .transactionId(transaction.getId())
                .bookingId(transaction.getReservation().getId())
                .provider(transaction.getProvider())
                .status(transaction.getStatus())
                .purpose(transaction.getPurpose())
                .amount(transaction.getAmount())
                .expectedAmount(transaction.getExpectedAmount())
                .receivedAmount(transaction.getReceivedAmount())
                .acceptedAmount(transaction.getAcceptedAmount())
                .refundRequiredAmount(transaction.getRefundRequiredAmount())
                .message("Thanh toán tiền mặt thành công")
                .createdAt(transaction.getCreatedAt())
                .paidAtUtc(transaction.getPaidAtUtc())
                .build();
    }

    // ==================== TRUY VẤN ====================

    public PaymentTransaction getTransaction(String transactionId, User currentUser) {
        PaymentTransaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy giao dịch: " + transactionId));
        ensureCanAccessReservation(currentUser, transaction.getReservation());
        return transaction;
    }

    public List<PaymentTransaction> getTransactionsByReservation(Long reservationId, User currentUser) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        ensureCanAccessReservation(currentUser, reservation);
        return transactionRepository.findByReservationId(reservationId);
    }
 
    public List<PaymentTransaction> getSuccessfulTransactions(Long reservationId) {
        return transactionRepository.findByReservationIdAndStatus(reservationId, PaymentStatus.SUCCESS);
    }

    @Transactional(readOnly = true)
    public PaymentResponse replayPaymentResponse(
            String transactionId,
            User currentUser,
            String guestToken) {
        PaymentTransaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy giao dịch thanh toán"));
        ensureCanAccessReservation(currentUser, transaction.getReservation(), guestToken);
        if (transaction.getProvider() == PaymentProvider.SEPAY
                && transaction.getStatus() == PaymentStatus.PENDING) {
            return toSePayCreateResponse(transaction, "Phiên VietQR đang chờ thanh toán");
        }
        return toResponse(transaction);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getLatestWalkInPayment(Long reservationId, User currentUser) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        ensureCanAccessReservation(currentUser, reservation);
        PaymentTransaction transaction = transactionRepository.findByReservationId(reservationId).stream()
                .filter(payment -> payment.getPurpose() == PaymentPurpose.WALK_IN)
                .max(java.util.Comparator.comparing(
                        PaymentTransaction::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .orElse(null);
        if (transaction == null) return null;
        if (transaction.getProvider() == PaymentProvider.SEPAY
                && transaction.getStatus() == PaymentStatus.PENDING) {
            return toSePayCreateResponse(transaction, "Phiên VietQR walk-in đang chờ thanh toán");
        }
        return toResponse(transaction);
    }
    // ==================== HOÀN TIỀN ====================

    @Transactional
    public PaymentRefundResponse refund(RefundRequest request, User currentUser) {
        PaymentRefundResponse response = paymentRefundService.requestTransactionRefund(
                request, currentUser != null && hasText(currentUser.getUsername())
                        ? currentUser.getUsername() : "hotel_system");
        PaymentTransaction transaction = transactionRepository.findById(request.getTransactionId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy giao dịch: " + request.getTransactionId()));
        reservationAuditService.record(transaction.getReservation(), ReservationAuditAction.REFUND,
                "Tạo yêu cầu hoàn " + request.getAmount() + " VND qua " + response.getRefundChannel()
                        + " cho giao dịch " + transaction.getTxnRef());
        return response;
    }

    private void validateReservationCanAcceptPayment(Reservation reservation) {
        if (List.of(ReservationStatus.CANCELLED, ReservationStatus.CHECKED_OUT)
                .contains(reservation.getStatus())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Không thể thanh toán cho đặt phòng đã hủy hoặc đã checkout");
        }
    }

    private void validateCashPaymentAllowed(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Tiền mặt chỉ dùng cho thanh toán cuối khi khách đã check-in. Đặt cọc dùng SePay VietQR");
        }
    }

    private void validateOnlinePaymentAllowed(Reservation reservation) {
        if (List.of(ReservationStatus.PAYMENT_PENDING, ReservationStatus.CHECKED_IN).contains(reservation.getStatus())) {
            return;
        }
        throw new AppException(ErrorCode.INVALID_REQUEST,
                "SePay VietQR chỉ dùng cho phiên đang chờ đặt cọc hoặc thanh toán cuối khi đã CHECKED_IN");
    }

    private void validateDepositHoldIsActive(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.PAYMENT_PENDING) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        var holds = roomHoldRepository.findByReservationId(reservation.getId());
        long expectedHoldCount = reservationRoomTypeRepository.countByReservationId(reservation.getId());
        boolean allHoldsActive = expectedHoldCount > 0
                && holds.size() == expectedHoldCount
                && holds.stream().allMatch(hold -> hold.getStatus() == HoldStatus.ACTIVE
                        && hold.getExpiresAt().isAfter(now));
        if (!allHoldsActive) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Phiên giữ phòng đã hết hạn. Vui lòng tạo lại đặt phòng trước khi thanh toán");
        }
    }

    private void validateDraftDepositPaymentNotCreated(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.PAYMENT_PENDING) {
            return;
        }

        boolean hasDepositPayment = transactionRepository
                .existsByReservationIdAndPurposeAndStatus(
                        reservation.getId(), PaymentPurpose.DEPOSIT, PaymentStatus.PENDING)
                || transactionRepository.existsByReservationIdAndPurposeAndStatus(
                        reservation.getId(), PaymentPurpose.DEPOSIT, PaymentStatus.SUCCESS);

        if (hasDepositPayment) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Đặt phòng này đã có giao dịch đặt cọc, không thể tạo thêm giao dịch cọc");
        }
    }

    private void validateNoPendingFinalPayment(Reservation reservation) {
        if (reservation.getStatus() == ReservationStatus.CHECKED_IN
                && (transactionRepository.existsByReservationIdAndPurposeAndStatus(
                        reservation.getId(), PaymentPurpose.FINAL_PAYMENT, PaymentStatus.PENDING)
                || transactionRepository.existsByReservationIdAndPurposeAndStatus(
                        reservation.getId(), PaymentPurpose.WALK_IN, PaymentStatus.PENDING))) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Reservation đang có một giao dịch online chờ xử lý. Vui lòng hoàn tất hoặc chờ giao dịch hết hạn");
        }
    }

    private PaymentPurpose resolvePaymentPurpose(Reservation reservation, PaymentPurpose requestedPurpose) {
        PaymentPurpose expected = reservation.getStatus() == ReservationStatus.PAYMENT_PENDING
                ? PaymentPurpose.DEPOSIT
                : PaymentPurpose.FINAL_PAYMENT;
        if (requestedPurpose == PaymentPurpose.WALK_IN && reservation.getStatus() == ReservationStatus.CHECKED_IN) {
            return PaymentPurpose.WALK_IN;
        }
        if (requestedPurpose != null && requestedPurpose != expected) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Mục đích thanh toán không phù hợp trạng thái reservation");
        }
        return expected;
    }

    private void validatePaymentAmount(Long amount, long remainingAmount) {
        if (amount == null || amount <= 0) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Số tiền thanh toán không hợp lệ");
        }

        if (remainingAmount <= 0) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Đặt phòng đã thanh toán đủ");
        }
        if (amount > remainingAmount) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    String.format("Số tiền thanh toán vượt quá số còn lại %,d VND", remainingAmount));
        }
    }

    /**
     * Số tiền thực còn được khách sạn giữ. Giao dịch REFUND_PENDING vẫn chứa
     * phần tiền đã thanh toán hợp lệ sau khi trừ đúng khoản đang hoàn.
     */
    private long getNetPaidAmount(Long reservationId) {
        return paymentRefundService.getNetPaidAmount(reservationId);
    }

    public List<PaymentRefundResponse> getPendingRefunds() {
        return paymentRefundService.getOperationalQueue();
    }

    public PaymentRefundResponse reconcileRefund(String refundId) {
        return paymentRefundService.reconcile(refundId);
    }

    public PaymentRefundResponse retryRefund(String refundId) {
        return paymentRefundService.retry(refundId);
    }

    private PaymentResponse toResponse(PaymentTransaction transaction) {
        return PaymentResponse.builder()
                .transactionId(transaction.getId())
                .bookingId(transaction.getReservation().getId())
                .reservationCode(transaction.getReservation().getReservationCode())
                .provider(transaction.getProvider())
                .refundProvider(transaction.getRefundProvider())
                .purpose(transaction.getPurpose())
                .status(transaction.getStatus())
                .amount(transaction.getAmount())
                .expectedAmount(transaction.getExpectedAmount())
                .receivedAmount(transaction.getReceivedAmount())
                .acceptedAmount(transaction.getAcceptedAmount())
                .refundRequiredAmount(transaction.getRefundRequiredAmount())
                .refundAmount(transaction.getRefundAmount())
                .message(transaction.getMessage())
                .createdAt(transaction.getCreatedAt())
                .updatedAt(transaction.getUpdatedAt())
                .expiresAt(transaction.getExpiresAt())
                .expiresAtUtc(transaction.getExpiresAtUtc())
                .paidAtUtc(transaction.getPaidAtUtc())
                .build();
    }

    private PaymentResponse toSePayCreateResponse(PaymentTransaction transaction, String message) {
        var instructions = sePayService.instructionsFor(transaction);
        return PaymentResponse.builder()
                .transactionId(transaction.getId())
                .bookingId(transaction.getReservation().getId())
                .reservationCode(transaction.getReservation().getReservationCode())
                .transactionReference(transaction.getTxnRef())
                .provider(transaction.getProvider())
                .status(transaction.getStatus())
                .purpose(transaction.getPurpose())
                .amount(transaction.getAmount())
                .expectedAmount(transaction.getExpectedAmount())
                .receivedAmount(transaction.getReceivedAmount())
                .acceptedAmount(transaction.getAcceptedAmount())
                .refundRequiredAmount(transaction.getRefundRequiredAmount())
                .qrCodeUrl(instructions.qrCodeUrl())
                .transferContent(instructions.transferContent())
                .bankAccountNumber(instructions.bankAccountNumber())
                .bankCode(instructions.bankCode())
                .bankName(instructions.bankName())
                .accountHolder(instructions.accountHolder())
                .expiresAt(instructions.expiresAt())
                .expiresAtUtc(transaction.getExpiresAtUtc())
                .paidAtUtc(transaction.getPaidAtUtc())
                .message(message)
                .createdAt(transaction.getCreatedAt())
                .updatedAt(transaction.getUpdatedAt())
                .build();
    }

    private PaymentTransaction findReusablePendingPayment(
            Reservation reservation,
            long expectedAmount,
            PaymentPurpose expectedPurpose) {
        LocalDateTime now = LocalDateTime.now(HOTEL_ZONE);
        Instant nowUtc = Instant.now();
        return transactionRepository.findByReservationId(reservation.getId()).stream()
                .filter(payment -> payment.getProvider() == PaymentProvider.SEPAY)
                .filter(payment -> payment.getStatus() == PaymentStatus.PENDING)
                .filter(payment -> payment.getPurpose() == expectedPurpose)
                .filter(payment -> java.util.Objects.equals(
                        payment.getExpectedAmount() != null
                                ? payment.getExpectedAmount() : payment.getAmount(),
                        expectedAmount))
                .filter(payment -> payment.getExpiresAtUtc() != null
                        ? payment.getExpiresAtUtc().isAfter(nowUtc)
                        : payment.getExpiresAt() == null || payment.getExpiresAt().isAfter(now))
                .max(java.util.Comparator.comparing(PaymentTransaction::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .orElse(null);
    }

    private PaymentBalance resolvePaymentBalance(Reservation reservation) {
        long requiredTotal = reservation.getStatus() == ReservationStatus.CHECKED_IN
                ? reservationService.getProjectedCheckoutTotal(reservation.getId())
                : reservation.getTotalAmount().longValue();
        long paidAmount = getNetPaidAmount(reservation.getId());
        long remainingAmount = Math.max(0L, requiredTotal - paidAmount);
        long defaultAmount = reservation.getStatus() == ReservationStatus.PAYMENT_PENDING
                ? getRequiredDepositAmount(reservation) : remainingAmount;
        return new PaymentBalance(defaultAmount, remainingAmount);
    }

    private long getRequiredDepositAmount(Reservation reservation) {
        if (reservation.getRequiredInitialPayment() != null
                && reservation.getRequiredInitialPayment().signum() > 0) {
            return reservation.getRequiredInitialPayment()
                    .setScale(0, RoundingMode.CEILING).longValue();
        }
        return reservation.getTotalAmount()
                .multiply(BigDecimal.valueOf(0.5))
                .setScale(0, RoundingMode.CEILING)
                .longValue();
    }

    private record PaymentBalance(long defaultAmount, long remainingAmount) {}

    private void ensureCanAccessReservation(User currentUser, Reservation reservation) {
        ensureCanAccessReservation(currentUser, reservation, null);
    }

    private void ensureCanAccessReservation(User currentUser, Reservation reservation, String guestToken) {
        if (currentUser == null) {
            if (hasText(guestToken) && guestToken.equals(reservation.getGuestToken())) {
                return;
            }
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Bạn cần đăng nhập hoặc cung cấp mã khách hợp lệ để thực hiện thao tác này");
        }
        if (List.of(UserType.ADMIN, UserType.STAFF).contains(currentUser.getType())) {
            return;
        }
        if (reservation.getCustomerProfile() == null
                || reservation.getCustomerProfile().getLinkedUser() == null
                || !reservation.getCustomerProfile().getLinkedUser().getId().equals(currentUser.getId())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Bạn không có quyền thao tác với đặt phòng này");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
