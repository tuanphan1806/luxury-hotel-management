package com.hotel.backend.service.Impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.constant.AssignStatus;
import com.hotel.backend.constant.CleaningStatus;
import com.hotel.backend.constant.CustomerProfileSource;
import com.hotel.backend.constant.HoldStatus;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentPlan;
import com.hotel.backend.constant.PaymentPurpose;
import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.ReservationCancellationReasonCode;
import com.hotel.backend.constant.RoomStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.constant.WalkInPaymentOption;
import com.hotel.backend.dto.request.AssignRoomRequest;
import com.hotel.backend.dto.request.CancelReservationRequest;
import com.hotel.backend.dto.request.CheckoutRefundRequest;
import com.hotel.backend.dto.request.CreateReservationRequest;
import com.hotel.backend.dto.request.CreateWalkInReservationRequest;
import com.hotel.backend.dto.request.CreateWalkInCheckedInRequest;
import com.hotel.backend.dto.request.CustomerProfileRequest;
import com.hotel.backend.dto.request.RoomTypeItemRequest;
import com.hotel.backend.dto.request.ReservationRefundRequest;
import com.hotel.backend.dto.request.RejectReservationRequest;
import com.hotel.backend.dto.request.UpdateReservationRequest;
import com.hotel.backend.dto.request.GuestRequest;
import com.hotel.backend.dto.request.WalkInPriceOverrideRequest;
import com.hotel.backend.dto.response.*;
import com.hotel.backend.entity.*;
import com.hotel.backend.event.GuestBookingCreatedEvent;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.*;
import com.hotel.backend.service.ReservationService;
import com.hotel.backend.service.PricingService;
import com.hotel.backend.service.ReservationAuditService;
import com.hotel.backend.service.CustomerProfileClaimService;
import com.hotel.backend.service.PaymentRefundService;
import com.hotel.backend.service.RefundRecipientService;
import com.hotel.backend.util.VNPayUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j(topic = "RESERVATION_SERVICE")
@Service
@RequiredArgsConstructor
public class ReservationServiceImpl implements ReservationService {

    private final ReservationRepository          reservationRepository;
    private final ReservationRoomTypeRepository  reservationRoomTypeRepository;
    private final ReservationRoomRepository      reservationRoomRepository;
    private final RoomHoldRepository             roomHoldRepository;
    private final CustomerProfileRepository      customerProfileRepository;
    private final RoomTypeRepository             roomTypeRepository;
    private final UserRepository                 userRepository;
    private final RoomRepository                 roomRepository;
    private final GuestRepository                guestRepository;
    private final PaymentTransactionRepository   paymentTransactionRepository;
    private final ReservationInvoiceRepository   reservationInvoiceRepository;
    private final ApplicationEventPublisher      eventPublisher;
    private final ObjectMapper                   objectMapper;
    private final PricingService                 pricingService;
    private final ReservationAuditService        auditService;
    private final CustomerProfileClaimService    customerProfileClaimService;
    private final PaymentRefundService           paymentRefundService;
    private final RefundRecipientService         refundRecipientService;

