package com.hotel.backend.service;

import com.hotel.backend.constant.CashMovementDirection;
import com.hotel.backend.constant.CashMovementSourceType;
import com.hotel.backend.constant.CashMovementType;
import com.hotel.backend.constant.CashierShiftStatus;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.request.CashMovementRequest;
import com.hotel.backend.dto.request.CloseCashierShiftRequest;
import com.hotel.backend.dto.request.OpenCashierShiftRequest;
import com.hotel.backend.dto.response.CashMovementResponse;
import com.hotel.backend.dto.response.CashierShiftResponse;
import com.hotel.backend.dto.response.MoneyReportResponse;
import com.hotel.backend.entity.CashMovement;
import com.hotel.backend.entity.CashierShift;
import com.hotel.backend.entity.PaymentRefund;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.CashMovementRepository;
import com.hotel.backend.repository.CashierShiftRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single write boundary for the physical cash drawer.
 *
 * <p>Cash movements are append-only. Payments/refunds call this service from
 * their existing transaction so financial state and drawer state commit or
 * roll back together.</p>
 */
@Service
public class CashierShiftService {

    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final EnumSet<CashierShiftStatus> ACTIVE_STATUSES =
            EnumSet.of(CashierShiftStatus.OPEN, CashierShiftStatus.CLOSING);

    private final CashierShiftRepository shiftRepository;
    private final CashMovementRepository movementRepository;
    private final ReservationAuditService auditService;
    private final MoneyReportService moneyReportService;
    private final Clock clock;

    @Autowired
    public CashierShiftService(
            CashierShiftRepository shiftRepository,
            CashMovementRepository movementRepository,
            ReservationAuditService auditService,
            MoneyReportService moneyReportService) {
        this(
                shiftRepository,
                movementRepository,
                auditService,
                moneyReportService,
                Clock.systemUTC());
    }

    CashierShiftService(
            CashierShiftRepository shiftRepository,
            CashMovementRepository movementRepository,
            ReservationAuditService auditService,
            Clock clock) {
        this(shiftRepository, movementRepository, auditService, null, clock);
    }

    CashierShiftService(
            CashierShiftRepository shiftRepository,
            CashMovementRepository movementRepository,
            ReservationAuditService auditService,
            MoneyReportService moneyReportService,
            Clock clock) {
        this.shiftRepository = shiftRepository;
        this.movementRepository = movementRepository;
        this.auditService = auditService;
        this.moneyReportService = moneyReportService;
        this.clock = clock;
    }

    @Transactional
    public CashierShiftResponse open(OpenCashierShiftRequest request, User currentUser) {
        User actor = requireOperator(currentUser);
        if (shiftRepository.findActiveByUserIdForUpdate(actor.getId(), ACTIVE_STATUSES).isPresent()) {
            throw new AppException(ErrorCode.CASHIER_SHIFT_ALREADY_OPEN);
        }

        Instant now = clock.instant();
        CashierShift shift = CashierShift.builder()
                .shiftCode(generateShiftCode(actor, now))
                .businessDate(LocalDate.ofInstant(now, HOTEL_ZONE))
                .status(CashierShiftStatus.OPEN)
                .openedBy(actor)
                .openedByName(displayName(actor))
                .openedByRole(actor.getType().name())
                .openedAtUtc(now)
                .openingCashAmount(BigDecimal.ZERO)
                .note(trimToNull(request.getNote()))
                .build();
        try {
            shift = shiftRepository.saveAndFlush(shift);
        } catch (DataIntegrityViolationException duplicate) {
            throw new AppException(ErrorCode.CASHIER_SHIFT_ALREADY_OPEN);
        }

        auditService.recordTargetForUser(
                actor,
                "CASHIER_SHIFT",
                String.valueOf(shift.getId()),
                ReservationAuditAction.CASHIER_SHIFT_OPENED,
                "Mở ca thu ngân " + shift.getShiftCode(),
                Map.of(
                        "businessDate", shift.getBusinessDate(),
                        "trackingMode", "AUTOMATIC"));
        return toResponse(shift, true);
    }

