package com.hotel.backend.service;

import com.hotel.backend.constant.AddOnPricingUnit;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.ReservationServiceOrigin;
import com.hotel.backend.constant.ReservationServiceStatus;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.request.ReservationServiceStatusRequest;
import com.hotel.backend.dto.request.ServiceOrderRequest;
import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.dto.response.ReservationServiceResponse;
import com.hotel.backend.entity.AddOnService;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.ReservationServiceOrder;
import com.hotel.backend.entity.User;
import com.hotel.backend.event.CheckoutReconciliationChangedEvent;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.AddOnServiceRepository;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.repository.ReservationServiceOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReservationAddOnService {

    private static final Set<ReservationServiceStatus> COMMITTED_STATUSES =
            Set.of(ReservationServiceStatus.CONFIRMED, ReservationServiceStatus.FULFILLED);
    private static final Set<ReservationServiceStatus> CHECKOUT_BLOCKING_STATUSES =
            Set.of(ReservationServiceStatus.REQUESTED, ReservationServiceStatus.CONFIRMED);

    private final AddOnServiceRepository catalogRepository;
    private final ReservationServiceOrderRepository orderRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentReservationAccessPolicy accessPolicy;
    private final ReservationAuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public BookingQuote quoteBookingTime(
            List<ServiceOrderRequest> requests,
            int guestCount,
            LocalDateTime checkIn,
            LocalDateTime checkOut) {
        return quote(
                requests,
                guestCount,
                checkIn,
                checkOut,
                ReservationServiceOrigin.BOOKING_TIME,
                true,
                null);
    }

    /**
     * Pricing V2 entry point. PER_NIGHT services use the package-cycle count
     * already decided by the room-pricing engine instead of rounding the stay
     * duration independently.
     */
    @Transactional
    public BookingQuote quoteBookingTimeForPackageCycles(
            List<ServiceOrderRequest> requests,
            int guestCount,
            int packageCycles) {
        return quote(
                requests,
                guestCount,
                null,
                null,
                ReservationServiceOrigin.BOOKING_TIME,
                true,
                packageCycles);
    }

    /**
     * Read-only preview used by the public pricing quote.
     *
     * <p>The catalog is deliberately not locked here. Reservation creation
     * calls {@link #quoteBookingTime(List, int, LocalDateTime, LocalDateTime)}
     * again with row locks before committing any amount.</p>
     */
    @Transactional(readOnly = true)
    public BookingQuote previewBookingTime(
            List<ServiceOrderRequest> requests,
            int guestCount,
            LocalDateTime checkIn,
            LocalDateTime checkOut) {
        return quote(
                requests,
                guestCount,
                checkIn,
                checkOut,
                ReservationServiceOrigin.BOOKING_TIME,
                false,
                null);
    }

    /**
     * Read-only Pricing V2 preview using the engine's authoritative package
     * cycles.
     */
    @Transactional(readOnly = true)
    public BookingQuote previewBookingTimeForPackageCycles(
            List<ServiceOrderRequest> requests,
            int guestCount,
            int packageCycles) {
        return quote(
                requests,
                guestCount,
                null,
                null,
                ReservationServiceOrigin.BOOKING_TIME,
                false,
                packageCycles);
    }

    @Transactional
    public List<ReservationServiceResponse> attachBookingTime(
            Reservation reservation,
            BookingQuote quote,
            User requestedBy) {
        if (quote == null || quote.lines().isEmpty()) return List.of();
        Instant now = Instant.now();
        List<ReservationServiceResponse> responses = new ArrayList<>();
        for (PricedService line : quote.lines()) {
            ReservationServiceOrder saved = orderRepository.save(toEntity(
                    reservation,
                    line,
                    ReservationServiceOrigin.BOOKING_TIME,
                    ReservationServiceStatus.CONFIRMED,
                    requestedBy,
                    now));
            responses.add(ReservationServiceResponse.from(saved));
            auditOrder(saved, ReservationAuditAction.RESERVATION_SERVICE_ADDED,
                    "Thêm dịch vụ khi đặt phòng");
            auditOrder(saved, ReservationAuditAction.RESERVATION_SERVICE_CONFIRMED,
                    "Dịch vụ đặt trước được xác nhận cùng reservation");
        }
        return responses;
    }

    @Transactional(readOnly = true)
    public List<ReservationServiceResponse> listForReservation(
            Long reservationId,
            User currentUser,
            String guestToken) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        accessPolicy.ensureCanAccessReservation(currentUser, reservation, guestToken);
        return orderRepository.findDetailedByReservationId(reservationId).stream()
                .map(ReservationServiceResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReservationServiceResponse> listInternal(Long reservationId) {
        return orderRepository.findDetailedByReservationId(reservationId).stream()
                .map(ReservationServiceResponse::from)
                .toList();
    }

    @Transactional
    public ReservationServiceResponse requestInStay(
            Long reservationId,
            ServiceOrderRequest request,
            User currentUser,
            String guestToken) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        accessPolicy.ensureCanAccessReservation(currentUser, reservation, guestToken);
        validateCanRequestInStay(reservation, currentUser);
        BookingQuote quote = quote(
                List.of(request),
                requireGuestCount(reservation),
                effectiveInStayStart(reservation),
                reservation.getCheckOut(),
                ReservationServiceOrigin.IN_STAY,
                false,
                null);
        PricedService line = quote.lines().get(0);
        Instant now = Instant.now();
        ReservationServiceOrder saved = orderRepository.save(toEntity(
                reservation,
                line,
                ReservationServiceOrigin.IN_STAY,
                ReservationServiceStatus.REQUESTED,
                currentUser,
                now));
        auditOrder(saved, ReservationAuditAction.RESERVATION_SERVICE_ADDED,
                "Gửi yêu cầu dịch vụ trong kỳ lưu trú");
        return ReservationServiceResponse.from(saved);
    }

    @Transactional
    public ReservationServiceResponse updateStatus(
            Long reservationId,
            Long orderId,
            ReservationServiceStatusRequest request,
            User currentUser) {
        requireOperator(currentUser);
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        ReservationServiceOrder order = orderRepository
                .findByIdAndReservationIdForUpdate(orderId, reservationId)
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy yêu cầu dịch vụ"));
        ReservationServiceStatus previous = order.getStatus();
        ReservationServiceStatus target = request.getStatus();
        if (target == previous) {
            return ReservationServiceResponse.from(order);
        }
        validateTransition(previous, target, request.getCancellationReason());
        Instant now = Instant.now();
        boolean financialChanged = false;
        switch (target) {
            case CONFIRMED -> {
                validateReservationCanConfirmService(reservation);
                order.setConfirmedAtUtc(now);
                reservation.setTotalAmount(safeMoney(reservation.getTotalAmount())
                        .add(order.getTotalPrice()));
                financialChanged = true;
            }
            case FULFILLED -> order.setFulfilledAtUtc(now);
            case CANCELLED -> {
                order.setCancelledAtUtc(now);
                order.setCancellationReason(request.getCancellationReason().trim());
                if (previous == ReservationServiceStatus.CONFIRMED) {
                    reservation.setTotalAmount(safeMoney(reservation.getTotalAmount())
                            .subtract(order.getTotalPrice()));
                    financialChanged = true;
                }
            }
            default -> throw new AppException(
                    ErrorCode.INVALID_REQUEST, "Không thể chuyển về trạng thái yêu cầu");
        }
        order.setStatus(target);
        order.setLastUpdatedByUser(currentUser);
        reservation.setLastActivityAt(LocalDateTime.now());
        orderRepository.save(order);
        reservationRepository.save(reservation);
        auditOrder(order, auditAction(target), auditMessage(target));
        if (financialChanged) {
            eventPublisher.publishEvent(new CheckoutReconciliationChangedEvent(
                    reservationId, "ADD_ON_SERVICE_" + target.name()));
        }
        return ReservationServiceResponse.from(order);
    }

    @Transactional(readOnly = true)
    public BigDecimal committedTotal(Long reservationId) {
        return safeMoney(orderRepository.sumTotalPriceByReservationIdAndStatusIn(
                reservationId, COMMITTED_STATUSES));
    }

    /**
     * Extends only still-confirmed booking-time services charged per night.
     * The original unit-price snapshot remains authoritative; catalog changes
     * are deliberately ignored. Fulfilled services are not reopened
     * automatically and require a new in-stay request if needed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExtensionAdjustment repriceBookingTimeForExtension(
            Reservation reservation,
            LocalDateTime newCheckOut,
            int newMultiplier) {
        if (newMultiplier < 1) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "Số chu kỳ tính giá dịch vụ phải lớn hơn 0");
        }
        BigDecimal additionalCharge = BigDecimal.ZERO;
        int updatedOrders = 0;
        String correlationId = UUID.randomUUID().toString();

        for (ReservationServiceOrder order :
                orderRepository.findByReservationIdForUpdate(
                        reservation.getId())) {
            if (order.getOrigin()
                    != ReservationServiceOrigin.BOOKING_TIME
                    || order.getPricingUnitSnapshot()
                    != AddOnPricingUnit.PER_NIGHT
                    || order.getStatus()
                    != ReservationServiceStatus.CONFIRMED
                    || newMultiplier
                    <= order.getPricingMultiplier()) {
                continue;
            }

            int oldMultiplier = order.getPricingMultiplier();
            int oldBillableQuantity = order.getBillableQuantity();
            BigDecimal oldTotal = order.getTotalPrice();
            int newBillableQuantity = Math.multiplyExact(
                    order.getQuantity(), newMultiplier);
            BigDecimal newTotal = order.getUnitPriceSnapshot()
                    .multiply(BigDecimal.valueOf(newBillableQuantity))
                    .setScale(2);
            BigDecimal delta = newTotal.subtract(oldTotal);

            order.setPricingMultiplier(newMultiplier);
            order.setBillableQuantity(newBillableQuantity);
            order.setTotalPrice(newTotal);
            orderRepository.save(order);
            additionalCharge = additionalCharge.add(delta);
            updatedOrders++;

            auditService.record(
                    reservation,
                    "RESERVATION_SERVICE",
                    String.valueOf(order.getId()),
                    ReservationAuditAction
                            .RESERVATION_SERVICE_REPRICED,
                    "Điều chỉnh dịch vụ theo số đêm sau gia hạn",
                    Map.of(
                            "pricingMultiplier", oldMultiplier,
                            "billableQuantity", oldBillableQuantity,
                            "totalPrice", oldTotal),
                    Map.of(
                            "pricingMultiplier", newMultiplier,
                            "billableQuantity", newBillableQuantity,
                            "totalPrice", newTotal),
                    Map.of(
                            "serviceCode",
                            order.getServiceCodeSnapshot(),
                            "pricingUnit",
                            order.getPricingUnitSnapshot(),
                            "newCheckOut",
                            newCheckOut),
                    correlationId,
                    "RESERVATION_SERVICE_REPRICED:"
                            + reservation.getId()
                            + ":"
                            + order.getId()
                            + ":"
                            + newMultiplier);
        }
        return new ExtensionAdjustment(
                additionalCharge.setScale(2), updatedOrders);
    }

    @Transactional(readOnly = true)
    public boolean hasCheckoutBlockers(Long reservationId) {
        return orderRepository.existsByReservationIdAndStatusIn(
                reservationId, CHECKOUT_BLOCKING_STATUSES);
    }

    @Transactional(readOnly = true)
    public List<ReservationServiceResponse> checkoutBlockers(Long reservationId) {
        return orderRepository
                .findByReservationIdAndStatusInOrderByCreatedAtAscIdAsc(
                        reservationId, CHECKOUT_BLOCKING_STATUSES)
                .stream()
                .map(ReservationServiceResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReservationResponse enrich(ReservationResponse response) {
        if (response == null || response.getId() == null) return response;
        List<ReservationServiceResponse> services = orderRepository
                .findDetailedByReservationId(response.getId())
                .stream()
                .map(ReservationServiceResponse::from)
                .toList();
        response.setServices(services);
        response.setAddOnServiceAmount(services.stream()
                .filter(item -> COMMITTED_STATUSES.contains(item.getStatus()))
                .map(ReservationServiceResponse::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return response;
    }

    private BookingQuote quote(
            List<ServiceOrderRequest> requests,
            int guestCount,
            LocalDateTime checkIn,
            LocalDateTime checkOut,
            ReservationServiceOrigin origin,
            boolean lockCatalog,
            Integer packageCyclesOverride) {
        if (requests == null || requests.isEmpty()) {
            return new BookingQuote(List.of(), BigDecimal.ZERO);
        }
        boolean invalidStayWindow = packageCyclesOverride == null
                && (checkIn == null
                        || checkOut == null
                        || !checkOut.isAfter(checkIn));
        if (guestCount < 1
                || invalidStayWindow
                || (packageCyclesOverride != null
                        && packageCyclesOverride < 1)) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Thông tin tính giá dịch vụ không hợp lệ");
        }
        Set<Long> uniqueIds = new HashSet<>();
        for (ServiceOrderRequest request : requests) {
            if (request == null || request.getServiceId() == null) {
                throw new AppException(
                        ErrorCode.INVALID_REQUEST,
                        "Danh sách dịch vụ có mục trùng hoặc thiếu mã");
            }
        }
        List<ServiceOrderRequest> sorted = requests.stream()
                .sorted(Comparator.comparing(ServiceOrderRequest::getServiceId))
                .toList();
        List<PricedService> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int nights = packageCyclesOverride != null
                ? packageCyclesOverride
                : chargeableNights(checkIn, checkOut);
        for (ServiceOrderRequest request : sorted) {
            if (request == null || request.getServiceId() == null
                    || !uniqueIds.add(request.getServiceId())) {
                throw new AppException(
                        ErrorCode.INVALID_REQUEST, "Danh sách dịch vụ có mục trùng hoặc thiếu mã");
            }
            AddOnService service = lockCatalog
                    ? catalogRepository.findByIdForUpdate(request.getServiceId())
                            .orElseThrow(() -> serviceNotFound())
                    : catalogRepository.findById(request.getServiceId())
                            .orElseThrow(() -> serviceNotFound());
            validateAvailable(service, origin);
            int quantity = normalizeQuantity(service.getPricingUnit(), request.getQuantity(), guestCount);
            int multiplier = service.getPricingUnit() == AddOnPricingUnit.PER_NIGHT ? nights : 1;
            int billableQuantity = Math.multiplyExact(quantity, multiplier);
            BigDecimal lineTotal = service.getPrice()
                    .multiply(BigDecimal.valueOf(billableQuantity))
                    .setScale(2);
            PricedService line = new PricedService(
                    service,
                    quantity,
                    multiplier,
                    billableQuantity,
                    lineTotal,
                    normalizeNotes(request.getNotes()));
            lines.add(line);
            total = total.add(lineTotal);
        }
        return new BookingQuote(List.copyOf(lines), total);
    }

    private ReservationServiceOrder toEntity(
            Reservation reservation,
            PricedService line,
            ReservationServiceOrigin origin,
            ReservationServiceStatus status,
            User requestedBy,
            Instant now) {
        AddOnService service = line.service();
        return ReservationServiceOrder.builder()
                .reservation(reservation)
                .service(service)
                .origin(origin)
                .serviceCodeSnapshot(service.getCode())
                .serviceNameSnapshot(service.getName())
                .serviceNameEnSnapshot(service.getNameEn())
                .serviceImageUrlSnapshot(service.getImageUrl())
                .unitPriceSnapshot(service.getPrice())
                .pricingUnitSnapshot(service.getPricingUnit())
                .quantity(line.quantity())
                .pricingMultiplier(line.multiplier())
                .billableQuantity(line.billableQuantity())
                .totalPrice(line.totalPrice())
                .status(status)
                .notes(line.notes())
                .requestedAtUtc(now)
                .confirmedAtUtc(status == ReservationServiceStatus.CONFIRMED ? now : null)
                .requestedByUser(requestedBy)
                .lastUpdatedByUser(requestedBy)
                .build();
    }

    private void validateAvailable(AddOnService service, ReservationServiceOrigin origin) {
        boolean flowEnabled = origin == ReservationServiceOrigin.BOOKING_TIME
                ? service.isBookingEnabled()
                : service.isInStayEnabled();
        if (!service.isActive() || !flowEnabled) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST, "Dịch vụ không khả dụng cho thời điểm này");
        }
    }

    private void validateCanRequestInStay(Reservation reservation, User currentUser) {
        boolean operator = isOperator(currentUser);
        boolean allowed = operator
                ? List.of(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN)
                        .contains(reservation.getStatus())
                : reservation.getStatus() == ReservationStatus.CHECKED_IN;
        if (!allowed) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    operator
                            ? "Chỉ được thêm dịch vụ cho đơn đã xác nhận hoặc đang lưu trú"
                            : "Khách chỉ được yêu cầu dịch vụ khi đang lưu trú");
        }
    }

    private void validateReservationCanConfirmService(Reservation reservation) {
        if (!List.of(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN)
                .contains(reservation.getStatus())) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "Reservation không còn ở trạng thái có thể xác nhận dịch vụ");
        }
    }

    private void validateTransition(
            ReservationServiceStatus previous,
            ReservationServiceStatus target,
            String cancellationReason) {
        boolean allowed = (previous == ReservationServiceStatus.REQUESTED
                        && List.of(ReservationServiceStatus.CONFIRMED, ReservationServiceStatus.CANCELLED)
                                .contains(target))
                || (previous == ReservationServiceStatus.CONFIRMED
                        && List.of(ReservationServiceStatus.FULFILLED, ReservationServiceStatus.CANCELLED)
                                .contains(target));
        if (!allowed) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "Chuyển trạng thái dịch vụ không hợp lệ");
        }
        if (target == ReservationServiceStatus.CANCELLED
                && (cancellationReason == null || cancellationReason.isBlank())) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Phải nhập lý do hủy dịch vụ");
        }
    }

    private int normalizeQuantity(
            AddOnPricingUnit unit,
            Integer requestedQuantity,
            int guestCount) {
        if (unit == AddOnPricingUnit.PER_ORDER) return 1;
        if (unit == AddOnPricingUnit.PER_GUEST) {
            int quantity = requestedQuantity == null ? guestCount : requestedQuantity;
            if (quantity < 1 || quantity > guestCount) {
                throw new AppException(
                        ErrorCode.INVALID_REQUEST,
                        "Số suất theo khách phải từ 1 đến tổng số khách của đơn");
            }
            return quantity;
        }
        int quantity = requestedQuantity == null ? 1 : requestedQuantity;
        if (quantity < 1 || quantity > 99) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Số lượng dịch vụ phải từ 1 đến 99");
        }
        return quantity;
    }

    private int chargeableNights(LocalDateTime checkIn, LocalDateTime checkOut) {
        long minutes = Duration.between(checkIn, checkOut).toMinutes();
        return Math.max(1, Math.toIntExact((minutes + 1439L) / 1440L));
    }

    private LocalDateTime effectiveInStayStart(Reservation reservation) {
        LocalDateTime start = reservation.getActualCheckIn() != null
                ? reservation.getActualCheckIn()
                : LocalDateTime.now();
        return start.isBefore(reservation.getCheckOut())
                ? start
                : reservation.getCheckOut().minusMinutes(1);
    }

    private int requireGuestCount(Reservation reservation) {
        if (reservation.getGuestCount() == null || reservation.getGuestCount() < 1) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Đơn chưa có số khách hợp lệ");
        }
        return reservation.getGuestCount();
    }

    private void requireOperator(User user) {
        if (!isOperator(user)) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Chỉ Staff/Admin được xử lý dịch vụ");
        }
    }

    private boolean isOperator(User user) {
        return user != null && List.of(UserType.STAFF, UserType.ADMIN).contains(user.getType());
    }

    private String normalizeNotes(String notes) {
        return notes == null || notes.isBlank() ? null : notes.trim();
    }

    private BigDecimal safeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private AppException serviceNotFound() {
        return new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy dịch vụ");
    }

    private void auditOrder(
            ReservationServiceOrder order,
            ReservationAuditAction action,
            String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("serviceOrderId", order.getId());
        detail.put("serviceCode", order.getServiceCodeSnapshot());
        detail.put("origin", order.getOrigin());
        detail.put("status", order.getStatus());
        detail.put("totalPrice", order.getTotalPrice());
        auditService.record(
                order.getReservation(),
                "RESERVATION_SERVICE",
                String.valueOf(order.getId()),
                action,
                message,
                null,
                null,
                detail,
                null,
                null);
    }

    private ReservationAuditAction auditAction(ReservationServiceStatus status) {
        return switch (status) {
            case CONFIRMED -> ReservationAuditAction.RESERVATION_SERVICE_CONFIRMED;
            case FULFILLED -> ReservationAuditAction.RESERVATION_SERVICE_FULFILLED;
            case CANCELLED -> ReservationAuditAction.RESERVATION_SERVICE_CANCELLED;
            default -> ReservationAuditAction.RESERVATION_SERVICE_ADDED;
        };
    }

    private String auditMessage(ReservationServiceStatus status) {
        return switch (status) {
            case CONFIRMED -> "Xác nhận yêu cầu dịch vụ";
            case FULFILLED -> "Xác nhận đã phục vụ dịch vụ";
            case CANCELLED -> "Hủy yêu cầu dịch vụ";
            default -> "Cập nhật yêu cầu dịch vụ";
        };
    }

    public record BookingQuote(List<PricedService> lines, BigDecimal totalAmount) {}

    public record ExtensionAdjustment(
            BigDecimal additionalCharge,
            int updatedOrders) {}

    public record PricedService(
            AddOnService service,
            int quantity,
            int multiplier,
            int billableQuantity,
            BigDecimal totalPrice,
            String notes) {}
}