    @Value("${app.hotel-name:Luxury Hotel}")
    private String hotelName;
    @Value("${app.hotel-address:}")
    private String hotelAddress;
    @Value("${app.hotel-phone:}")
    private String hotelPhone;
    @Value("${app.hotel-email:}")
    private String hotelEmail;
    @Value("${app.hotel-tax-code:}")
    private String hotelTaxCode;
    @Value("${app.reservation.no-show-grace-minutes:360}")
    private long noShowGraceMinutes;
    // ─────────────────────────────────────────────────────────────────────────
    // Tạo đặt phòng
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationResponse createReservation(User currentUser, CreateReservationRequest request) {
        return createReservation(currentUser, request, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationResponse createReservation(
            User currentUser,
            CreateReservationRequest request,
            String deterministicGuestToken) {
        log.info("createReservation: userId={} checkIn={} checkOut={}",
                currentUser != null ? currentUser.getId() : null, request.getCheckIn(), request.getCheckOut());

        // 1. Validate ngày
        validateFutureDates(request.getCheckIn(), request.getCheckOut());
        validateReservationRequest(request.getGuestCount(), request.getRoomTypes());

        CustomerProfile customerProfile = currentUser != null
                ? findOrCreateOnlineCustomerProfile(currentUser)
                : resolveGuestOnlineCustomerProfile(request.getCustomer());
        String guestToken = currentUser == null
                ? resolveGuestToken(deterministicGuestToken)
                : null;


        // 3. Check availability + tính tiền cho từng room type
        BigDecimal totalAmount = BigDecimal.ZERO;
        int totalCapacity = 0;

        List<RoomTypeWithPrice> roomTypeWithPrices = new ArrayList<>();

        List<RoomTypeItemRequest> roomTypeRequests = request.getRoomTypes().stream()
                .sorted(Comparator.comparing(RoomTypeItemRequest::getRoomTypeId))
                .toList();

        for (RoomTypeItemRequest item : roomTypeRequests) {
            RoomType roomType = roomTypeRepository.findByIdForUpdate(item.getRoomTypeId())
                    .orElseThrow(() -> new AppException(ErrorCode.ROOM_TYPE_NOT_FOUND));

            checkAvailabilityOrThrow(roomType, item.getQuantity(),
                    request.getCheckIn(), request.getCheckOut(), null);

            BigDecimal pricePerRoom = pricingService.calculateStayPricePerRoom(
                    roomType, request.getCheckIn(), request.getCheckOut());

            BigDecimal subtotal = pricePerRoom.multiply(BigDecimal.valueOf(item.getQuantity()));

            totalAmount = totalAmount.add(subtotal);
            totalCapacity += roomCapacity(roomType) * item.getQuantity();
            roomTypeWithPrices.add(new RoomTypeWithPrice(roomType, item.getQuantity(),
                    pricePerRoom, subtotal));
        }

        validateCapacity(request.getGuestCount(), totalCapacity);

        PaymentPlan paymentPlan = request.getPaymentPlan() != null
                ? request.getPaymentPlan() : PaymentPlan.DEPOSIT_50;
        if (!List.of(PaymentPlan.DEPOSIT_50, PaymentPlan.PREPAY_100).contains(paymentPlan)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Đặt phòng online chỉ hỗ trợ cọc 50% hoặc thanh toán toàn bộ 100%");
        }
        BigDecimal requiredInitialPayment = paymentPlan == PaymentPlan.PREPAY_100
                ? totalAmount
                : totalAmount.multiply(BigDecimal.valueOf(0.5))
                        .setScale(0, RoundingMode.CEILING);

        // 4. Tạo Reservation
        Reservation reservation = Reservation.builder()
                .reservationCode(generateCode())
                .guestToken(guestToken)
                .customerProfile(customerProfile)
                .checkIn(request.getCheckIn())
                .checkOut(request.getCheckOut())
                .totalAmount(totalAmount)
                .paymentPlan(paymentPlan)
                .requiredInitialPayment(requiredInitialPayment)
                .guestCount(request.getGuestCount())
                .note(request.getNote())
                .status(ReservationStatus.PAYMENT_PENDING)
                .build();
        reservationRepository.save(reservation);

        // 5. Chỉ tạo chi tiết phòng. RoomHold chỉ được tạo khi khách mở QR
        // thanh toán, tránh khóa tồn kho khi khách mới điền xong biểu mẫu.
        List<ReservationRoomTypeResponse> roomTypeResponses = new ArrayList<>();

        for (RoomTypeWithPrice item : roomTypeWithPrices) {
            ReservationRoomType rrt = ReservationRoomType.builder()
                    .reservation(reservation)
                    .roomType(item.roomType())
                    .quantity(item.quantity())
                    .roomPrice(item.price())
                    .subtotal(item.subtotal())
                    .build();
            reservationRoomTypeRepository.save(rrt);

            // ReservationRoom placeholder (1 row per unit, room chưa assign)
            for (int i = 0; i < item.quantity(); i++) {
                ReservationRoom rr = ReservationRoom.builder()
                        .reservationRoomType(rrt)
                        .status(AssignStatus.PENDING_ASSIGN)
                        .build();
                reservationRoomRepository.save(rr);
            }

            ReservationRoomTypeResponse rrtRes = ReservationRoomTypeResponse.from(rrt);
            roomTypeResponses.add(rrtRes);
        }

        log.info("Reservation created: code={} total={}", reservation.getReservationCode(), totalAmount);
        ReservationResponse response = ReservationResponse.fromWithDetails(reservation, roomTypeResponses);
        response.setGuestToken(guestToken);
        return paymentRefundService.applyReservationRefundSummary(response);
    }

    private String resolveGuestToken(String deterministicGuestToken) {
        if (!hasText(deterministicGuestToken)) {
            return generateGuestToken();
        }
        String normalized = deterministicGuestToken.trim();
        if (normalized.length() > 64) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Guest reservation token không hợp lệ");
        }
        return normalized;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void activatePaymentHolds(Long reservationId, LocalDateTime expiresAt) {
        LocalDateTime now = LocalDateTime.now();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Thời hạn giữ phòng cho mã QR không hợp lệ");
        }

        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        if (reservation.getStatus() != ReservationStatus.PAYMENT_PENDING) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ phiên đang chờ đặt cọc mới được tạo giữ phòng thanh toán");
        }

        List<ReservationRoomType> items = reservationRoomTypeRepository
                .findDetailsByReservationId(reservation.getId());
        if (items.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Phiên đặt phòng không có loại phòng để giữ");
        }

        // Khóa RoomType theo thứ tự ổn định rồi kiểm tra lại tồn kho ngay tại
        // thời điểm phát hành QR. Hai khách cùng chọn phòng vẫn có thể nhập form,
        // nhưng chỉ QR được tạo trước mới giành được suất cuối cùng.
        for (ReservationRoomType item : items) {
            RoomType lockedRoomType = roomTypeRepository.findByIdForUpdate(item.getRoomType().getId())
                    .orElseThrow(() -> new AppException(ErrorCode.ROOM_TYPE_NOT_FOUND));
            checkAvailabilityOrThrow(
                    lockedRoomType,
                    item.getQuantity(),
                    reservation.getCheckIn(),
                    reservation.getCheckOut(),
                    reservation.getId());
        }

        for (ReservationRoomType item : items) {
            RoomHold hold = item.getRoomHold();
            if (hold == null) {
                hold = RoomHold.builder()
                        .reservationRoomType(item)
                        .expiresAt(expiresAt)
                        .status(HoldStatus.ACTIVE)
                        .build();
                item.setRoomHold(hold);
            } else {
                hold.setExpiresAt(expiresAt);
                hold.setStatus(HoldStatus.ACTIVE);
            }
            roomHoldRepository.save(hold);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void convertHoldsAfterPayment(Long reservationId) {
        // Payment completion and hold expiry both lock the reservation aggregate
        // before mutating its holds.
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));

        if (reservation.getStatus() != ReservationStatus.PAYMENT_PENDING) {
            log.info("Reservation {} không còn PAYMENT_PENDING, bỏ qua hoàn tất thanh toán cọc", reservationId);
            return;
        }

        List<ReservationRoomType> items = reservationRoomTypeRepository
                .findDetailsByReservationId(reservation.getId());
        boolean holdsAreValid = !items.isEmpty()
                && items.stream().allMatch(rrt -> {
                    RoomHold hold = rrt.getRoomHold();
                    return hold != null
                            && hold.getStatus() == HoldStatus.ACTIVE
                            && hold.getExpiresAt().isAfter(LocalDateTime.now());
                });
        if (!holdsAreValid) {
            throw new AppException(ErrorCode.ROOM_HOLD_EXPIRED);
        }

        items.forEach(rrt -> {
            RoomHold hold = rrt.getRoomHold();
            if (hold != null && hold.getStatus() == HoldStatus.ACTIVE) {
                hold.setStatus(HoldStatus.CONVERTED);
                roomHoldRepository.save(hold);
                log.info("RoomHold {} chuyển sang CONVERTED sau khi thanh toán thành công", hold.getId());
            }
        });

        // Thanh toán cọc chỉ hoàn tất bước tạo đơn. Staff vẫn phải xác nhận đơn sau đó.
        reservation.setStatus(ReservationStatus.DRAFT);
        reservationRepository.save(reservation);
        if (reservation.getGuestToken() != null && reservation.getCustomerProfile() != null) {
            eventPublisher.publishEvent(new GuestBookingCreatedEvent(
                    reservation.getId(),
                    reservation.getCustomerProfile().getEmail(),
                    reservation.getGuestToken()));
        }
        log.info("Reservation {} chuyển sang DRAFT sau khi thanh toán thành công, chờ staff xác nhận", reservationId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelForPaymentFailure(Long reservationId, String reasonCode, String message) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        if (reservation.getStatus() != ReservationStatus.PAYMENT_PENDING) {
            return;
        }
        releaseReservationHolds(reservation);
        reservation.setStatusBeforeCancellation(null);
        reservation.setCancellationFee(BigDecimal.ZERO);
        reservation.setRefundableAmount(BigDecimal.ZERO);
        String normalizedCode = hasText(reasonCode) ? reasonCode.trim() : "PAYMENT_FAILURE";
        String normalizedMessage = hasText(message) ? message.trim() : normalizedCode;
        reservation.setCancellationReason(normalizedCode + ": " + normalizedMessage);
        reservation.setCancellationReasonCode(normalizedCode);
        reservation.setLastActivityAt(LocalDateTime.now());
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(reservation);
        auditService.recordSystem(
                reservation,
                "RESERVATION",
                String.valueOf(reservationId),
                ReservationAuditAction.CANCEL,
                "Tự động hủy do payment không được chấp nhận: " + normalizedCode,
                Map.of("status", ReservationStatus.PAYMENT_PENDING.name()),
                Map.of("status", ReservationStatus.CANCELLED.name()),
                Map.of("reasonCode", normalizedCode),
                UUID.randomUUID().toString(),
                "PAYMENT_FAILURE_CANCELLED:" + reservationId + ":" + normalizedCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean recoverOnTimeDepositPayment(
            Long reservationId,
            String paymentId,
            Instant providerOccurredAt) {
        if (reservationId == null || paymentId == null || providerOccurredAt == null) return false;
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        if (reservation.getStatus() != ReservationStatus.CANCELLED
                || !"PAYMENT_SESSION_EXPIRED".equals(reservation.getCancellationReasonCode())) {
            return false;
        }
        PaymentTransaction payment = paymentTransactionRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy payment cần khôi phục"));
        if (payment.getPurpose() != PaymentPurpose.DEPOSIT
                || payment.getStatus() != PaymentStatus.CANCELLED
                || (payment.getExpiresAtUtc() == null && payment.getExpiresAt() == null)) {
            return false;
        }
        Instant createdAt = payment.getCreatedAt() != null
                ? payment.getCreatedAt().atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()
                : null;
        Instant expiresAt = payment.getExpiresAtUtc() != null
                ? payment.getExpiresAtUtc()
                : payment.getExpiresAt().atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        if ((createdAt != null && providerOccurredAt.isBefore(createdAt))
                || !providerOccurredAt.isBefore(expiresAt)) {
            return false;
        }

        List<ReservationRoomType> items = reservationRoomTypeRepository
                .findDetailsByReservationId(reservationId);
        if (items.isEmpty()) return false;
        // Lock all RoomTypes in deterministic order before checking availability.
        for (ReservationRoomType item : items.stream()
                .sorted(Comparator.comparing(item -> item.getRoomType().getId()))
                .toList()) {
            RoomType roomType = roomTypeRepository.findByIdForUpdate(item.getRoomType().getId())
                    .orElseThrow(() -> new AppException(ErrorCode.ROOM_TYPE_NOT_FOUND));
            if (!hasAvailability(roomType, item.getQuantity(), reservation.getCheckIn(),
                    reservation.getCheckOut(), reservationId)) {
                return false;
            }
        }
        List<RoomHold> holds = roomHoldRepository.findByReservationIdForUpdate(reservationId);
        for (ReservationRoomType item : items) {
            RoomHold hold = item.getRoomHold();
            if (hold == null) return false;
            if (hold.getStatus() == HoldStatus.EXPIRED || hold.getStatus() == HoldStatus.ACTIVE) {
                hold.setStatus(HoldStatus.CONVERTED);
                roomHoldRepository.save(hold);
            } else if (hold.getStatus() != HoldStatus.CONVERTED) {
                return false;
            }
        }
        reservation.setStatus(ReservationStatus.DRAFT);
        reservation.setStatusBeforeCancellation(null);
        reservation.setCancellationReasonCode("RECOVERED_ON_TIME_PAYMENT");
        reservation.setCancellationReason(null);
        reservation.setCancellationFee(BigDecimal.ZERO);
        reservation.setRefundableAmount(BigDecimal.ZERO);
        reservation.setLastActivityAt(LocalDateTime.now());
        reservationRepository.save(reservation);
        auditService.record(reservation, ReservationAuditAction.RECOVERED_ON_TIME_PAYMENT,
                "providerOccurredAt=" + providerOccurredAt);
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tạo đặt phòng vãng lai (staff tạo hộ, check-in trực tiếp — bỏ qua DRAFT/Hold/Payment)
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationResponse createWalkInReservation(CreateWalkInReservationRequest request) {
        LocalDateTime actualCheckIn = LocalDateTime.now();
        log.info("createWalkInReservation: customerProfileId={} actualCheckIn={} expectedCheckOut={}",
                request.getCustomerProfileId(), actualCheckIn, request.getCheckOut());

        validateInterval(actualCheckIn, request.getCheckOut());
        validateReservationRequest(request.getGuestCount(), request.getRoomTypes());

        CustomerProfile customerProfile = resolveWalkInCustomerProfile(request);

        BigDecimal totalAmount = BigDecimal.ZERO;
        int totalCapacity = 0;

        List<RoomTypeWithPrice> roomTypeWithPrices = new ArrayList<>();

        List<RoomTypeItemRequest> roomTypeRequests = request.getRoomTypes().stream()
                .sorted(Comparator.comparing(RoomTypeItemRequest::getRoomTypeId))
                .toList();

        for (RoomTypeItemRequest item : roomTypeRequests) {
            RoomType roomType = roomTypeRepository.findByIdForUpdate(item.getRoomTypeId())
                    .orElseThrow(() -> new AppException(ErrorCode.ROOM_TYPE_NOT_FOUND));

            checkAvailabilityOrThrow(roomType, item.getQuantity(),
                    actualCheckIn, request.getCheckOut(), null);

            BigDecimal pricePerRoom = pricingService.calculateStayPricePerRoom(
                    roomType, actualCheckIn, request.getCheckOut());

            BigDecimal subtotal = pricePerRoom.multiply(BigDecimal.valueOf(item.getQuantity()));

            totalAmount = totalAmount.add(subtotal);
            totalCapacity += roomCapacity(roomType) * item.getQuantity();
            roomTypeWithPrices.add(new RoomTypeWithPrice(roomType, item.getQuantity(),
                    pricePerRoom, subtotal));
        }

        validateCapacity(request.getGuestCount(), totalCapacity);

        // Tạo Reservation — đi thẳng CONFIRMED, không qua DRAFT/Hold/Payment
        Reservation reservation = Reservation.builder()
                .reservationCode(generateCode())
                .customerProfile(customerProfile)
                .checkIn(actualCheckIn)
                .checkOut(request.getCheckOut())
                .totalAmount(totalAmount)
                .paymentPlan(PaymentPlan.PAY_AT_HOTEL)
                .requiredInitialPayment(BigDecimal.ZERO)
                .guestCount(request.getGuestCount())
                .note(request.getNote())
                .status(ReservationStatus.CONFIRMED)
                .build();
        reservationRepository.save(reservation);

        // Tạo ReservationRoomType + ReservationRoom (placeholder) — KHÔNG tạo RoomHold
        List<ReservationRoomTypeResponse> roomTypeResponses = new ArrayList<>();

        for (RoomTypeWithPrice item : roomTypeWithPrices) {
            ReservationRoomType rrt = ReservationRoomType.builder()
                    .reservation(reservation)
                    .roomType(item.roomType())
                    .quantity(item.quantity())
                    .roomPrice(item.price())
                    .subtotal(item.subtotal())
                    .build();
            reservationRoomTypeRepository.save(rrt);

            for (int i = 0; i < item.quantity(); i++) {
                ReservationRoom rr = ReservationRoom.builder()
                        .reservationRoomType(rrt)
                        .status(AssignStatus.PENDING_ASSIGN)
                        .build();
                reservationRoomRepository.save(rr);
            }

            roomTypeResponses.add(ReservationRoomTypeResponse.from(rrt));
        }

        log.info("Walk-in reservation created & confirmed: code={} total={}",
                reservation.getReservationCode(), totalAmount);
        return paymentRefundService.applyReservationRefundSummary(
                ReservationResponse.fromWithDetails(reservation, roomTypeResponses));
    }

    /**
     * Compatibility-v2 walk-in aggregate. Reservation, concrete room
     * assignments, guests, CHECKED_IN transition and optional CASH receipt are
     * committed together. A SEPAY session is intentionally created by the
     * controller only after this transaction returns and commits.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WalkInReservationResponse createWalkInCheckedIn(
            CreateWalkInCheckedInRequest request,
            User currentUser,
            String ipAddress) {
        if (currentUser == null
                || !List.of(UserType.ADMIN, UserType.STAFF).contains(currentUser.getType())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ Staff/Admin được tạo walk-in");
        }

        LocalDateTime actualCheckIn = LocalDateTime.now();
        validateInterval(actualCheckIn, request.getCheckOut());
        if (request.getRooms() == null || request.getRooms().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Phải chọn ít nhất một phòng vật lý");
        }

        List<AssignRoomRequest> assignments = request.getRooms().stream()
                .sorted(Comparator.comparing(AssignRoomRequest::getRoomId))
                .toList();
        Set<Long> requestedRoomIds = new HashSet<>();
        for (AssignRoomRequest assignment : assignments) {
            if (assignment.getRoomId() == null || !requestedRoomIds.add(assignment.getRoomId())) {
                throw new AppException(ErrorCode.INVALID_REQUEST,
                        "Danh sách walk-in có phòng trùng hoặc thiếu roomId");
            }
        }

        // Discover RoomType IDs without taking Room locks, then follow the
        // canonical lock order: RoomType ascending -> Room ascending.
        Set<Long> discoveredRoomTypeIds = new HashSet<>();
        for (Long roomId : requestedRoomIds.stream().sorted().toList()) {
            Room room = roomRepository.findById(roomId)
                    .orElseThrow(() -> new AppException(ErrorCode.ROOM_NOT_FOUND));
            if (room.getRoomType() == null || room.getRoomType().getId() == null) {
                throw new AppException(ErrorCode.ROOM_WRONG_TYPE);
            }
            discoveredRoomTypeIds.add(room.getRoomType().getId());
        }

        Map<Long, RoomType> lockedRoomTypes = new LinkedHashMap<>();
        for (Long roomTypeId : discoveredRoomTypeIds.stream().sorted().toList()) {
            RoomType roomType = roomTypeRepository.findByIdForUpdate(roomTypeId)
                    .orElseThrow(() -> new AppException(ErrorCode.ROOM_TYPE_NOT_FOUND));
            lockedRoomTypes.put(roomTypeId, roomType);
        }

        Map<Long, Room> lockedRooms = new LinkedHashMap<>();
        for (Long roomId : requestedRoomIds.stream().sorted().toList()) {
            Room room = roomRepository.findByIdForUpdate(roomId)
                    .orElseThrow(() -> new AppException(ErrorCode.ROOM_NOT_FOUND));
            Long roomTypeId = room.getRoomType() != null ? room.getRoomType().getId() : null;
            if (roomTypeId == null || !lockedRoomTypes.containsKey(roomTypeId)) {
                throw new AppException(ErrorCode.INVALID_REQUEST,
                        "Loại phòng đã thay đổi trong lúc tạo walk-in; vui lòng tải lại");
            }
            if (room.getStatus() != RoomStatus.AVAILABLE
                    || room.getCleaningStatus() != CleaningStatus.CLEAN
                    || !Boolean.TRUE.equals(room.getSellable())
                    || room.getDecommissionedAt() != null) {
                throw new AppException(ErrorCode.ROOM_NOT_AVAILABLE,
                        String.format("Phòng '%s' chưa sẵn sàng để nhận khách", room.getRoomName()));
            }
            if (reservationRoomRepository.existsByRoomIdAndStatusIn(
                    roomId, List.of(AssignStatus.ASSIGNED, AssignStatus.CHECKED_IN))) {
                throw new AppException(ErrorCode.ROOM_NOT_AVAILABLE,
                        String.format("Phòng '%s' đã được gán cho reservation khác", room.getRoomName()));
            }
            lockedRooms.put(roomId, room);
        }

        int submittedGuestCount = 0;
        Map<Long, List<AssignRoomRequest>> assignmentsByRoomType = new LinkedHashMap<>();
        for (AssignRoomRequest assignment : assignments) {
            List<GuestRequest> guests = assignment.getGuests();
            if (guests == null || guests.isEmpty()) {
                throw new AppException(ErrorCode.INVALID_REQUEST,
                        "Mỗi phòng walk-in phải có ít nhất một khách");
            }
            long primaryCount = guests.stream()
                    .filter(guest -> Boolean.TRUE.equals(guest.getIsPrimary()))
                    .count();
            if (primaryCount == 0) {
                throw new AppException(ErrorCode.GUEST_PRIMARY_REQUIRED);
            }
            if (primaryCount > 1) {
                throw new AppException(ErrorCode.GUEST_MULTIPLE_PRIMARY);
            }

            Room room = lockedRooms.get(assignment.getRoomId());
            if (guests.size() > roomCapacity(room.getRoomType())) {
                throw new AppException(ErrorCode.INVALID_REQUEST,
                        String.format("Số khách vượt sức chứa phòng '%s'", room.getRoomName()));
            }
            submittedGuestCount += guests.size();
            assignmentsByRoomType
                    .computeIfAbsent(room.getRoomType().getId(), ignored -> new ArrayList<>())
                    .add(assignment);
        }
        if (submittedGuestCount != request.getGuestCount()) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    String.format("Walk-in khai báo %d khách nhưng danh sách phòng có %d khách",
                            request.getGuestCount(), submittedGuestCount));
        }

        Map<Long, WalkInPriceOverrideRequest> priceOverrides = new LinkedHashMap<>();
        if (request.getPriceOverrides() != null) {
            for (WalkInPriceOverrideRequest override : request.getPriceOverrides()) {
                if (!discoveredRoomTypeIds.contains(override.getRoomTypeId())) {
                    throw new AppException(ErrorCode.INVALID_REQUEST,
                            "Chỉ được thay giá cho loại phòng đã chọn trong walk-in");
                }
                if (priceOverrides.putIfAbsent(override.getRoomTypeId(), override) != null) {
                    throw new AppException(ErrorCode.INVALID_REQUEST,
                            "Mỗi loại phòng chỉ được khai báo một mức giá thay thế");
                }
            }
        }

        BigDecimal totalAmount = BigDecimal.ZERO;
        Map<Long, BigDecimal> priceByRoomType = new LinkedHashMap<>();
        Map<Long, BigDecimal> systemPriceByRoomType = new LinkedHashMap<>();
        for (Map.Entry<Long, List<AssignRoomRequest>> entry : assignmentsByRoomType.entrySet()) {
            RoomType roomType = lockedRoomTypes.get(entry.getKey());
            int quantity = entry.getValue().size();
            checkAvailabilityOrThrow(roomType, quantity, actualCheckIn, request.getCheckOut(), null);
            BigDecimal systemPrice = pricingService.calculateStayPricePerRoom(
                    roomType, actualCheckIn, request.getCheckOut());
            systemPriceByRoomType.put(entry.getKey(), systemPrice);
            WalkInPriceOverrideRequest override = priceOverrides.get(entry.getKey());
            BigDecimal price = override != null ? override.getNewUnitPrice() : systemPrice;
            priceByRoomType.put(entry.getKey(), price);
            totalAmount = totalAmount.add(price.multiply(BigDecimal.valueOf(quantity)));
        }

        long remainingAmount = totalAmount.setScale(0, RoundingMode.CEILING).longValueExact();
        Long paymentAmount = null;
        if (request.getPaymentOption() != WalkInPaymentOption.UNPAID) {
            paymentAmount = request.getPaymentAmount() != null
                    ? request.getPaymentAmount() : remainingAmount;
            if (paymentAmount <= 0 || paymentAmount > remainingAmount) {
                throw new AppException(ErrorCode.INVALID_REQUEST,
                        String.format("Số tiền walk-in phải từ 1 đến %,d VND", remainingAmount));
            }
        }

        CustomerProfile customerProfile = resolveWalkInCustomerProfile(
                request.getCustomerProfileId(), request.getCustomer());
        Reservation reservation = Reservation.builder()
                .reservationCode(generateCode())
                .customerProfile(customerProfile)
                .checkIn(actualCheckIn)
                .checkOut(request.getCheckOut())
                .actualCheckIn(actualCheckIn)
                .totalAmount(totalAmount)
                .paymentPlan(PaymentPlan.PAY_AT_HOTEL)
                .requiredInitialPayment(BigDecimal.ZERO)
                .guestCount(request.getGuestCount())
                .note(request.getNote())
                .status(ReservationStatus.CHECKED_IN)
                .lastActivityAt(actualCheckIn)
                .build();
        reservation = reservationRepository.save(reservation);

        List<ReservationRoomTypeResponse> roomTypeResponses = new ArrayList<>();
        for (Map.Entry<Long, List<AssignRoomRequest>> entry : assignmentsByRoomType.entrySet()) {
            RoomType roomType = lockedRoomTypes.get(entry.getKey());
            BigDecimal price = priceByRoomType.get(entry.getKey());
            ReservationRoomType reservationRoomType = reservationRoomTypeRepository.save(
                    ReservationRoomType.builder()
                            .reservation(reservation)
                            .roomType(roomType)
                            .quantity(entry.getValue().size())
                            .roomPrice(price)
                            .subtotal(price.multiply(BigDecimal.valueOf(entry.getValue().size())))
                            .build());

            WalkInPriceOverrideRequest override = priceOverrides.get(entry.getKey());
            BigDecimal systemPrice = systemPriceByRoomType.get(entry.getKey());
            if (override != null && systemPrice.compareTo(price) != 0) {
                auditService.record(
                        reservation,
                        "RESERVATION_ROOM_TYPE",
                        String.valueOf(reservationRoomType.getId()),
                        ReservationAuditAction.PRICE_OVERRIDDEN,
                        "Thay giá walk-in cho " + roomType.getTypeName(),
                        Map.of(
                                "unitPrice", systemPrice,
                                "subtotal", systemPrice.multiply(BigDecimal.valueOf(entry.getValue().size()))),
                        Map.of(
                                "unitPrice", price,
                                "subtotal", price.multiply(BigDecimal.valueOf(entry.getValue().size()))),
                        Map.of(
                                "roomTypeId", roomType.getId(),
                                "roomTypeName", roomType.getTypeName(),
                                "quantity", entry.getValue().size(),
                                "reasonCode", override.getReasonCode().trim(),
                                "note", override.getNote().trim()),
                        "walk-in:" + reservation.getId(),
                        "PRICE_OVERRIDDEN:" + reservation.getId() + ":" + roomType.getId());
            }

            for (AssignRoomRequest assignment : entry.getValue()) {
                Room room = lockedRooms.get(assignment.getRoomId());
                ensureRoomHasNoOverlappingAssignment(room, reservation);
                ReservationRoom reservationRoom = reservationRoomRepository.save(
                        ReservationRoom.builder()
                                .reservationRoomType(reservationRoomType)
                                .room(room)
                                .assignedBy(currentUser)
                                .status(AssignStatus.CHECKED_IN)
                                .build());
                for (GuestRequest guestRequest : assignment.getGuests()) {
                    guestRepository.save(Guest.builder()
                            .reservationRoom(reservationRoom)
                            .fullName(guestRequest.getFullName())
                            .phone(guestRequest.getPhone())
                            .email(guestRequest.getEmail())
                            .idCardNumber(guestRequest.getIdCardNumber())
                            .idCardType(guestRequest.getIdCardType())
                            .dateOfBirth(guestRequest.getDateOfBirth())
                            .nationality(guestRequest.getNationality())
                            .isPrimary(guestRequest.getIsPrimary())
                            .build());
                }
                room.setStatus(RoomStatus.CHECKED_IN);
                roomRepository.save(room);
            }
            roomTypeResponses.add(ReservationRoomTypeResponse.from(reservationRoomType));
        }

        PaymentResponse paymentResponse = null;
        String paymentCreationStatus = "NOT_REQUESTED";
        if (request.getPaymentOption() == WalkInPaymentOption.CASH) {
            PaymentTransaction cashPayment = paymentTransactionRepository.saveAndFlush(
                    PaymentTransaction.builder()
                            .reservation(reservation)
                            .txnRef(VNPayUtil.generateTxnRef("CASH-WALKIN-" + reservation.getId()))
                            .provider(PaymentProvider.CASH)
                            .purpose(PaymentPurpose.WALK_IN)
                            .status(PaymentStatus.SUCCESS)
                            .amount(paymentAmount)
                            .expectedAmount(paymentAmount)
                            .receivedAmount(paymentAmount)
                            .acceptedAmount(paymentAmount)
                            .refundRequiredAmount(0L)
                            .currency("VND")
                            .orderInfo("Thu tiền mặt walk-in " + reservation.getReservationCode())
                            .ipAddress(ipAddress)
                            .paidAt(actualCheckIn)
                            .paidAtUtc(actualCheckIn.atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant())
                            .message("Thanh toán tiền mặt walk-in thành công")
                            .build());
            paymentResponse = PaymentResponse.from(cashPayment);
            paymentCreationStatus = "SUCCESS";
            auditService.record(reservation, ReservationAuditAction.PAYMENT_RECEIVED,
                    "Thu tiền mặt walk-in " + paymentAmount + " VND");
        } else if (request.getPaymentOption() == WalkInPaymentOption.SEPAY) {
            paymentCreationStatus = "NOT_CREATED";
        }

        auditService.record(reservation, ReservationAuditAction.CHECK_IN,
                "Tạo walk-in và check-in nguyên tử " + assignments.size()
                        + " phòng, " + submittedGuestCount + " khách");
        ReservationResponse reservationResponse = paymentRefundService.applyReservationRefundSummary(
                ReservationResponse.fromWithDetails(reservation, roomTypeResponses));
        return WalkInReservationResponse.builder()
                .reservationCreated(true)
                .reservation(reservationResponse)
                .paymentCreationStatus(paymentCreationStatus)
                .paymentInstructions(paymentResponse)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lấy đặt phòng
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    @Transactional(readOnly = true)
    public ReservationResponse getReservation(Long reservationId, User currentUser, String guestToken) {
        Reservation reservation = reservationRepository.findByIdWithDetails(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        ensureCanAccessReservation(currentUser, reservation, guestToken);

        List<ReservationRoomTypeResponse> roomTypeResponses = reservation.getRoomTypes().stream()
                .map(rrt -> {
                    ReservationRoomTypeResponse res = ReservationRoomTypeResponse.from(rrt);
                    if (rrt.getRoomHold() != null) {
                        res.setRoomHold(RoomHoldResponse.from(rrt.getRoomHold()));
                    }
                    return res;
                }).toList();

        return paymentRefundService.applyReservationRefundSummary(
                ReservationResponse.fromWithDetails(reservation, roomTypeResponses));
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationResponse lookupGuestReservation(String guestToken) {
        if (!hasText(guestToken)) {
            throw new AppException(ErrorCode.RESERVATION_NOT_OWNER);
        }

        Reservation reservation = reservationRepository.findByGuestTokenWithDetails(guestToken)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_OWNER));

        List<ReservationRoomTypeResponse> roomTypeResponses = reservation.getRoomTypes().stream()
                .map(rrt -> {
                    ReservationRoomTypeResponse res = ReservationRoomTypeResponse.from(rrt);
                    if (rrt.getRoomHold() != null) {
                        res.setRoomHold(RoomHoldResponse.from(rrt.getRoomHold()));
                    }
                    return res;
                }).toList();

        return paymentRefundService.applyReservationRefundSummary(
                ReservationResponse.fromWithDetails(reservation, roomTypeResponses));
    }

    @Override
  @Transactional(rollbackFor = Exception.class)
  public List<ReservationResponse> getMyReservations(User currentUser) {
      if (currentUser == null) {
          throw new AppException(ErrorCode.CUSTOMER_NOT_FOUND);
      }

      // Đồng bộ các reservation guest cũ có cùng email vào hồ sơ đã
      // xác thực. Việc này xử lý cả dữ liệu được tạo trước khi frontend
      // khôi phục access token đúng cách qua refresh cookie HttpOnly.
      customerProfileClaimService.claimForVerifiedUser(currentUser.getId());

      return reservationRepository.findByLinkedUserIdOrderByCreatedAtDesc(currentUser.getId())
            .stream()
            .map(reservation -> {
                List<ReservationRoomTypeResponse> roomTypeResponses = reservation.getRoomTypes().stream()
                        .map(rrt -> {
                            ReservationRoomTypeResponse res = ReservationRoomTypeResponse.from(rrt);
                            if (rrt.getRoomHold() != null) {
                                res.setRoomHold(RoomHoldResponse.from(rrt.getRoomHold()));
                            }
                            return res;
                        }).toList();
                return paymentRefundService.applyReservationRefundSummary(
                        ReservationResponse.fromWithDetails(reservation, roomTypeResponses));
            })
            .toList();
}
    // ─────────────────────────────────────────────────────────────────────────
    // Hủy đặt phòng
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationResponse cancelReservation(
            Long reservationId,
            CancelReservationRequest request,
            User currentUser,
            String guestToken) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
        .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        ensureCanAccessReservation(currentUser, reservation, guestToken);

        ensureNoPendingCancellationRefund(reservation);

        if (!List.of(ReservationStatus.DRAFT, ReservationStatus.CONFIRMED)
                .contains(reservation.getStatus())) {
            throw new AppException(ErrorCode.RESERVATION_CANNOT_CANCEL);
        }

        if (getNetPaidAmount(reservationId) > 0L && request.getRefundRecipient() == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Vui lòng cung cấp tài khoản ngân hàng nhận hoàn tiền khi gửi yêu cầu hủy");
        }

        reservation.setStatusBeforeCancellation(reservation.getStatus());
        reservation.setStatus(ReservationStatus.CANCELLATION_PENDING);
        reservation.setCancellationReason(request.getCancellationReason());
        reservationRepository.save(reservation);
        if (request.getRefundRecipient() != null) {
            refundRecipientService.submit(
                    reservationId, request.getRefundRecipient(), currentUser, guestToken);
        }
        auditService.record(reservation, ReservationAuditAction.CANCEL, "Khách gửi yêu cầu hủy");

        log.info("Cancellation requested: reservationId={} previousStatus={}",
                reservationId, reservation.getStatusBeforeCancellation());
        return paymentRefundService.applyReservationRefundSummary(ReservationResponse.from(reservation));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationResponse approveCancellation(Long reservationId, CancelReservationRequest request) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        if (reservation.getStatus() != ReservationStatus.CANCELLATION_PENDING) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Reservation không có yêu cầu hủy đang chờ duyệt");
        }
        if (ReservationCancellationReasonCode.isRefundPending(
                reservation.getCancellationReasonCode())) {
            return paymentRefundService.applyReservationRefundSummary(
                    ReservationResponse.from(reservation));
        }

        // Chỉ hoàn số tiền khách sạn còn thực giữ; các khoản đã hoàn thành phải
        // được trừ và các khoản đang chờ vẫn được sổ refund giữ hạn mức.
        long paidAmount = getNetPaidAmount(reservationId);
        long alreadyPendingRefund = paymentRefundService.getOutstandingReservedRefundAmount(reservationId);
        CancellationAmounts amounts = resolveCancellationAmounts(
                request, paidAmount, reservation.getCancellationReason());
        RefundChannel refundChannel = amounts.refundAmount() > 0L
                ? requireOnlineCancellationRefundChannel(request.getRefundChannel()) : null;
        long refundableAmount = amounts.refundAmount() + alreadyPendingRefund;

        if (amounts.refundAmount() > 0L) {
            paymentRefundService.requestCancellationRefund(
                    reservationId,
                    amounts.refundAmount(),
                    refundChannel,
                    "Staff/Admin duyệt hủy reservation " + reservation.getReservationCode(),
                    currentOperator(),
                    amounts.originalAmount(),
                    amounts.penaltyAmount(),
                    amounts.policyApplied(),
                    amounts.policyNote());
        }

        reservation.setCancellationFee(BigDecimal.valueOf(amounts.penaltyAmount()));
        reservation.setRefundableAmount(BigDecimal.valueOf(refundableAmount));
        boolean deferFinalStatus = amounts.refundAmount() > 0L;
        if (deferFinalStatus) {
            reservation.setCancellationReasonCode(ReservationCancellationReasonCode.pending(
                    ReservationCancellationReasonCode.CANCELLATION_APPROVED));
        } else {
            releaseReservationHolds(reservation);
            reservation.setStatusBeforeCancellation(null);
            reservation.setStatus(ReservationStatus.CANCELLED);
        }
        reservationRepository.save(reservation);
        auditService.record(
                reservation,
                "RESERVATION",
                String.valueOf(reservationId),
                ReservationAuditAction.CANCEL,
                deferFinalStatus
                        ? "Đã duyệt hủy, phí phạt " + amounts.penaltyAmount()
                        + " VND, tạo refund " + amounts.refundAmount() + " VND qua "
                        + refundChannel + "; reservation giữ nguyên đến khi refund hoàn tất"
                        : "Đã duyệt hủy, phí phạt " + amounts.penaltyAmount()
                        + " VND, không còn số tiền phải hoàn",
                Map.of("status", ReservationStatus.CANCELLATION_PENDING.name()),
                Map.of("status", deferFinalStatus
                        ? ReservationStatus.CANCELLATION_PENDING.name()
                        : ReservationStatus.CANCELLED.name()),
                cancellationAuditDetail(amounts, refundChannel),
                UUID.randomUUID().toString(),
                null);

        log.info("Cancellation approved: reservationId={} paid={} fee={} refund={}",
                reservationId, paidAmount, amounts.penaltyAmount(), amounts.refundAmount());
        return paymentRefundService.applyReservationRefundSummary(ReservationResponse.from(reservation));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationResponse rejectCancellation(Long reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        if (reservation.getStatus() != ReservationStatus.CANCELLATION_PENDING) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Reservation không có yêu cầu hủy đang chờ duyệt");
        }
        ensureNoPendingCancellationRefund(reservation);
        ReservationStatus restoredStatus = reservation.getStatusBeforeCancellation();
        if (!List.of(ReservationStatus.DRAFT, ReservationStatus.CONFIRMED).contains(restoredStatus)) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Không xác định được trạng thái cần khôi phục");
        }
        reservation.setStatus(restoredStatus);
        reservation.setStatusBeforeCancellation(null);
        reservation.setCancellationReason(null);
        reservation.setCancellationReasonCode(null);
        reservation.setCancellationFee(BigDecimal.ZERO);
        reservation.setRefundableAmount(BigDecimal.ZERO);
        reservationRepository.save(reservation);
        auditService.record(reservation, ReservationAuditAction.CANCEL, "Từ chối yêu cầu hủy");
        log.info("Cancellation rejected: reservationId={} restoredStatus={}", reservationId, restoredStatus);
        return paymentRefundService.applyReservationRefundSummary(ReservationResponse.from(reservation));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationResponse cancelByStaff(Long reservationId, CancelReservationRequest request) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        ReservationStatus oldStatus = reservation.getStatus();
        if (!List.of(ReservationStatus.DRAFT, ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLATION_PENDING).contains(reservation.getStatus())) {
            throw new AppException(ErrorCode.RESERVATION_CANNOT_CANCEL);
        }
        if (ReservationCancellationReasonCode.isRefundPending(
                reservation.getCancellationReasonCode())) {
            return paymentRefundService.applyReservationRefundSummary(
                    ReservationResponse.from(reservation));
        }

        // Không dùng tổng SUCCESS thô vì có thể đã tồn tại refund ledger trước đó.
        long paidAmount = getNetPaidAmount(reservationId);
        long alreadyPendingRefund = paymentRefundService.getOutstandingReservedRefundAmount(reservationId);
        CancellationAmounts amounts = resolveCancellationAmounts(
                request, paidAmount, reservation.getCancellationReason());
        RefundChannel refundChannel = amounts.refundAmount() > 0L
                ? requireStaffRefundChannel(request.getRefundChannel()) : null;
        long refundableAmount = amounts.refundAmount() + alreadyPendingRefund;
        if (amounts.refundAmount() > 0L) {
            paymentRefundService.requestCancellationRefund(
                    reservationId,
                    amounts.refundAmount(),
                    refundChannel,
                    "Staff/Admin hủy reservation " + reservation.getReservationCode(),
                    currentOperator(),
                    amounts.originalAmount(),
                    amounts.penaltyAmount(),
                    amounts.policyApplied(),
                    amounts.policyNote());
        }

        reservation.setCancellationReason(hasText(request.getCancellationReason())
                ? request.getCancellationReason().trim()
                : "Staff/Admin chủ động hủy đơn");
        reservation.setCancellationFee(BigDecimal.valueOf(amounts.penaltyAmount()));
        reservation.setRefundableAmount(BigDecimal.valueOf(refundableAmount));
        boolean deferFinalStatus = amounts.refundAmount() > 0L;
        if (deferFinalStatus) {
            if (reservation.getStatusBeforeCancellation() == null) {
                reservation.setStatusBeforeCancellation(reservation.getStatus());
            }
            reservation.setCancellationReasonCode(ReservationCancellationReasonCode.pending(
                    ReservationCancellationReasonCode.STAFF_CANCELLED));
        } else {
            releaseReservationHolds(reservation);
            reservation.setStatusBeforeCancellation(null);
            reservation.setStatus(ReservationStatus.CANCELLED);
        }
        reservationRepository.save(reservation);
        auditService.record(
                reservation,
                "RESERVATION",
                String.valueOf(reservationId),
                ReservationAuditAction.CANCEL,
                deferFinalStatus
                        ? "Đã ghi phí phạt " + amounts.penaltyAmount() + " VND và tạo refund "
                        + amounts.refundAmount() + " VND qua " + refundChannel
                        + "; chưa chốt hủy reservation khi refund chưa hoàn tất"
                        : "Nhân viên hủy, phí phạt " + amounts.penaltyAmount()
                        + " VND, không còn số tiền phải hoàn",
                Map.of("status", oldStatus.name()),
                Map.of("status", deferFinalStatus
                        ? reservation.getStatus().name()
                        : ReservationStatus.CANCELLED.name()),
                cancellationAuditDetail(amounts, refundChannel),
                UUID.randomUUID().toString(),
                null);

        log.info("Staff cancellation: reservationId={} refundRequested={} refundAmount={}",
                reservationId, amounts.refundAmount() > 0L, amounts.refundAmount());
        return paymentRefundService.applyReservationRefundSummary(ReservationResponse.from(reservation));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Confirm đặt phòng
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationResponse confirmReservation(Long reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
        .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));

        ensureNoPendingCancellationRefund(reservation);

        if (reservation.getStatus() != ReservationStatus.DRAFT) {
            throw new AppException(ErrorCode.RESERVATION_CANNOT_CONFIRM);
        }



        long requiredDeposit = getRequiredDepositAmount(reservation);
        // Dùng số tiền ròng trong ledger thay vì chỉ đếm payment SUCCESS.
        // Payment SePay có phần thừa sẽ mang REFUND_PENDING nhưng phần cọc
        // hợp lệ vẫn phải được phép xác nhận reservation.
        if (getNetPaidAmount(reservationId) < requiredDeposit) {
            throw new AppException(ErrorCode.RESERVATION_PAYMENT_REQUIRED,
                    String.format("Cần thanh toán tối thiểu %,d VND để xác nhận đặt phòng", requiredDeposit));
        }
        

        // Kiểm tra hold chưa expired
        // Kiểm tra hold — chấp nhận cả ACTIVE (chưa qua convertHoldsAfterPayment)
        // và CONVERTED (đã qua convertHoldsAfterPayment từ IPN)
        reservation.getRoomTypes().forEach(rrt -> {
            RoomHold hold = rrt.getRoomHold();
            if (hold == null) {
                throw new AppException(ErrorCode.ROOM_HOLD_EXPIRED);
            }
            if (hold.getStatus() == HoldStatus.ACTIVE) {
                if (hold.getExpiresAt().isBefore(LocalDateTime.now())) {
                    throw new AppException(ErrorCode.ROOM_HOLD_EXPIRED);
                }
                hold.setStatus(HoldStatus.CONVERTED);
                roomHoldRepository.save(hold);
            } else if (hold.getStatus() != HoldStatus.CONVERTED) {
                // EXPIRED hoặc RELEASED → không hợp lệ
                throw new AppException(ErrorCode.ROOM_HOLD_EXPIRED);
            }
            // Nếu đã CONVERTED rồi (do IPN xử lý trước) → bỏ qua, không làm gì thêm
        });

        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(reservation);

        auditService.record(reservation, ReservationAuditAction.CONFIRM,
                "Xác nhận đơn sau khi kiểm tra tiền cọc");

        log.info("Reservation confirmed: id={}", reservationId);
        return paymentRefundService.applyReservationRefundSummary(ReservationResponse.from(reservation));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Check availability
    // ─────────────────────────────────────────────────────────────────────────
    @Override
@Transactional(readOnly = true)
public List<AvailabilityResponse> checkAvailability(LocalDateTime checkIn, LocalDateTime checkOut) {
    validateFutureDates(checkIn, checkOut);
    LocalDateTime now = LocalDateTime.now();
    long hours = pricingService.billableHours(checkIn, checkOut);
 
    return roomTypeRepository.findAll().stream().map(rt -> {
        int total     = roomTypeRepository.countAvailableRoomsByType(rt.getId());
        int booked    = reservationRoomTypeRepository.countBookedQuantity(rt.getId(), checkIn, checkOut);
        int held      = roomHoldRepository.countActiveHeldQuantity(rt.getId(), checkIn, checkOut, now);
        int available = Math.max(0, total - booked - held);
 
        return AvailabilityResponse.builder()
                .roomTypeId(rt.getId())
                .roomTypeName(rt.getTypeName())
                .roomTypeNameEn(rt.getTypeNameEn())
                .description(rt.getDescription())
                .descriptionEn(rt.getDescriptionEn())
                .pricePerHour(rt.getPrice())
                .estimatedPricePerRoom(pricingService.calculateStayPricePerRoom(rt, checkIn, checkOut))
                .maxGuestsPerRoom(roomCapacity(rt))
                .imageUrl(rt.getImageUrl())
                .checkIn(checkIn)
                .checkOut(checkOut)
                .totalHours(hours)
                .totalRooms(total)
                .bookedRooms(booked)
                .heldRooms(held)
                .availableRooms(available)
                .build();
    }).toList();
}

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationResponse checkIn(Long reservationId, List<AssignRoomRequest> requests) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));

        ensureNoPendingCancellationRefund(reservation);

        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new AppException(ErrorCode.RESERVATION_CANNOT_CHECKIN);
        }
        validateCheckInTime(reservation);
        if (requests == null || requests.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Phải chọn phòng để check-in");
        }

        // Lấy tất cả ReservationRoom cần assign
        List<ReservationRoom> reservationRooms = 
                reservationRoomRepository.findAllByReservationId(reservationId);

        if (requests.size() != reservationRooms.size()) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Số phòng gán không khớp với số phòng đặt");
        }

        int submittedGuestCount = requests.stream()
                .mapToInt(request -> request.getGuests() == null ? 0 : request.getGuests().size())
                .sum();
        if (reservation.getGuestCount() != null && submittedGuestCount != reservation.getGuestCount()) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    String.format("Reservation khai báo %d khách nhưng danh sách check-in có %d khách",
                            reservation.getGuestCount(), submittedGuestCount));
        }

        // Giữ thứ tự khóa cố định cho mọi luồng gán phòng: RoomType tăng dần
        // trước, sau đó mới đến Room tăng dần. Điều này tránh deadlock khi hai
        // reservation check-in đồng thời với nhiều loại phòng giao nhau.
        List<Long> reservationRoomTypeIds = reservationRooms.stream()
                .map(ReservationRoom::getReservationRoomType)
                .filter(Objects::nonNull)
                .map(ReservationRoomType::getRoomType)
                .filter(Objects::nonNull)
                .map(RoomType::getId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        for (Long roomTypeId : reservationRoomTypeIds) {
            roomTypeRepository.findByIdForUpdate(roomTypeId)
                    .orElseThrow(() -> new AppException(ErrorCode.ROOM_TYPE_NOT_FOUND));
        }

        List<AssignRoomRequest> sortedRequests = requests.stream()
                .sorted(Comparator.comparing(AssignRoomRequest::getRoomId))
                .toList();
        Set<Long> usedReservationRoomIds = new HashSet<>();
        Set<Long> usedRoomIds = new HashSet<>();

        // Gán phòng + CHECKED_IN từng ReservationRoom
        for (AssignRoomRequest req : sortedRequests) {
            Room room = roomRepository.findByIdForUpdate(req.getRoomId())
                    .orElseThrow(() -> new AppException(ErrorCode.ROOM_NOT_FOUND));

            if (room.getStatus() != RoomStatus.AVAILABLE
                    || room.getCleaningStatus() != CleaningStatus.CLEAN
                    || !Boolean.TRUE.equals(room.getSellable())
                    || room.getDecommissionedAt() != null) {
                throw new AppException(ErrorCode.ROOM_NOT_AVAILABLE,
                        String.format("Phòng '%s' chưa sẵn sàng để nhận khách", room.getRoomName()));
            }

            if (!usedRoomIds.add(room.getId())) {
                throw new AppException(ErrorCode.ROOM_ALREADY_ASSIGNED,
                        String.format("Phòng '%s' bị gửi trùng trong yêu cầu check-in", room.getRoomName()));
            }

            ReservationRoom rr = resolveReservationRoomForCheckIn(
                    req, reservationRooms, usedReservationRoomIds, room);

            // Kiểm tra đúng room type
            Long expectedRoomTypeId = rr.getReservationRoomType().getRoomType().getId();
            if (!room.getRoomType().getId().equals(expectedRoomTypeId)) {
                throw new AppException(ErrorCode.ROOM_WRONG_TYPE);
            }

            // Kiểm tra phòng chưa bị assign cho reservation khác đang CHECKED_IN
            boolean isOccupied = reservationRoomRepository.existsByRoomIdAndStatus(room.getId(), AssignStatus.CHECKED_IN);
            if (isOccupied) {
                throw new AppException(ErrorCode.ROOM_NOT_AVAILABLE,
                        String.format("Phòng '%s' đang có khách", room.getRoomName()));
            }

            ensureRoomHasNoOverlappingAssignment(room, reservation);

            long primaryCount = req.getGuests().stream()
            .filter(g -> Boolean.TRUE.equals(g.getIsPrimary()))
            .count();
            if (primaryCount == 0) {
                throw new AppException(ErrorCode.GUEST_PRIMARY_REQUIRED);
            }
            if (primaryCount > 1) {
                throw new AppException(ErrorCode.GUEST_MULTIPLE_PRIMARY);
            }

            //nv check lai de xem co the tao nhieu guest
            List<Guest> existingGuests = guestRepository.findByReservationRoomId(rr.getId());
            if (existingGuests.isEmpty()) {
                for (GuestRequest g : req.getGuests()) {
                    Guest guest = Guest.builder()
                            .reservationRoom(rr)
                            .fullName(g.getFullName())
                            .phone(g.getPhone())
                            .email(g.getEmail())
                            .idCardNumber(g.getIdCardNumber())
                            .idCardType(g.getIdCardType())
                            .dateOfBirth(g.getDateOfBirth())
                            .nationality(g.getNationality())
                            .isPrimary(g.getIsPrimary())
                            .build();
                    guestRepository.save(guest);
                }
            } else {
                log.info("ReservationRoom {} đã có guest, bỏ qua tạo mới", rr.getId());
            }
            rr.setRoom(room);
            rr.setStatus(AssignStatus.CHECKED_IN);
            reservationRoomRepository.save(rr);

            room.setStatus(RoomStatus.CHECKED_IN);
            roomRepository.save(room);
        }

        reservation.setStatus(ReservationStatus.CHECKED_IN);
        reservation.setActualCheckIn(LocalDateTime.now());
        reservationRepository.save(reservation);

        auditService.record(reservation, ReservationAuditAction.CHECK_IN,
                "Check-in " + requests.size() + " phòng, " + submittedGuestCount + " khách");

        log.info("Reservation checked-in: id={}", reservationId);
        return paymentRefundService.applyReservationRefundSummary(ReservationResponse.from(reservation));
    }

    private ReservationRoom resolveReservationRoomForCheckIn(
            AssignRoomRequest request,
            List<ReservationRoom> reservationRooms,
            Set<Long> usedReservationRoomIds,
            Room room) {
        // bỏ đoạn code cũ vì không còn dùng reservationRoomId nữa
        // if (request.getReservationRoomId() != null) {
        //     ReservationRoom rr = reservationRooms.stream()
        //             .filter(r -> r.getId().equals(request.getReservationRoomId()))
        //             .findFirst()
        //             .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_ROOM_NOT_FOUND));

        //     if (!usedReservationRoomIds.add(rr.getId())) {
        //         throw new AppException(ErrorCode.RESERVATION_ROOM_NOT_FOUND,
        //                 "reservationRoomId bị gửi trùng trong yêu cầu check-in");
        //     }
        //     return rr;
        // }

        Long roomTypeId = room.getRoomType().getId();

        ReservationRoom rr = reservationRooms.stream()
                .filter(r -> !usedReservationRoomIds.contains(r.getId()))
                .filter(r -> r.getRoom() != null && r.getRoom().getId().equals(room.getId()))
                .findFirst()
                .orElseGet(() -> reservationRooms.stream()
                        .filter(r -> !usedReservationRoomIds.contains(r.getId()))
                        .filter(r -> r.getRoom() == null)
                        .filter(r -> r.getReservationRoomType().getRoomType().getId().equals(roomTypeId))
                        .findFirst()
                        .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_ROOM_NOT_FOUND,
                                String.format("Không còn slot đặt phòng phù hợp cho phòng '%s'",
                                        room.getRoomName()))));

        usedReservationRoomIds.add(rr.getId());
        return rr;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationResponse checkOut(Long reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        String correlationId = UUID.randomUUID().toString();

        if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            throw new AppException(ErrorCode.RESERVATION_CANNOT_CHECKOUT);
        }
        ensureNoPendingSettlementPayment(reservationId);
        LocalDateTime now = LocalDateTime.now();
        applyEarlyCheckoutAdjustment(reservation, now);
        applyLateCheckoutFee(reservation, now);
        // Bắt buộc đã thanh toán đủ tổng tiền sau khi đã cộng phụ phí trả muộn.
        long totalAmount = reservation.getTotalAmount().longValue();
        long outstandingRefund = paymentRefundService.getOutstandingReservedRefundAmount(reservationId);
        if (outstandingRefund > 0L) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    String.format("Còn %,d VND đang chờ hoàn tiền thực tế trước khi check-out",
                            outstandingRefund));
        }
        // Sau đối soát, giao dịch có thể là REFUND_PENDING. Phần tiền không hoàn
        // vẫn là tiền khách đã thanh toán hợp lệ để hoàn tất check-out.
        long paidAmount = getNetPaidAmount(reservationId);
        long uncoveredRequiredRefund = paymentRefundService
                .getUncoveredRequiredRefundAmount(reservationId);
        if (uncoveredRequiredRefund > 0L) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    String.format("Còn %,d VND nghĩa vụ hoàn chưa được tạo lại trước khi check-out",
                            uncoveredRequiredRefund));
        }
        if (paidAmount < totalAmount) {
            throw new AppException(ErrorCode.RESERVATION_PAYMENT_REQUIRED,
                    String.format("Còn phải thanh toán %,d VND", totalAmount - paidAmount));
        }
        long refundableAmount = Math.max(0L, paidAmount - totalAmount);
        if (refundableAmount > 0L) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    String.format("Phải hoàn %,d VND tiền thừa trước khi check-out", refundableAmount));
        }
        CheckoutReconciliationResponse reconciliation = buildCheckoutReconciliation(
                reservation, now);
        if (reconciliation.getStatus()
                != com.hotel.backend.constant.CheckoutReconciliationStatus.MATCHED) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Đối soát checkout chưa khớp: "
                            + String.join("; ", reconciliation.getBlockingReasons()));
        }

        // Giải phóng phòng → CHECKED_OUT
        List<ReservationRoom> rooms = 
                reservationRoomRepository.findAllByReservationId(reservationId);

        rooms.forEach(rr -> {
            rr.setStatus(AssignStatus.CHECKED_OUT);
            reservationRoomRepository.save(rr);
            Room room = rr.getRoom();
            if (room != null) {
                room.setStatus(RoomStatus.AVAILABLE);
                room.setCleaningStatus(CleaningStatus.DIRTY); // cần dọn trước khi cho khách tiếp theo
                roomRepository.save(room);
            }

            // Giữ liên kết với ReservationRoom để lịch sử lưu trú vẫn truy xuất được
            // trong thời gian retention. Trạng thái phòng vật lý đã được giải phóng ở trên.
            List<Guest> guests = guestRepository.findByReservationRoomId(rr.getId());
            guests.forEach(guest -> {
                guest.setCheckedOutAt(now);
                guestRepository.save(guest);
            });

        });

        reservation.setStatus(ReservationStatus.CHECKED_OUT);
        reservation.setActualCheckOut(now);
        reservationRepository.save(reservation);
        createInvoiceSnapshot(reservation);
        Map<String, Object> reconciliationDetail = new LinkedHashMap<>();
        reconciliationDetail.put("requiredAmount", reconciliation.getRequiredAmount());
        reconciliationDetail.put("acceptedAmount", reconciliation.getAcceptedAmount());
        reconciliationDetail.put("reservedRefundAmount", reconciliation.getReservedRefundAmount());
        reconciliationDetail.put("outstandingAmount", reconciliation.getOutstandingAmount());
        reconciliationDetail.put("deltaAmount", reconciliation.getDeltaAmount());
        reconciliationDetail.put("status", reconciliation.getStatus().name());
        auditService.record(reservation, "RESERVATION", String.valueOf(reservationId),
                ReservationAuditAction.CHECKOUT_RECONCILIATION_PASSED,
                "Đối soát khớp trong transaction checkout",
                null, null, reconciliationDetail, correlationId,
                "CHECKOUT_RECONCILIATION_PASSED:" + reservationId);
        auditService.record(reservation, "RESERVATION", String.valueOf(reservationId),
                ReservationAuditAction.CHECK_OUT,
                "Checkout lúc " + now + ", tổng quyết toán "
                        + reservation.getTotalAmount().toPlainString() + " VND",
                Map.of("status", ReservationStatus.CHECKED_IN.name()),
                Map.of("status", ReservationStatus.CHECKED_OUT.name()),
                Map.of("actualCheckOut", now.toString()),
                correlationId,
                "CHECK_OUT:" + reservationId);

        log.info("Reservation checked-out: id={}", reservationId);
        return paymentRefundService.applyReservationRefundSummary(ReservationResponse.from(reservation));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationResponse rejectConfirmation(
            Long reservationId,
            RejectReservationRequest request) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        ensureNoPendingCancellationRefund(reservation);
        if (reservation.getStatus() != ReservationStatus.DRAFT) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ reservation đang chờ xác nhận mới có thể bị từ chối");
        }
        String reason = request != null && request.getReason() != null
                ? request.getReason().trim() : "";
        if (reason.isBlank()) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Lý do từ chối đặt phòng không được để trống");
        }

        long acceptedPaid = getNetPaidAmount(reservationId);
        long existingRequiredRefund = paymentRefundService
                .getOutstandingReservedRefundAmount(reservationId);
        RefundChannel refundChannel = acceptedPaid > 0L
                ? requireStaffRefundChannel(request.getRefundChannel()) : null;
        if (acceptedPaid > 0L) {
            paymentRefundService.requestCancellationRefund(
                    reservationId,
                    acceptedPaid,
                    refundChannel,
                    "Staff/Admin từ chối booking " + reservation.getReservationCode()
                            + ": " + reason,
                    currentOperator());
        }
        reservation.setCancellationReason(reason);
        reservation.setCancellationReasonCode("STAFF_REJECTED");
        reservation.setCancellationFee(BigDecimal.ZERO);
        reservation.setRefundableAmount(BigDecimal.valueOf(acceptedPaid + existingRequiredRefund));
        boolean deferFinalStatus = acceptedPaid > 0L;
        if (deferFinalStatus) {
            reservation.setStatusBeforeCancellation(reservation.getStatus());
            reservation.setCancellationReasonCode(ReservationCancellationReasonCode.pending(
                    ReservationCancellationReasonCode.STAFF_REJECTED));
        } else {
            releaseReservationHolds(reservation);
            reservation.setStatusBeforeCancellation(null);
            reservation.setStatus(ReservationStatus.CANCELLED);
        }
        reservationRepository.save(reservation);
        auditService.record(reservation, ReservationAuditAction.CANCEL,
                deferFinalStatus
                        ? "Staff/Admin từ chối booking: " + reason
                        + "; refund " + refundChannel + " đang chờ hoàn tất, chưa chốt trạng thái reservation"
                        : "Staff/Admin từ chối booking: " + reason
                        + "; hoàn " + (acceptedPaid + existingRequiredRefund) + " VND");
        return paymentRefundService.applyReservationRefundSummary(ReservationResponse.from(reservation));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationResponse updateCheckoutAdditionalFee(Long reservationId, CheckoutRefundRequest request) {
        if (request == null || !hasText(request.getReasonCode()) || !hasText(request.getReason())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Điều chỉnh phụ phí bắt buộc có mã lý do và ghi chú");
        }
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ có thể điều chỉnh phụ phí khi reservation đang CHECKED_IN");
        }
        ensureNoPendingSettlementPayment(reservationId);
        applyEarlyCheckoutAdjustment(reservation, LocalDateTime.now());
        applyLateCheckoutFee(reservation, LocalDateTime.now());
        long additionalFee = request.getAdditionalFee();
        BigDecimal currentAdditionalFee = reservation.getCheckoutAdditionalFee() != null
                ? reservation.getCheckoutAdditionalFee() : BigDecimal.ZERO;
        BigDecimal oldTotal = reservation.getTotalAmount();
        reservation.setTotalAmount(reservation.getTotalAmount()
                .subtract(currentAdditionalFee)
                .add(BigDecimal.valueOf(additionalFee)));
        reservation.setCheckoutAdditionalFee(BigDecimal.valueOf(additionalFee));
        reservation.setRefundableAmount(BigDecimal.ZERO);
        reservationRepository.save(reservation);
        String correlationId = UUID.randomUUID().toString();
        auditService.record(reservation, "RESERVATION", String.valueOf(reservationId),
                ReservationAuditAction.UPDATE_CHECKOUT_FEE,
                "Điều chỉnh phụ phí checkout: " + request.getReason().trim(),
                Map.of(
                        "feeLine", "CHECKOUT_ADDITIONAL_FEE",
                        "amount", currentAdditionalFee.longValue(),
                        "totalAmount", oldTotal.longValue()),
                Map.of(
                        "feeLine", "CHECKOUT_ADDITIONAL_FEE",
                        "amount", additionalFee,
                        "totalAmount", reservation.getTotalAmount().longValue()),
                Map.of(
                        "reasonCode", request.getReasonCode().trim(),
                        "reason", request.getReason().trim()),
                correlationId,
                null);
        return paymentRefundService.applyReservationRefundSummary(ReservationResponse.from(reservation));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationResponse requestCheckoutRefund(Long reservationId, ReservationRefundRequest request) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ có thể hoàn tiền khi reservation đang CHECKED_IN");
        }
        ensureNoPendingSettlementPayment(reservationId);
        applyEarlyCheckoutAdjustment(reservation, LocalDateTime.now());
        applyLateCheckoutFee(reservation, LocalDateTime.now());
        long refundableAmount = Math.max(0L,
                getNetPaidAmount(reservationId) - reservation.getTotalAmount().longValue());
        long uncoveredRequiredRefund = paymentRefundService
                .getUncoveredRequiredRefundAmount(reservationId);
        if (uncoveredRequiredRefund > 0L) {
            paymentRefundService.requestRequiredRefundReplacement(
                    reservationId,
                    requireStaffRefundChannel(request.getRefundChannel()),
                    "Tạo lại nghĩa vụ hoàn checkout sau khi refund trước bị hủy",
                    currentOperator());
            uncoveredRequiredRefund = paymentRefundService
                    .getUncoveredRequiredRefundAmount(reservationId);
            if (uncoveredRequiredRefund > 0L) {
                throw new AppException(ErrorCode.INVALID_REQUEST,
                        String.format("Chưa tạo đủ nghĩa vụ hoàn còn thiếu %,d VND",
                                uncoveredRequiredRefund));
            }
        }
        if (refundableAmount <= 0L) {
            // Retry an already-created checkout refund idempotently. The net-paid
            // calculation intentionally excludes reserved refunds, so the same
            // request would otherwise look like there is no overpayment left.
            if (paymentRefundService.getOutstandingReservedRefundAmount(reservationId) > 0L) {
                reservation.setRefundableAmount(BigDecimal.ZERO);
                reservationRepository.save(reservation);
                return paymentRefundService.applyReservationRefundSummary(
                        ReservationResponse.from(reservation));
            }
            throw new AppException(ErrorCode.INVALID_REQUEST, "Reservation hiện không có tiền thừa để hoàn");
        }
        // getNetPaidAmount đã loại các refund đang reserve, nên đây
        // chính là phần thừa chưa từng được yêu cầu hoàn.
        long amountToRequest = refundableAmount;
        if (amountToRequest > 0L) {
            paymentRefundService.requestReservationRefund(reservationId, amountToRequest,
                    requireStaffRefundChannel(request.getRefundChannel()),
                    "Hoàn tiền thừa khi đối soát checkout reservation " + reservation.getReservationCode(),
                    currentOperator());
            auditService.record(reservation, ReservationAuditAction.REFUND,
                    "Ghi nhận hoàn tiền đối soát checkout: " + amountToRequest
                            + " VND qua " + request.getRefundChannel());
        }
        reservation.setRefundableAmount(BigDecimal.ZERO);
        reservationRepository.save(reservation);
        return paymentRefundService.applyReservationRefundSummary(ReservationResponse.from(reservation));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationResponse markNoShow(Long reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        ensureNoPendingCancellationRefund(reservation);
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ reservation CONFIRMED mới có thể chuyển sang NO_SHOW");
        }
        if (reservation.getActualCheckIn() != null) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Reservation đã có actualCheckIn nên không thể đánh dấu NO_SHOW");
        }
        LocalDateTime eligibleAt = noShowEligibleAt(reservation);
        if (LocalDateTime.now().isBefore(eligibleAt)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ có thể ghi nhận no-show từ " + eligibleAt);
        }
        reservation.setStatus(ReservationStatus.NO_SHOW);
        reservationRepository.save(reservation);
        auditService.record(reservation, ReservationAuditAction.MARK_NO_SHOW, "Ghi nhận khách không đến");
        log.info("Reservation marked NO_SHOW: id={}", reservationId);
        return paymentRefundService.applyReservationRefundSummary(ReservationResponse.from(reservation));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReservationResponse> getAllReservations() {
        return reservationRepository.findAllWithDetails()
                .stream()
                .map(reservation -> {
                    List<ReservationRoomTypeResponse> roomTypeResponses = reservation.getRoomTypes().stream()
                            .map(rrt -> {
                                ReservationRoomTypeResponse res = ReservationRoomTypeResponse.from(rrt);
                                if (rrt.getRoomHold() != null) {
                                    res.setRoomHold(RoomHoldResponse.from(rrt.getRoomHold()));
                                }
                                return res;
                            }).toList();
                    ReservationResponse response = ReservationResponse.fromWithDetails(reservation, roomTypeResponses);
                    response.setPaidAmount(BigDecimal.valueOf(getNetPaidAmount(reservation.getId())));
                    LocalDateTime eligibleAt = noShowEligibleAt(reservation);
                    response.setNoShowEligibleAt(eligibleAt);
                    response.setNoShowEligible(reservation.getStatus() == ReservationStatus.CONFIRMED
                            && reservation.getActualCheckIn() == null
                            && !LocalDateTime.now().isBefore(eligibleAt));
                    return paymentRefundService.applyReservationRefundSummary(response);
                })
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationResponse updateReservation(Long reservationId, UpdateReservationRequest request, User currentUser) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        ensureCanAccessReservation(currentUser, reservation);
        ensureNoPendingCancellationRefund(reservation);

        if (reservation.getStatus() != ReservationStatus.DRAFT) {
            throw new AppException(ErrorCode.RESERVATION_CANNOT_UPDATE);
        }

        if (request.getGuestCount() != null) {
            int totalCapacity = reservation.getRoomTypes().stream()
                    .mapToInt(roomType -> roomType.getQuantity()
                            * Math.max(0, roomType.getRoomType().getMaxGuests() != null
                                    ? roomType.getRoomType().getMaxGuests() : 0))
                    .sum();
            validateCapacity(request.getGuestCount(), totalCapacity);
            reservation.setGuestCount(request.getGuestCount());
        }
        if (request.getNote() != null) {
            reservation.setNote(request.getNote());
        }

        reservationRepository.save(reservation);
        log.info("Reservation updated: id={}", reservationId);
        return paymentRefundService.applyReservationRefundSummary(ReservationResponse.from(reservation));
    }

    @Override
    @Transactional(readOnly = true)
    public FinalPaymentResponse calculateFinalPayment(Long reservationId, User currentUser) {
        Reservation reservation = reservationRepository.findByIdWithDetails(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        ensureCanAccessReservation(currentUser, reservation);
        ProjectedCheckout projected = projectCheckout(reservation, LocalDateTime.now());
        long totalAmount = projected.totalAmount();
        long paidAmount = getNetPaidAmount(reservationId);
        long remaining = Math.max(0, totalAmount - paidAmount);
        long refundable = Math.max(0, paidAmount - totalAmount)
                + paymentRefundService.getUncoveredRequiredRefundAmount(reservationId);
        long lateCheckoutFee = projected.lateCheckoutFee();
        long checkoutAdditionalFee = projected.checkoutAdditionalFee();
        long earlyCheckoutAdjustment = projected.earlyCheckoutAdjustment();
        long roomCharge = Math.max(0L, totalAmount - lateCheckoutFee - checkoutAdditionalFee);

        return FinalPaymentResponse.builder()
                .reservationId(reservationId)
                .totalAmount(totalAmount)
                .roomCharge(roomCharge)
                .plannedRoomCharge(roomCharge + earlyCheckoutAdjustment)
                .paidAmount(paidAmount)
                .remainingAmount(remaining)
                .lateCheckoutFee(lateCheckoutFee)
                .refundableAmount(refundable)
                .earlyCheckoutAdjustment(earlyCheckoutAdjustment)
                .checkoutAdditionalFee(checkoutAdditionalFee)
                .fullyPaid(remaining <= 0)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public CheckoutReconciliationResponse getCheckoutReconciliation(
            Long reservationId,
            User currentUser) {
        Reservation reservation = reservationRepository.findByIdWithDetails(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        ensureCanAccessReservation(currentUser, reservation);
        return buildCheckoutReconciliation(reservation, LocalDateTime.now());
    }

    @Override
    @Transactional(readOnly = true)
    public long getProjectedCheckoutTotal(Long reservationId) {
        Reservation reservation = reservationRepository.findByIdWithDetails(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        return projectCheckout(reservation, LocalDateTime.now()).totalAmount();
    }

    @Override
    @Transactional
    public ReservationInvoiceResponse getInvoice(Long reservationId, User currentUser) {
        // Serialize invoice creation/read with checkout and another print
        // request. The reservation_id unique constraint remains a last line
        // of defense, not the normal concurrency path for an immutable snapshot.
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        ensureCanAccessReservation(currentUser, reservation);
        if (reservation.getStatus() != ReservationStatus.CHECKED_OUT) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ có thể xuất hóa đơn cho reservation đã CHECKED_OUT");
        }

        ReservationInvoiceResponse invoice = reservationInvoiceRepository.findByReservationId(reservationId)
                .map(snapshot -> readInvoiceSnapshot(snapshot.getSnapshotJson()))
                .orElseGet(() -> createInvoiceSnapshot(reservation));
        auditService.record(reservation, ReservationAuditAction.PRINT_INVOICE, "Mở hoặc in lại hóa đơn");
        return invoice;
    }

    private ReservationInvoiceResponse createInvoiceSnapshot(Reservation reservation) {
        var existing = reservationInvoiceRepository.findByReservationId(reservation.getId());
        if (existing.isPresent()) {
            return readInvoiceSnapshot(existing.get().getSnapshotJson());
        }

        BigDecimal lateFee = amountOrZero(reservation.getLateCheckoutFee());
        BigDecimal additionalFee = amountOrZero(reservation.getCheckoutAdditionalFee());
        BigDecimal earlyAdjustment = amountOrZero(reservation.getEarlyCheckoutAdjustment());
        BigDecimal discount = amountOrZero(reservation.getDiscountAmount());
        BigDecimal tax = amountOrZero(reservation.getTaxAmount());
        BigDecimal total = amountOrZero(reservation.getTotalAmount());
        BigDecimal roomCharge = total.subtract(lateFee).subtract(additionalFee).max(BigDecimal.ZERO);
        BigDecimal plannedRoomCharge = roomCharge.add(earlyAdjustment);

        List<PaymentTransaction> transactions = paymentTransactionRepository
                .findByReservationId(reservation.getId()).stream()
                .filter(transaction -> transaction.getStatus() == PaymentStatus.SUCCESS
                        || transaction.getStatus() == PaymentStatus.REFUND_PENDING
                        || transaction.getStatus() == PaymentStatus.REFUNDED)
                .sorted(Comparator.comparing(PaymentTransaction::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        long grossPaid = transactions.stream()
                .mapToLong(transaction -> transaction.getReceivedAmount() != null
                        ? transaction.getReceivedAmount()
                        : transaction.getAmount() != null ? transaction.getAmount() : 0L)
                .sum();
        long acceptedPaid = transactions.stream()
                .mapToLong(transaction -> transaction.getAcceptedAmount() != null
                        ? transaction.getAcceptedAmount()
                        : transaction.getAmount() != null ? transaction.getAmount() : 0L)
                .sum();
        long netPaid = paymentRefundService.getNetPaidAmount(reservation.getId());
        long refunded = Math.max(0L, grossPaid - netPaid);
        long balance = total.longValue() - netPaid;
        boolean refundPending = transactions.stream()
                .anyMatch(transaction -> transaction.getStatus() == PaymentStatus.REFUND_PENDING);

        CustomerProfile customer = reservation.getCustomerProfile();
        LocalDateTime issuedAt = reservation.getActualCheckOut() != null
                ? reservation.getActualCheckOut() : LocalDateTime.now();
        Instant issuedAtUtc = issuedAt.atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .toInstant();
        ReservationInvoiceResponse response = ReservationInvoiceResponse.builder()
                .invoiceNumber("INV-" + reservation.getReservationCode())
                .reservationId(reservation.getId())
                .reservationCode(reservation.getReservationCode())
                .issuedAt(issuedAt)
                .issuedAtUtc(issuedAtUtc)
                .hotelName(hotelName)
                .hotelAddress(hotelAddress)
                .hotelPhone(hotelPhone)
                .hotelEmail(hotelEmail)
                .hotelTaxCode(hotelTaxCode)
                .customerName(customer.getFullName())
                .customerPhone(customer.getPhone())
                .customerEmail(customer.getEmail())
                .customerAddress(customer.getAddress())
                .plannedCheckIn(reservation.getCheckIn())
                .plannedCheckOut(reservation.getCheckOut())
                .actualCheckIn(reservation.getActualCheckIn())
                .actualCheckOut(reservation.getActualCheckOut())
                .guestCount(reservation.getGuestCount())
                .note(reservation.getNote())
                .roomTypes(reservation.getRoomTypes().stream()
                        .map(item -> ReservationInvoiceResponse.RoomTypeLine.builder()
                                .roomTypeName(item.getRoomType().getTypeName())
                                .quantity(item.getQuantity())
                                .pricePerRoomForStay(item.getRoomPrice())
                                .plannedSubtotal(item.getSubtotal())
                                .build())
                        .toList())
                .payments(transactions.stream()
                        .map(transaction -> ReservationInvoiceResponse.PaymentLine.builder()
                                .transactionId(transaction.getId())
                                .transactionReference(transaction.getTxnRef())
                                .provider(transaction.getProvider().name())
                                .purpose(transaction.getPurpose() != null ? transaction.getPurpose().name() : null)
                                .status(transaction.getStatus().name())
                                .amount(transaction.getAmount())
                                .refundAmount(transaction.getRefundAmount())
                                .refundProvider(transaction.getRefundProvider() != null
                                        ? transaction.getRefundProvider().name() : null)
                                .refundChannel(paymentRefundService.latestChannelForPayment(transaction.getId()))
                                .paidAt(transaction.getPaidAt())
                                .paidAtUtc(transaction.getPaidAtUtc())
                                .createdAt(transaction.getCreatedAt())
                                .build())
                        .toList())
                .plannedRoomCharge(plannedRoomCharge)
                .roomCharge(roomCharge)
                .actualRoomCharge(roomCharge)
                .earlyCheckoutAdjustment(earlyAdjustment)
                .lateCheckoutFee(lateFee)
                .checkoutAdditionalFee(additionalFee)
                .discountAmount(discount)
                .taxAmount(tax)
                .totalAmount(total)
                .grossPaidAmount(grossPaid)
                .refundedAmount(refunded)
                .completedRefundAmount(refunded)
                .netPaidAmount(netPaid)
                .balanceAmount(balance)
                .remainingAmount(balance)
                .settlementStatus(refundPending ? "REFUND_PENDING"
                        : balance > 0 ? "BALANCE_DUE"
                        : balance < 0 ? "OVERPAID" : "PAID")
                .build();

        try {
            String snapshot = objectMapper.writeValueAsString(response);
            reservationInvoiceRepository.save(ReservationInvoice.builder()
                    .reservation(reservation)
                    .invoiceNumber(response.getInvoiceNumber())
                    .issuedAt(response.getIssuedAt())
                    .totalAmount(total)
                    .currency("VND")
                    .roomCharge(roomCharge)
                    .actualRoomCharge(roomCharge)
                    .plannedRoomCharge(plannedRoomCharge)
                    .earlyCheckoutAdjustment(earlyAdjustment)
                    .lateCheckoutFee(lateFee)
                    .additionalFee(additionalFee)
                    .discountAmount(discount)
                    .taxAmount(tax)
                    .grossReceivedAmount(grossPaid)
                    .acceptedPaidAmount(acceptedPaid)
                    .refundedAmount(refunded)
                    .completedRefundAmount(refunded)
                    .balanceAmount(balance)
                    .remainingAmount(balance)
                    .settlementStatus(response.getSettlementStatus())
                    .snapshotJson(snapshot)
                    .snapshotHash(sha256(snapshot))
                    .snapshotCreatedAtUtc(Instant.now())
                    .issuedAtUtc(issuedAtUtc)
                    .createdAtUtc(Instant.now())
                    .build());
            return response;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Không thể tạo snapshot hóa đơn", exception);
        }
    }

    private ReservationInvoiceResponse readInvoiceSnapshot(String snapshotJson) {
        try {
            return objectMapper.readValue(snapshotJson, ReservationInvoiceResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Snapshot hóa đơn không hợp lệ", exception);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể tạo hash snapshot", exception);
        }
    }

    private BigDecimal amountOrZero(BigDecimal amount) {
        return amount != null ? amount : BigDecimal.ZERO;
    }

    private void ensureNoPendingSettlementPayment(Long reservationId) {
        boolean pendingFinalPayment = paymentTransactionRepository
                .existsByReservationIdAndPurposeAndStatus(
                        reservationId, PaymentPurpose.FINAL_PAYMENT, PaymentStatus.PENDING);
        boolean pendingWalkInPayment = paymentTransactionRepository
                .existsByReservationIdAndPurposeAndStatus(
                        reservationId, PaymentPurpose.WALK_IN, PaymentStatus.PENDING);
        if (pendingFinalPayment || pendingWalkInPayment) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Đang có QR thanh toán cuối còn hiệu lực; hãy hủy/hết hạn QR trước khi sửa phí hoặc checkout");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private CustomerProfile findOrCreateOnlineCustomerProfile(User user) {
        return customerProfileRepository.findByLinkedUserId(user.getId())
                .orElseGet(() -> customerProfileRepository.save(CustomerProfile.builder()
                        .fullName(user.getFullName())
                        .phone(user.getPhone())
                        .email(user.getEmail())
                        .address(user.getAddress())
                        .source(CustomerProfileSource.ONLINE)
                        .linkedUser(user)
                        .build()));
    }

    private CustomerProfile resolveWalkInCustomerProfile(CreateWalkInReservationRequest request) {
        return resolveWalkInCustomerProfile(request.getCustomerProfileId(), request.getCustomer());
    }

    private CustomerProfile resolveWalkInCustomerProfile(
            Long customerProfileId,
            CustomerProfileRequest customer) {
        if (customerProfileId != null) {
            return customerProfileRepository.findById(customerProfileId)
                    .orElseThrow(() -> new AppException(ErrorCode.CUSTOMER_NOT_FOUND));
        }

        return resolveCustomerProfileFromRequest(
                customer,
                CustomerProfileSource.WALK_IN,
                "customerProfileId hoặc thông tin khách vãng lai là bắt buộc",
                "Tên khách vãng lai không được để trống khi tạo hồ sơ mới",
                true);
    }

    private CustomerProfile resolveGuestOnlineCustomerProfile(CustomerProfileRequest customer) {
        if (customer == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Thông tin khách đặt phòng là bắt buộc khi chưa đăng nhập");
        }
        if (!hasText(customer.getEmail())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Email là bắt buộc khi đặt phòng online");
        }
        validateCustomerProfile(customer, "Tên khách đặt phòng không được để trống khi chưa đăng nhập");
        // Không cập nhật profile tìm bằng email/số điện thoại từ một request public.
        // Sau khi xác minh email, luồng claim sẽ liên kết/ghép các profile cùng email.
        return customerProfileRepository.save(CustomerProfile.builder()
                .fullName(customer.getFullName().trim())
                .phone(trimToNull(customer.getPhone()))
                .email(customer.getEmail().trim())
                .address(trimToNull(customer.getAddress()))
                .idCardNumber(trimToNull(customer.getIdCardNumber()))
                .source(CustomerProfileSource.ONLINE)
                .build());
    }

    private CustomerProfile resolveCustomerProfileFromRequest(
            CustomerProfileRequest customer,
            CustomerProfileSource source,
            String missingCustomerMessage,
            String missingNameMessage,
            boolean allowLinkedProfileReuse) {
        if (customer == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST, missingCustomerMessage);
        }
        validateCustomerProfile(customer, missingNameMessage);

        // Chỉ dùng lại hồ sơ khi tất cả định danh được gửi lên cùng khớp một profile.
        // Không lấy profile chỉ vì trùng một trường rồi ghi đè các trường còn lại.
        if (hasText(customer.getIdCardNumber())) {
            var existing = customerProfileRepository.findFirstByIdCardNumber(customer.getIdCardNumber().trim());
            if (existing.isPresent()
                    && canReuseCustomerProfile(existing.get(), allowLinkedProfileReuse)
                    && matchesProvidedIdentity(existing.get(), customer)) {
                return updateCustomerProfile(existing.get(), customer, source);
            }
        }
        return customerProfileRepository.save(CustomerProfile.builder()
                .fullName(customer.getFullName().trim())
                .phone(trimToNull(customer.getPhone()))
                .email(trimToNull(customer.getEmail()))
                .address(trimToNull(customer.getAddress()))
                .idCardNumber(trimToNull(customer.getIdCardNumber()))
                .source(source)
                .build());
    }

    private boolean matchesProvidedIdentity(CustomerProfile profile, CustomerProfileRequest request) {
        return (!hasText(request.getPhone()) || request.getPhone().trim().equals(profile.getPhone()))
                && (!hasText(request.getEmail()) || request.getEmail().trim().equalsIgnoreCase(profile.getEmail()))
                && (!hasText(request.getIdCardNumber())
                    || request.getIdCardNumber().trim().equals(profile.getIdCardNumber()));
    }

    private boolean canReuseCustomerProfile(CustomerProfile profile, boolean allowLinkedProfileReuse) {
        return allowLinkedProfileReuse || profile.getLinkedUser() == null;
    }

    private CustomerProfile updateCustomerProfile(
            CustomerProfile profile,
            CustomerProfileRequest request,
            CustomerProfileSource source) {
        if (hasText(request.getFullName())) {
            profile.setFullName(request.getFullName().trim());
        }
        if (hasText(request.getPhone())) {
            profile.setPhone(request.getPhone().trim());
        }
        if (hasText(request.getEmail())) {
            profile.setEmail(request.getEmail().trim());
        }
        if (hasText(request.getAddress())) {
            profile.setAddress(request.getAddress().trim());
        }
        if (hasText(request.getIdCardNumber())) {
            profile.setIdCardNumber(request.getIdCardNumber().trim());
        }
        if (profile.getSource() == null) {
            profile.setSource(source);
        }
        return customerProfileRepository.save(profile);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private void validateInterval(LocalDateTime checkIn, LocalDateTime checkOut) {
    if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) {
        throw new AppException(ErrorCode.RESERVATION_INVALID_DATE);
    }
}

    private void validateFutureDates(LocalDateTime checkIn, LocalDateTime checkOut) {
    validateInterval(checkIn, checkOut);
    if (!checkIn.isAfter(LocalDateTime.now())) {
        throw new AppException(ErrorCode.RESERVATION_CHECKIN_PAST);
    }
}

    private void validateCapacity(Integer guestCount, int totalCapacity) {
        if (guestCount != null && guestCount > totalCapacity) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    String.format("Số khách vượt sức chứa: tối đa %d khách cho các phòng đã chọn", totalCapacity));
        }
    }

    private int roomCapacity(RoomType roomType) {
        return roomType.getMaxGuests() != null ? Math.max(1, roomType.getMaxGuests()) : 2;
    }

    private void validateReservationRequest(Integer guestCount, List<RoomTypeItemRequest> roomTypes) {
        if (guestCount == null || guestCount < 1) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Số khách phải lớn hơn 0");
        }
        if (roomTypes == null || roomTypes.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Phải chọn ít nhất một loại phòng");
        }
        Set<Long> roomTypeIds = new HashSet<>();
        for (RoomTypeItemRequest item : roomTypes) {
            if (item == null || item.getRoomTypeId() == null || item.getQuantity() == null || item.getQuantity() < 1) {
                throw new AppException(ErrorCode.INVALID_REQUEST, "Loại phòng và số lượng phải hợp lệ");
            }
            if (!roomTypeIds.add(item.getRoomTypeId())) {
                throw new AppException(ErrorCode.INVALID_REQUEST, "Mỗi loại phòng chỉ được chọn một lần");
            }
        }
    }

    private void validateCustomerProfile(CustomerProfileRequest customer, String missingNameMessage) {
        if (customer == null || !hasText(customer.getFullName())) {
            throw new AppException(ErrorCode.INVALID_REQUEST, missingNameMessage);
        }
        if (customer.getFullName().trim().length() > 150) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Tên khách không được quá 150 ký tự");
        }
        if (hasText(customer.getPhone()) && !customer.getPhone().trim().matches("^(0|\\+84)[0-9]{9,10}$")) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Số điện thoại không hợp lệ");
        }
        if (hasText(customer.getEmail()) && customer.getEmail().trim().length() > 255) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Email không được quá 255 ký tự");
        }
        if (hasText(customer.getIdCardNumber()) && customer.getIdCardNumber().trim().length() > 50) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Số giấy tờ không được quá 50 ký tự");
        }
    }

    private void validateCheckInTime(Reservation reservation) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime earliestAllowed = reservation.getCheckIn().minusHours(2);
        if (now.isBefore(earliestAllowed)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ có thể check-in sớm tối đa 2 giờ so với thời gian đã đặt");
        }
        if (!now.isBefore(reservation.getCheckOut())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Đã quá thời gian trả phòng dự kiến, reservation cần được nhân viên xử lý lại");
        }
        if (now.toLocalDate().isAfter(reservation.getCheckIn().toLocalDate())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Đã quá ngày check-in dự kiến; nhân viên cần xử lý reservation là no-show");
        }
    }

    private void applyEarlyCheckoutAdjustment(Reservation reservation, LocalDateTime now) {
        BigDecimal currentAdjustment = reservation.getEarlyCheckoutAdjustment() != null
                ? reservation.getEarlyCheckoutAdjustment() : BigDecimal.ZERO;

        // A preview is not the final checkout. If the guest later stays until (or
        // beyond) the planned checkout, restore the persisted early-checkout
        // discount before applyLateCheckoutFee calculates any late charge.
        if (!now.isBefore(reservation.getCheckOut()) || reservation.getActualCheckIn() == null) {
            if (currentAdjustment.compareTo(BigDecimal.ZERO) > 0) {
                reservation.setTotalAmount(reservation.getTotalAmount().add(currentAdjustment));
                reservation.setEarlyCheckoutAdjustment(BigDecimal.ZERO);
                reservationRepository.save(reservation);
            }
            return;
        }

        long bookedHours = pricingService.billableHours(reservation.getCheckIn(), reservation.getCheckOut());
        // A reconciliation can be opened in the same clock tick as check-in.
        // Pricing still has a one-hour minimum, so keep the interval valid instead of rejecting it.
        LocalDateTime effectiveCheckout = now.isAfter(reservation.getActualCheckIn())
                ? now
                : reservation.getActualCheckIn().plusNanos(1);
        long actualHours = pricingService.billableHours(reservation.getActualCheckIn(), effectiveCheckout);

        BigDecimal actualUsageTotal = reservation.getRoomTypes().stream()
                .map(item -> {
                    BigDecimal snapshotBasePrice = pricingService.recoverFirstHourPrice(item.getRoomPrice(), bookedHours);
                    BigDecimal actualPricePerRoom = pricingService.calculatePricePerRoom(snapshotBasePrice, actualHours);
                    return actualPricePerRoom.multiply(BigDecimal.valueOf(item.getQuantity()));
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal additionalFee = reservation.getCheckoutAdditionalFee() != null
                ? reservation.getCheckoutAdditionalFee() : BigDecimal.ZERO;
        // Tổng dự kiến gốc không bao gồm phụ phí do staff nhập. Giữ phụ phí tách biệt
        // để các lần xem lại final-payment không vô tình trừ mất khoản vừa đối soát.
        BigDecimal originalBookedTotal = reservation.getTotalAmount()
                .add(currentAdjustment)
                .subtract(additionalFee);
        BigDecimal difference = originalBookedTotal.subtract(actualUsageTotal).max(BigDecimal.ZERO);
        BigDecimal requiredAdjustment = difference;

        BigDecimal requiredTotal = originalBookedTotal.subtract(requiredAdjustment).add(additionalFee);
        if (requiredAdjustment.compareTo(currentAdjustment) != 0
                || requiredTotal.compareTo(reservation.getTotalAmount()) != 0) {
            reservation.setTotalAmount(requiredTotal);
            reservation.setEarlyCheckoutAdjustment(requiredAdjustment);
            reservationRepository.save(reservation);
        }
    }

    private void applyLateCheckoutFee(Reservation reservation, LocalDateTime now) {
        BigDecimal currentFee = reservation.getLateCheckoutFee() != null
                ? reservation.getLateCheckoutFee() : BigDecimal.ZERO;
        BigDecimal requiredFee = BigDecimal.ZERO;
        if (now.isAfter(reservation.getCheckOut())) {
            long lateMinutes = ChronoUnit.MINUTES.between(reservation.getCheckOut(), now);
            long lateHours = Math.max(1, (lateMinutes + 59) / 60);
            int roomCount = reservation.getRoomTypes().stream()
                    .mapToInt(item -> item.getQuantity() != null ? item.getQuantity() : 0)
                    .sum();
            requiredFee = pricingService.calculateLateCheckoutFee(lateHours, roomCount);
        }
        if (requiredFee.compareTo(currentFee) > 0) {
            reservation.setTotalAmount(reservation.getTotalAmount().add(requiredFee.subtract(currentFee)));
            reservation.setLateCheckoutFee(requiredFee);
            reservationRepository.save(reservation);
        }
    }

    /**
     * Read-only projection mirroring the current early/late pricing rules.
     * Checkout still persists these values only while holding the reservation
     * lock; opening a preview never changes financial state.
     */
    private ProjectedCheckout projectCheckout(Reservation reservation, LocalDateTime now) {
        BigDecimal projectedTotal = amountOrZero(reservation.getTotalAmount());
        BigDecimal projectedEarly = amountOrZero(reservation.getEarlyCheckoutAdjustment());
        BigDecimal projectedLate = amountOrZero(reservation.getLateCheckoutFee());
        BigDecimal additionalFee = amountOrZero(reservation.getCheckoutAdditionalFee());

        if (reservation.getStatus() == ReservationStatus.CHECKED_IN) {
            if (!now.isBefore(reservation.getCheckOut()) || reservation.getActualCheckIn() == null) {
                if (projectedEarly.signum() > 0) {
                    projectedTotal = projectedTotal.add(projectedEarly);
                    projectedEarly = BigDecimal.ZERO;
                }
            } else {
                long bookedHours = pricingService.billableHours(
                        reservation.getCheckIn(), reservation.getCheckOut());
                LocalDateTime effectiveCheckout = now.isAfter(reservation.getActualCheckIn())
                        ? now : reservation.getActualCheckIn().plusNanos(1);
                long actualHours = pricingService.billableHours(
                        reservation.getActualCheckIn(), effectiveCheckout);
                BigDecimal actualUsageTotal = reservation.getRoomTypes().stream()
                        .map(item -> {
                            BigDecimal snapshotBasePrice = pricingService.recoverFirstHourPrice(
                                    item.getRoomPrice(), bookedHours);
                            BigDecimal actualPricePerRoom = pricingService.calculatePricePerRoom(
                                    snapshotBasePrice, actualHours);
                            return actualPricePerRoom.multiply(
                                    BigDecimal.valueOf(item.getQuantity()));
                        })
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal originalBookedTotal = projectedTotal
                        .add(projectedEarly)
                        .subtract(additionalFee);
                projectedEarly = originalBookedTotal
                        .subtract(actualUsageTotal)
                        .max(BigDecimal.ZERO);
                projectedTotal = originalBookedTotal
                        .subtract(projectedEarly)
                        .add(additionalFee);
            }

            BigDecimal requiredLate = BigDecimal.ZERO;
            if (now.isAfter(reservation.getCheckOut())) {
                long lateMinutes = ChronoUnit.MINUTES.between(reservation.getCheckOut(), now);
                long lateHours = Math.max(1, (lateMinutes + 59) / 60);
                int roomCount = reservation.getRoomTypes().stream()
                        .mapToInt(item -> item.getQuantity() != null ? item.getQuantity() : 0)
                        .sum();
                requiredLate = pricingService.calculateLateCheckoutFee(lateHours, roomCount);
            }
            if (requiredLate.compareTo(projectedLate) > 0) {
                projectedTotal = projectedTotal.add(requiredLate.subtract(projectedLate));
                projectedLate = requiredLate;
            }
        }

        return new ProjectedCheckout(
                projectedTotal.longValue(),
                projectedEarly.longValue(),
                projectedLate.longValue(),
                additionalFee.longValue());
    }

    private CheckoutReconciliationResponse buildCheckoutReconciliation(
            Reservation reservation,
            LocalDateTime now) {
        ProjectedCheckout projected = projectCheckout(reservation, now);
        long acceptedAmount = getNetPaidAmount(reservation.getId());
        long reservedRefundAmount = paymentRefundService
                .getOutstandingReservedRefundAmount(reservation.getId());
        long uncoveredRefundAmount = paymentRefundService
                .getUncoveredRequiredRefundAmount(reservation.getId());
        boolean paymentPending = paymentTransactionRepository
                .existsByReservationIdAndPurposeAndStatus(
                        reservation.getId(), PaymentPurpose.FINAL_PAYMENT, PaymentStatus.PENDING)
                || paymentTransactionRepository.existsByReservationIdAndPurposeAndStatus(
                        reservation.getId(), PaymentPurpose.WALK_IN, PaymentStatus.PENDING);
        long outstandingAmount = Math.max(0L, projected.totalAmount() - acceptedAmount);
        long deltaAmount = acceptedAmount - projected.totalAmount();
        List<String> blockers = new ArrayList<>();
        if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            blockers.add("Reservation không ở trạng thái CHECKED_IN");
        }
        if (paymentPending) blockers.add("Còn payment cuối đang PENDING");
        if (outstandingAmount > 0L) blockers.add("Còn thiếu " + outstandingAmount + " VND");
        if (deltaAmount > 0L) blockers.add("Còn thừa " + deltaAmount + " VND cần hoàn");
        if (reservedRefundAmount > 0L) {
            blockers.add("Còn " + reservedRefundAmount + " VND refund chưa hoàn tất");
        }
        if (uncoveredRefundAmount > 0L) {
            blockers.add("Còn " + uncoveredRefundAmount + " VND nghĩa vụ hoàn chưa được tạo lại");
        }
        boolean matched = blockers.isEmpty();
        return CheckoutReconciliationResponse.builder()
                .reservationId(reservation.getId())
                .reservationCode(reservation.getReservationCode())
                .requiredAmount(projected.totalAmount())
                .acceptedAmount(acceptedAmount)
                .reservedRefundAmount(reservedRefundAmount)
                .uncoveredRefundAmount(uncoveredRefundAmount)
                .outstandingAmount(outstandingAmount)
                .deltaAmount(deltaAmount)
                .lateCheckoutFee(projected.lateCheckoutFee())
                .earlyCheckoutAdjustment(projected.earlyCheckoutAdjustment())
                .checkoutAdditionalFee(projected.checkoutAdditionalFee())
                .paymentPending(paymentPending)
                .refundPending(reservedRefundAmount > 0L || uncoveredRefundAmount > 0L)
                .status(matched
                        ? com.hotel.backend.constant.CheckoutReconciliationStatus.MATCHED
                        : com.hotel.backend.constant.CheckoutReconciliationStatus.MISMATCH)
                .blockingReasons(List.copyOf(blockers))
                .build();
    }

    private long getNetPaidAmount(Long reservationId) {
        return paymentRefundService.getNetPaidAmount(reservationId);
    }

    private record ProjectedCheckout(
            long totalAmount,
            long earlyCheckoutAdjustment,
            long lateCheckoutFee,
            long checkoutAdditionalFee) {}

    private String currentOperator() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return authentication != null && hasText(authentication.getName())
                ? authentication.getName() : "hotel_system";
    }

    private LocalDateTime noShowEligibleAt(Reservation reservation) {
        LocalDateTime graceEnd = reservation.getCheckIn()
                .plusMinutes(Math.max(0L, noShowGraceMinutes));
        return graceEnd.isAfter(reservation.getCheckOut()) ? reservation.getCheckOut() : graceEnd;
    }

    private void ensureCanAccessReservation(User currentUser, Reservation reservation) {
        ensureCanAccessReservation(currentUser, reservation, null);
    }

    private void ensureCanAccessReservation(User currentUser, Reservation reservation, String guestToken) {
        if (currentUser == null && hasText(guestToken) && guestToken.equals(reservation.getGuestToken())) {
            return;
        }
        if (currentUser == null) {
            throw new AppException(ErrorCode.RESERVATION_NOT_OWNER);
        }
        if (List.of(UserType.ADMIN, UserType.STAFF).contains(currentUser.getType())) {
            return;
        }
        if (reservation.getCustomerProfile() == null
                || reservation.getCustomerProfile().getLinkedUser() == null
                || !reservation.getCustomerProfile().getLinkedUser().getId().equals(currentUser.getId())) {
            throw new AppException(ErrorCode.RESERVATION_NOT_OWNER);
        }
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

    private CancellationAmounts resolveCancellationAmounts(
            CancelReservationRequest request,
            long originalAmount,
            String fallbackPolicyNote) {
        if (request == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Thiếu quyết định phí hủy");
        }
        long safeOriginalAmount = Math.max(0L, originalAmount);
        boolean refundRequested = Boolean.TRUE.equals(request.getRefundPayment());
        long penaltyAmount = refundRequested
                ? request.getCancellationPenaltyAmount() != null
                ? request.getCancellationPenaltyAmount() : 0L
                : safeOriginalAmount;
        if (penaltyAmount < 0L || penaltyAmount > safeOriginalAmount) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    String.format("Phí phạt phải nằm trong khoảng 0 đến %,d VND", safeOriginalAmount));
        }

        String reasonCode = hasText(request.getPenaltyReasonCode())
                ? request.getPenaltyReasonCode().trim()
                : penaltyAmount == 0L
                ? "NO_PENALTY"
                : refundRequested
                ? null
                : "FULL_FORFEITURE";
        String policyNote = hasText(request.getPenaltyNote())
                ? request.getPenaltyNote().trim()
                : !refundRequested && hasText(fallbackPolicyNote)
                ? fallbackPolicyNote.trim()
                : null;
        if (penaltyAmount > 0L && (!hasText(reasonCode) || !hasText(policyNote))) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Phí phạt lớn hơn 0 bắt buộc có loại chính sách và căn cứ áp dụng");
        }

        return new CancellationAmounts(
                safeOriginalAmount,
                penaltyAmount,
                safeOriginalAmount - penaltyAmount,
                reasonCode,
                policyNote);
    }