    @Transactional(readOnly = true)
    public CashierShiftResponse current(User currentUser) {
        User actor = requireOperator(currentUser);
        return shiftRepository
                .findFirstByOpenedByIdAndStatusInOrderByOpenedAtUtcDesc(actor.getId(), ACTIVE_STATUSES)
                .map(shift -> toResponse(shift, true))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Page<CashierShiftResponse> list(Pageable pageable, User currentUser) {
        User actor = requireOperator(currentUser);
        Page<CashierShift> shifts = actor.getType() == UserType.ADMIN
                ? shiftRepository.findAll(pageable)
                : shiftRepository.findAllByOpenedById(actor.getId(), pageable);
        List<Long> shiftIds = shifts.getContent().stream().map(CashierShift::getId).toList();
        Map<Long, CashMovementRepository.CashShiftMovementSummary> summaries = new HashMap<>();
        if (!shiftIds.isEmpty()) {
            movementRepository.summarizeByCashierShiftIds(shiftIds)
                    .forEach(summary -> summaries.put(summary.getShiftId(), summary));
        }
        return shifts.map(shift -> {
            CashMovementRepository.CashShiftMovementSummary summary = summaries.get(shift.getId());
            BigDecimal currentExpected = summary != null
                    ? nullSafeMoney(summary.getExpectedCash())
                    : BigDecimal.ZERO;
            long movementCount = summary != null && summary.getMovementCount() != null
                    ? summary.getMovementCount()
                    : 0L;
            BigDecimal expected = ACTIVE_STATUSES.contains(shift.getStatus())
                    ? currentExpected
                    : shift.getExpectedCashAmount();
            return toResponse(shift, false, expected, movementCount);
        });
    }

    @Transactional(readOnly = true)
    public CashierShiftResponse get(Long shiftId, User currentUser) {
        User actor = requireOperator(currentUser);
        CashierShift shift = requireShift(shiftId);
        ensureCanView(actor, shift);
        return toResponse(shift, true);
    }

    @Transactional(readOnly = true)
    public CashMovementResponse getMovement(Long movementId, User currentUser) {
        User actor = requireOperator(currentUser);
        CashMovement movement = movementRepository.findById(movementId)
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy bút toán tiền mặt"));
        ensureCanView(actor, movement.getCashierShift());
        return toResponse(movement);
    }

    @Transactional
    public CashMovementResponse addCashIn(
            Long shiftId,
            CashMovementRequest request,
            String idempotencyKey,
            User currentUser) {
        return addManualMovement(
                shiftId, request, idempotencyKey, currentUser,
                CashMovementType.CASH_IN, CashMovementDirection.IN);
    }

    @Transactional
    public CashMovementResponse addCashOut(
            Long shiftId,
            CashMovementRequest request,
            String idempotencyKey,
            User currentUser) {
        return addManualMovement(
                shiftId, request, idempotencyKey, currentUser,
                CashMovementType.CASH_OUT, CashMovementDirection.OUT);
    }

    @Transactional(readOnly = true)
    public CashierShiftResponse previewClose(Long shiftId, User currentUser) {
        User actor = requireOperator(currentUser);
        CashierShift shift = requireShift(shiftId);
        ensureOwner(actor, shift);
        ensureOpen(shift);
        return toResponse(shift, true, expectedCash(shift.getId()));
    }

    @Transactional
    public CashierShiftResponse close(
            Long shiftId,
            CloseCashierShiftRequest request,
            User currentUser) {
        User actor = requireOperator(currentUser);
        CashierShift shift = shiftRepository.findByIdForUpdate(shiftId)
                .orElseThrow(() -> new AppException(ErrorCode.CASHIER_SHIFT_NOT_FOUND));
        ensureOwner(actor, shift);
        ensureOpen(shift);

        BigDecimal expected = expectedCash(shift.getId());

        Instant now = clock.instant();
        shift.setExpectedCashAmount(expected);
        // Compatibility columns remain populated, but operators no longer
        // re-enter a total already derived from completed transactions.
        shift.setCountedCashAmount(expected);
        shift.setVarianceAmount(BigDecimal.ZERO);
        shift.setClosedBy(actor);
        shift.setClosedByName(displayName(actor));
        shift.setClosedByRole(actor.getType().name());
        shift.setClosedAtUtc(now);
        shift.setCloseNote(trimToNull(request.getNote()));
        shift.setStatus(CashierShiftStatus.CLOSED);
        shift = shiftRepository.saveAndFlush(shift);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("cashNetAmount", expected);
        detail.put("trackingMode", "AUTOMATIC");
        auditService.recordTargetForUser(
                actor,
                "CASHIER_SHIFT",
                String.valueOf(shift.getId()),
                ReservationAuditAction.CASHIER_SHIFT_CLOSED,
                "Kết thúc ca thu ngân " + shift.getShiftCode(),
                detail);
        return toResponse(shift, true);
    }

    /** Records a successful CASH payment in the caller's transaction. */
    @Transactional
    public void recordCashPayment(PaymentTransaction payment, User currentUser) {
        if (payment == null || payment.getProvider() != PaymentProvider.CASH) return;
        User actor = requireOperator(currentUser);
        BigDecimal amount = BigDecimal.valueOf(
                payment.getReceivedAmount() != null
                        ? payment.getReceivedAmount()
                        : payment.getAmount());
        CashierShift shift = shiftRepository
                .findActiveByUserIdForUpdate(actor.getId(), ACTIVE_STATUSES)
                .filter(activeShift -> activeShift.getStatus() == CashierShiftStatus.OPEN)
                .orElse(null);
        // STAFF physically operates a cashier drawer, so an open shift remains
        // mandatory. ADMIN can resolve a reservation without opening a front-desk
        // shift; the caller still persists the payment, financial journal and
        // reservation audit in the same transaction.
        if (shift == null) {
            if (actor.getType() == UserType.ADMIN) return;
            throw new AppException(ErrorCode.CASHIER_SHIFT_REQUIRED);
        }
        CashMovement existing = movementRepository
                .findBySourceTypeAndSourceIdAndMovementType(
                        CashMovementSourceType.PAYMENT_TRANSACTION,
                        payment.getId(),
                        CashMovementType.CASH_PAYMENT)
                .orElse(null);
        if (existing != null) return;

        CashMovement movement = appendMovement(
                shift,
                CashMovementType.CASH_PAYMENT,
                CashMovementDirection.IN,
                amount,
                CashMovementSourceType.PAYMENT_TRANSACTION,
                payment.getId(),
                payment.getReservation(),
                payment,
                null,
                actor,
                "Thu tiền mặt cho đơn " + payment.getReservation().getReservationCode());
        auditMovement(actor, movement, "Thu tiền mặt");
    }

    /** Records a completed counter CASH refund in the caller's transaction. */
    @Transactional
    public void recordCashRefund(PaymentRefund refund, User currentUser) {
        if (refund == null
                || refund.getChannel() != RefundChannel.CASH_AT_COUNTER
                || refund.getStatus() != RefundStatus.SUCCEEDED) return;
        User actor = requireOperator(currentUser);
        BigDecimal amount = BigDecimal.valueOf(
                refund.getActualRefundAmount() != null
                        ? refund.getActualRefundAmount()
                        : refund.getAmount());
        CashierShift shift = shiftRepository
                .findActiveByUserIdForUpdate(actor.getId(), ACTIVE_STATUSES)
                .filter(activeShift -> activeShift.getStatus() == CashierShiftStatus.OPEN)
                .orElse(null);
        // STAFF must work inside a cashier shift. ADMIN may resolve an
        // exceptional refund without opening a front-desk shift; the refund
        // remains part of the canonical financial report.
        if (shift == null) {
            if (actor.getType() == UserType.ADMIN) return;
            throw new AppException(ErrorCode.CASHIER_SHIFT_REQUIRED);
        }
        CashMovement existing = movementRepository
                .findBySourceTypeAndSourceIdAndMovementType(
                        CashMovementSourceType.PAYMENT_REFUND,
                        refund.getId(),
                        CashMovementType.CASH_REFUND)
                .orElse(null);
        if (existing != null) return;

        CashMovement movement = appendMovement(
                shift,
                CashMovementType.CASH_REFUND,
                CashMovementDirection.OUT,
                amount,
                CashMovementSourceType.PAYMENT_REFUND,
                refund.getId(),
                refund.getReservation(),
                refund.getPaymentTransaction(),
                refund,
                actor,
                "Hoàn tiền mặt cho khách");
        auditMovement(actor, movement, "Hoàn tiền mặt");
    }

    private CashMovementResponse addManualMovement(
            Long shiftId,
            CashMovementRequest request,
            String idempotencyKey,
            User currentUser,
            CashMovementType type,
            CashMovementDirection direction) {
        User actor = requireOperator(currentUser);
        CashierShift shift = shiftRepository.findByIdForUpdate(shiftId)
                .orElseThrow(() -> new AppException(ErrorCode.CASHIER_SHIFT_NOT_FOUND));
        ensureOwner(actor, shift);
        ensureOpen(shift);
        String sourceId = shiftId + ":" + idempotencyKey.trim();
        CashMovement existing = movementRepository
                .findBySourceTypeAndSourceIdAndMovementType(
                        CashMovementSourceType.MANUAL, sourceId, type)
                .orElse(null);
        if (existing != null) return toResponse(existing);

        CashMovement movement = appendMovement(
                shift,
                type,
                direction,
                normalize(request.getAmount()),
                CashMovementSourceType.MANUAL,
                sourceId,
                null,
                null,
                null,
                actor,
                request.getReason().trim());
        auditMovement(actor, movement, direction == CashMovementDirection.IN
                ? "Thu tiền khác" : "Chi tiền khác");
        return toResponse(movement);
    }

    private CashMovement appendMovement(
            CashierShift shift,
            CashMovementType type,
            CashMovementDirection direction,
            BigDecimal amount,
            CashMovementSourceType sourceType,
            String sourceId,
            com.hotel.backend.entity.Reservation reservation,
            PaymentTransaction payment,
            PaymentRefund refund,
            User actor,
            String reason) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 0) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Số tiền mặt phải là số nguyên VND lớn hơn 0");
        }
        CashMovement movement = CashMovement.builder()
                .cashierShift(shift)
                .movementType(type)
                .direction(direction)
                .amount(amount)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .reservation(reservation)
                .paymentTransaction(payment)
                .refund(refund)
                .createdBy(actor)
                .createdByName(displayName(actor))
                .createdByRole(actor.getType().name())
                .reason(trimToNull(reason))
                .occurredAtUtc(clock.instant())
                .build();
        return movementRepository.saveAndFlush(movement);
    }

    private void auditMovement(User actor, CashMovement movement, String label) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("shiftId", movement.getCashierShift().getId());
        detail.put("shiftCode", movement.getCashierShift().getShiftCode());
        detail.put("movementType", movement.getMovementType().name());
        detail.put("direction", movement.getDirection().name());
        detail.put("amount", movement.getAmount());
        if (movement.getReservation() != null) {
            detail.put("reservationId", movement.getReservation().getId());
            detail.put("reservationCode", movement.getReservation().getReservationCode());
        }
        auditService.recordTargetForUser(
                actor,
                "CASH_MOVEMENT",
                String.valueOf(movement.getId()),
                ReservationAuditAction.CASH_MOVEMENT_RECORDED,
                label + " " + movement.getAmount() + " VND",
                detail);
    }

    private CashierShift requireShift(Long shiftId) {
        return shiftRepository.findById(shiftId)
                .orElseThrow(() -> new AppException(ErrorCode.CASHIER_SHIFT_NOT_FOUND));
    }

    private User requireOperator(User user) {
        if (user == null || user.getId() == null || user.getType() == null
                || !List.of(UserType.ADMIN, UserType.STAFF).contains(user.getType())) {
            throw new AppException(ErrorCode.CASHIER_SHIFT_FORBIDDEN);
        }
        return user;
    }

    private void ensureCanView(User actor, CashierShift shift) {
        if (actor.getType() != UserType.ADMIN
                && !actor.getId().equals(shift.getOpenedBy().getId())) {
            throw new AppException(ErrorCode.CASHIER_SHIFT_FORBIDDEN);
        }
    }

    private void ensureOwner(User actor, CashierShift shift) {
        if (!actor.getId().equals(shift.getOpenedBy().getId())) {
            throw new AppException(
                    ErrorCode.CASHIER_SHIFT_FORBIDDEN,
                    "Mỗi nhân viên chỉ được thao tác ca tiền mặt của chính mình");
        }
    }

    private void ensureOpen(CashierShift shift) {
        if (shift.getStatus() != CashierShiftStatus.OPEN) {
            throw new AppException(ErrorCode.CASHIER_SHIFT_CLOSED);
        }
    }

    private BigDecimal expectedCash(Long shiftId) {
        BigDecimal value = movementRepository.calculateExpectedCash(shiftId);
        return value == null ? BigDecimal.ZERO : value;
    }

    private CashierShiftResponse toResponse(CashierShift shift, boolean includeMovements) {
        return toResponse(shift, includeMovements, null, null);
    }

    private CashierShiftResponse toResponse(
            CashierShift shift,
            boolean includeMovements,
            BigDecimal expectedOverride) {
        return toResponse(shift, includeMovements, expectedOverride, null);
    }

    private CashierShiftResponse toResponse(
            CashierShift shift,
            boolean includeMovements,
            BigDecimal expectedOverride,
            Long movementCountOverride) {
        List<CashMovementResponse> movements = includeMovements
                ? movementRepository.findAllByCashierShiftIdOrderByOccurredAtUtcAscIdAsc(shift.getId())
                        .stream().map(this::toResponse).toList()
                : List.of();
        BigDecimal expected = expectedOverride != null
                ? expectedOverride
                : shift.getStatus() == CashierShiftStatus.OPEN
                    || shift.getStatus() == CashierShiftStatus.CLOSING
                    ? expectedCash(shift.getId())
                    : shift.getExpectedCashAmount();
        BigDecimal cashIncome = movements.stream()
                .filter(movement ->
                        movement.movementType() == CashMovementType.CASH_PAYMENT
                                && movement.direction() == CashMovementDirection.IN)
                .map(CashMovementResponse::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cashRefund = movements.stream()
                .filter(movement ->
                        movement.movementType() == CashMovementType.CASH_REFUND
                                && movement.direction() == CashMovementDirection.OUT)
                .map(CashMovementResponse::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal transferIncome = BigDecimal.ZERO;
        BigDecimal transferRefund = BigDecimal.ZERO;
        Instant reportingTo = shift.getClosedAtUtc() != null
                ? shift.getClosedAtUtc()
                : clock.instant();
        if (includeMovements
                && moneyReportService != null
                && shift.getOpenedAtUtc().isBefore(reportingTo)) {
            MoneyReportResponse.Breakdown online =
                    moneyReportService.summarizeWindow(
                            shift.getOpenedAtUtc(), reportingTo);
            // Cash belongs to the operator through cash_movements. Online
            // transfers have no cashier actor, so they are informational for
            // the same time window and are not used to grade the operator.
            transferIncome = online.transferIncome();
            transferRefund = online.transferRefund();
        }
        BigDecimal totalIncome = cashIncome.add(transferIncome);
        BigDecimal totalRefund = cashRefund.add(transferRefund);
        return new CashierShiftResponse(
                shift.getId(),
                shift.getShiftCode(),
                shift.getBusinessDate(),
                shift.getStatus(),
                shift.getOpenedBy().getId(),
                shift.getOpenedByName(),
                shift.getOpenedByRole(),
                shift.getOpenedAtUtc(),
                shift.getClosedBy() != null ? shift.getClosedBy().getId() : null,
                shift.getClosedByName(),
                shift.getClosedByRole(),
                shift.getClosedAtUtc(),
                shift.getOpeningCashAmount(),
                expected,
                shift.getCountedCashAmount(),
                shift.getVarianceAmount(),
                shift.getNote(),
                shift.getCloseNote(),
                includeMovements
                        ? movements.size()
                        : movementCountOverride != null
                            ? movementCountOverride
                            : movementRepository.countByCashierShiftId(shift.getId()),
                movements,
                cashIncome,
                transferIncome,
                totalIncome,
                cashRefund,
                transferRefund,
                totalRefund,
                totalIncome.subtract(totalRefund));
    }

    private CashMovementResponse toResponse(CashMovement movement) {
        return new CashMovementResponse(
                movement.getId(),
                movement.getCashierShift().getId(),
                movement.getMovementType(),
                movement.getDirection(),
                movement.getAmount(),
                movement.getSourceType(),
                movement.getSourceId(),
                movement.getReservation() != null ? movement.getReservation().getId() : null,
                movement.getReservation() != null ? movement.getReservation().getReservationCode() : null,
                movement.getPaymentTransaction() != null ? movement.getPaymentTransaction().getId() : null,
                movement.getRefund() != null ? movement.getRefund().getId() : null,
                movement.getCreatedBy().getId(),
                movement.getCreatedByName(),
                movement.getCreatedByRole(),
                movement.getReason(),
                movement.getOccurredAtUtc());
    }

    private String generateShiftCode(User actor, Instant now) {
        String date = LocalDate.ofInstant(now, HOTEL_ZONE).toString().replace("-", "");
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "CS-" + date + "-" + actor.getId() + "-" + random;
    }

    private BigDecimal normalize(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private BigDecimal nullSafeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String displayName(User user) {
        if (hasText(user.getFullName())) return user.getFullName().trim();
        if (hasText(user.getUsername())) return user.getUsername().trim();
        return "Người dùng #" + user.getId();
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