    private Map<String, Object> cancellationAuditDetail(
            CancellationAmounts amounts,
            RefundChannel refundChannel) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("currency", "VND");
        detail.put("originalAmount", amounts.originalAmount());
        detail.put("penaltyAmount", amounts.penaltyAmount());
        detail.put("refundAmount", amounts.refundAmount());
        detail.put("policyApplied", amounts.policyApplied());
        detail.put("policyNote", amounts.policyNote());
        detail.put("refundChannel", refundChannel != null ? refundChannel.name() : null);
        return detail;
    }

    private record CancellationAmounts(
            long originalAmount,
            long penaltyAmount,
            long refundAmount,
            String policyApplied,
            String policyNote) {}

    private RefundChannel requireStaffRefundChannel(RefundChannel channel) {
        if (channel == null || !List.of(RefundChannel.MANUAL_BANK_TRANSFER, RefundChannel.CASH_AT_COUNTER)
                .contains(channel)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Vui lòng chọn hoàn tiền mặt tại quầy hoặc QR/chuyển khoản ngân hàng");
        }
        return channel;
    }

    /**
     * Khách online đã khai tài khoản nhận tiền khi gửi yêu cầu hủy, vì vậy bước
     * staff duyệt luôn đi qua QR thủ công. Client cũ được phép bỏ trống field;
     * mọi giá trị khác QR đều bị từ chối để không đổi kênh sang tiền mặt.
     */
    private RefundChannel requireOnlineCancellationRefundChannel(RefundChannel channel) {
        if (channel != null && channel != RefundChannel.MANUAL_BANK_TRANSFER) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Yêu cầu hủy online chỉ được hoàn bằng QR");
        }
        return RefundChannel.MANUAL_BANK_TRANSFER;
    }

    private void ensureNoPendingCancellationRefund(Reservation reservation) {
        if (reservation != null
                && ReservationCancellationReasonCode.isRefundPending(
                reservation.getCancellationReasonCode())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Reservation đang chờ hoàn tiền hoàn tất; không thể thực hiện hành động khác");
        }
    }

    private void releaseReservationHolds(Reservation reservation) {
        reservation.getRoomTypes().forEach(rrt -> {
            RoomHold hold = rrt.getRoomHold();
            if (hold != null && List.of(HoldStatus.ACTIVE, HoldStatus.CONVERTED).contains(hold.getStatus())) {
                hold.setStatus(HoldStatus.RELEASED);
                roomHoldRepository.save(hold);
            }
        });
    }

    private void ensureRoomHasNoOverlappingAssignment(Room room, Reservation reservation) {
        boolean hasOverlap = reservationRoomRepository.existsOverlappingRoomAssignment(
                room.getId(),
                reservation.getId(),
                reservation.getCheckIn(),
                reservation.getCheckOut(),
                List.of(AssignStatus.ASSIGNED, AssignStatus.CHECKED_IN));

        if (hasOverlap) {
            throw new AppException(ErrorCode.ROOM_NOT_AVAILABLE,
                    String.format("Phòng '%s' đã được gán cho đặt phòng khác trong khoảng thời gian này",
                            room.getRoomName()));
        }
    }

    private void checkAvailabilityOrThrow(RoomType roomType, int requested,
                                          LocalDateTime checkIn, LocalDateTime checkOut,
                                          Long excludeReservationId) {
        int available = availableRooms(roomType, checkIn, checkOut, excludeReservationId);
        if (available < requested) {
            throw new AppException(ErrorCode.ROOM_NOT_AVAILABLE,
                    String.format("Loại phòng '%s' chỉ còn %d phòng trống", roomType.getTypeName(), available));
        }
    }

    private boolean hasAvailability(RoomType roomType, int requested,
                                    LocalDateTime checkIn, LocalDateTime checkOut,
                                    Long excludeReservationId) {
        return availableRooms(roomType, checkIn, checkOut, excludeReservationId) >= requested;
    }

    private int availableRooms(RoomType roomType,
                               LocalDateTime checkIn,
                               LocalDateTime checkOut,
                               Long excludeReservationId) {
        int total = roomTypeRepository.countAvailableRoomsByType(roomType.getId());
        int booked = excludeReservationId == null
                ? reservationRoomTypeRepository.countBookedQuantity(roomType.getId(), checkIn, checkOut)
                : reservationRoomTypeRepository.countBookedQuantityExcluding(
                roomType.getId(), excludeReservationId, checkIn, checkOut);
        int held = excludeReservationId == null
                ? roomHoldRepository.countActiveHeldQuantity(
                roomType.getId(), checkIn, checkOut, LocalDateTime.now())
                : roomHoldRepository.countActiveHeldQuantityExcluding(
                roomType.getId(), excludeReservationId, checkIn, checkOut, LocalDateTime.now());
        return total - booked - held;
    }

    private String generateCode() {
        String code;
        do {
            code = "RES-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        } while (reservationRepository.existsByReservationCode(code));
        return code;
    }

    private String generateGuestToken() {
        String token;
        do {
            token = UUID.randomUUID().toString();
        } while (reservationRepository.existsByGuestToken(token));
        return token;
    }

    // Inner record tạm để truyền data giữa các bước
    private record RoomTypeWithPrice(RoomType roomType, int quantity,
                                     BigDecimal price, BigDecimal subtotal) {}
}
