package com.hotel.backend.controller;

import com.hotel.backend.dto.request.AssignRoomRequest;
import com.hotel.backend.dto.request.CheckoutRefundRequest;
import com.hotel.backend.dto.request.CancelReservationRequest;
import com.hotel.backend.dto.request.RejectReservationRequest;
import com.hotel.backend.dto.request.CreateReservationRequest;
import com.hotel.backend.dto.request.CreateWalkInReservationRequest;
import com.hotel.backend.dto.request.CreateWalkInCheckedInRequest;
import com.hotel.backend.dto.request.ReservationRefundRequest;
import com.hotel.backend.dto.request.RefundRecipientRequest;
import com.hotel.backend.dto.request.UpdateReservationRequest;
import com.hotel.backend.dto.request.ManualRoomHoldReleaseRequest;
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.AvailabilityResponse;
import com.hotel.backend.dto.response.FinalPaymentResponse;
import com.hotel.backend.dto.response.CheckoutReconciliationResponse;
import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.dto.response.RefundRecipientResponse;
import com.hotel.backend.dto.response.ReservationInvoiceResponse;
import com.hotel.backend.dto.response.ReservationAuditLogResponse;
import com.hotel.backend.dto.response.PaymentResponse;
import com.hotel.backend.dto.response.WalkInReservationResponse;
import com.hotel.backend.constant.WalkInPaymentOption;
import com.hotel.backend.service.ReservationAuditService;
import com.hotel.backend.service.ReservationService;
import com.hotel.backend.service.RefundRecipientService;
import com.hotel.backend.service.IdempotencyService;
import com.hotel.backend.service.PaymentService;
import com.hotel.backend.service.PaymentSessionExpiryService;
import com.hotel.backend.service.BusinessMetricService;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.util.VNPayUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;


import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
@Slf4j(topic = "RESERVATION-CONTROLLER")
public class ReservationController {

    private final ReservationService reservationService;
    private final ReservationAuditService reservationAuditService;
    private final RefundRecipientService refundRecipientService;
    private final IdempotencyService idempotencyService;
    private final PaymentService paymentService;
    private final PaymentSessionExpiryService paymentSessionExpiryService;
    private final BusinessMetricService businessMetrics;

    // ── Public: kiểm tra phòng trống ─────────────────────────────────────────
    @Operation(summary = "Check room availability", description = "API check available room types for the selected check-in and check-out time")
    @GetMapping("/availability")
public ApiResponse<List<AvailabilityResponse>> checkAvailability(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime checkIn,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime checkOut) {
    return ApiResponse.success(reservationService.checkAvailability(checkIn, checkOut));
}

    // ── Customer: tạo đặt phòng ───────────────────────────────────────────────
    @Operation(summary = "Create Reservation", description = "API create a new reservation for the current customer")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReservationResponse> createReservation(
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateReservationRequest request) {
        String guestToken = currentUser == null
                ? idempotencyService.guestReservationToken(idempotencyKey)
                : null;
        String actorScope = idempotencyService.actorScope(currentUser, guestToken);
        ReservationResponse response = idempotencyService.execute(
                idempotencyKey,
                "RESERVATION_CREATE",
                actorScope,
                request,
                "RESERVATION",
                () -> reservationService.createReservation(currentUser, request, guestToken),
                item -> String.valueOf(item.getId()),
                reservationId -> replayCreatedReservation(
                        Long.valueOf(reservationId), currentUser, guestToken));
        return ApiResponse.success("Đặt phòng thành công", response);
    }

    private ReservationResponse replayCreatedReservation(
            Long reservationId,
            com.hotel.backend.entity.User currentUser,
            String guestToken) {
        ReservationResponse response = reservationService.getReservation(
                reservationId, currentUser, guestToken);
        if (currentUser == null) {
            response.setGuestToken(guestToken);
        }
        return response;
    }
    // ── Staff: khách vãng lai đến trực tiếp, tạo + confirm luôn ─────────────────
    @Operation(summary = "Create walk-in Reservation", description = "API create and confirm a walk-in reservation for a customer")
    @PostMapping("/walk-in")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReservationResponse> createWalkInReservation(
            @Valid @RequestBody CreateWalkInReservationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser) {
        ReservationResponse response = idempotencyService.execute(
                idempotencyKey,
                "WALK_IN_CREATE",
                idempotencyService.actorScope(currentUser, null),
                request,
                "RESERVATION",
                () -> reservationService.createWalkInReservation(request),
                item -> String.valueOf(item.getId()),
                reservationId -> reservationService.getReservation(
                        Long.valueOf(reservationId), currentUser, null));
        return ApiResponse.success("Tạo đặt phòng vãng lai thành công", response);
    }

    @Operation(summary = "Create atomic walk-in reservation",
            description = "Atomically creates the stay, assigns physical rooms and guests, and checks in. CASH is atomic; optional SEPAY failure leaves the stay checked in without duplicating it on retry.")
    @PostMapping("/walk-in/v2")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WalkInReservationResponse> createWalkInCheckedIn(
            @Valid @RequestBody CreateWalkInCheckedInRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser,
            HttpServletRequest httpRequest) {
        WalkInReservationResponse response = idempotencyService.execute(
                idempotencyKey,
                "WALK_IN_CREATE_V2",
                idempotencyService.actorScope(currentUser, null),
                request,
                "RESERVATION",
                () -> createWalkInCheckedInAndPayment(request, currentUser, httpRequest),
                item -> String.valueOf(item.getReservation().getId()),
                reservationId -> replayWalkInCheckedIn(
                        Long.valueOf(reservationId), request.getPaymentOption(), currentUser));
        return ApiResponse.success("Đã tạo và check-in walk-in", response);
    }

    private WalkInReservationResponse createWalkInCheckedInAndPayment(
            CreateWalkInCheckedInRequest request,
            com.hotel.backend.entity.User currentUser,
            HttpServletRequest httpRequest) {
        WalkInReservationResponse response = reservationService.createWalkInCheckedIn(
                request, currentUser, VNPayUtil.getClientIp(httpRequest));
        if (request.getPaymentOption() != WalkInPaymentOption.SEPAY) {
            return response;
        }

        try {
            PaymentResponse payment = paymentService.createWalkInSePayPayment(
                    response.getReservation().getId(),
                    request.getPaymentAmount(),
                    httpRequest,
                    currentUser);
            response.setPaymentCreationStatus("PENDING");
            response.setPaymentInstructions(payment);
            response.setPaymentError(null);
        } catch (RuntimeException failure) {
            // A provider/configuration rejection is reported separately. The
            // optional payment method uses noRollbackFor AppException so the
            // surrounding idempotent command still commits the checked-in stay.
            log.warn("Walk-in {} đã CHECKED_IN nhưng tạo SePay QR thất bại: {}",
                    response.getReservation().getId(), failure.getMessage());
            response.setPaymentCreationStatus("FAILED");
            response.setPaymentInstructions(null);
            response.setPaymentError(failure instanceof AppException
                    ? failure.getMessage()
                    : "Không thể tạo SePay QR; reservation vẫn đã CHECKED_IN");
        }
        return response;
    }

    private WalkInReservationResponse replayWalkInCheckedIn(
            Long reservationId,
            WalkInPaymentOption paymentOption,
            com.hotel.backend.entity.User currentUser) {
        ReservationResponse reservation = reservationService.getReservation(
                reservationId, currentUser, null);
        PaymentResponse payment = paymentService.getLatestWalkInPayment(
                reservationId, currentUser);
        String status;
        String error = null;
        if (payment != null) {
            status = payment.getStatus().name();
        } else if (paymentOption == WalkInPaymentOption.UNPAID) {
            status = "NOT_REQUESTED";
        } else {
            status = "FAILED";
            error = "Reservation đã CHECKED_IN nhưng chưa có payment walk-in";
        }
        return WalkInReservationResponse.builder()
                .reservationCreated(true)
                .reservation(reservation)
                .paymentCreationStatus(status)
                .paymentInstructions(payment)
                .paymentError(error)
                .build();
    }

    // ── Customer: xem đặt phòng của mình ─────────────────────────────────────
    @Operation(summary = "Get my Reservations", description = "API retrieve reservations of the current customer")
    @GetMapping("/my")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<ReservationResponse>> getMyReservations(
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser) {
        return ApiResponse.success(
                reservationService.getMyReservations(currentUser));
    }

    @Operation(summary = "Lookup guest reservation", description = "API retrieve reservation detail by guest token")
    @PostMapping("/lookup")
    public ApiResponse<ReservationResponse> lookupGuestReservation(
            @RequestHeader("X-Guest-Token") String guestToken) {
        return ApiResponse.success(reservationService.lookupGuestReservation(guestToken));
    }

    // ── Customer/Staff: xem chi tiết đặt phòng ───────────────────────────────
    @Operation(summary = "Get detail Reservation", description = "API retrieve reservation detail by id")
    @GetMapping("/{id}")
    // @PreAuthorize("hasAuthority('reservation:read')")
    public ApiResponse<ReservationResponse> getReservation(
            @PathVariable Long id,
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser) {
        return ApiResponse.success(reservationService.getReservation(id, currentUser, guestToken));
    }

    // ── Customer/Staff: hủy đặt phòng ────────────────────────────────────────
    @Operation(summary = "Cancel Reservation", description = "API cancel a reservation by id")
    @PatchMapping("/cancel/{id}")
    // @PreAuthorize("hasAuthority('reservation:cancel')")
    public ApiResponse<ReservationResponse> cancelReservation(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser,
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @Valid @RequestBody(required = false) CancelReservationRequest request) {
        CancelReservationRequest effectiveRequest = request != null
                ? request
                : new CancelReservationRequest();
        ReservationResponse response = idempotencyService.execute(
                idempotencyKey,
                "RESERVATION_CANCEL_REQUEST",
                idempotencyService.actorScope(currentUser, guestToken),
                java.util.Map.of("reservationId", id, "request", effectiveRequest),
                "RESERVATION",
                () -> reservationService.cancelReservation(
                        id, effectiveRequest, currentUser, guestToken),
                item -> String.valueOf(item.getId()),
                reservationId -> reservationService.getReservation(
                        Long.valueOf(reservationId), currentUser, guestToken));
        return ApiResponse.success("Đã gửi yêu cầu hủy, chờ khách sạn duyệt", response);
    }

    @Operation(summary = "Submit refund bank recipient", description = "Owner/guest supplies bank details for a manual refund already awaiting customer information")
    @PutMapping("/{id}/refund-recipient")
    public ApiResponse<RefundRecipientResponse> submitRefundRecipient(
            @PathVariable Long id,
            @Valid @RequestBody RefundRecipientRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser,
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        RefundRecipientResponse response = idempotencyService.execute(
                idempotencyKey,
                "RESERVATION_REFUND_RECIPIENT_SUBMIT",
                idempotencyService.actorScope(currentUser, guestToken),
                java.util.Map.of("reservationId", id, "request", request),
                "REFUND_RECIPIENT",
                () -> refundRecipientService.submit(id, request, currentUser, guestToken),
                item -> String.valueOf(item.getRecipientId()),
                ignored -> refundRecipientService.getMasked(id, currentUser, guestToken));
        return ApiResponse.success("Đã lưu thông tin ngân hàng nhận hoàn tiền", response);
    }

    @Operation(summary = "Get masked refund bank recipient", description = "Owner/guest retrieves only masked bank details")
    @GetMapping("/{id}/refund-recipient")
    public ApiResponse<RefundRecipientResponse> getRefundRecipient(
            @PathVariable Long id,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser,
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        return ApiResponse.success(refundRecipientService.getMasked(id, currentUser, guestToken));
    }

    @Operation(summary = "Approve cancellation", description = "Staff approves cancellation and decides whether paid money is refunded")
    @PatchMapping("/cancel/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<ReservationResponse> approveCancellation(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) CancelReservationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser) {
        CancelReservationRequest effectiveRequest = request != null
                ? request
                : new CancelReservationRequest();
        ReservationResponse response = idempotencyService.execute(
                idempotencyKey,
                "RESERVATION_CANCEL_APPROVE",
                idempotencyService.actorScope(currentUser, null),
                java.util.Map.of("reservationId", id, "request", effectiveRequest),
                "RESERVATION",
                () -> reservationService.approveCancellation(id, effectiveRequest),
                item -> String.valueOf(item.getId()),
                reservationId -> reservationService.getReservation(
                        Long.valueOf(reservationId), currentUser, null));
        return ApiResponse.success(Boolean.TRUE.equals(effectiveRequest.getRefundPayment())
                        ? "Đã duyệt yêu cầu hủy và tạo nghĩa vụ hoàn theo chính sách; reservation chỉ chốt khi refund hoàn tất"
                        : "Đã duyệt hủy và ghi nhận toàn bộ số tiền giữ lại là phí phạt",
                response);
    }

    @Operation(summary = "Reject cancellation", description = "Staff rejects cancellation and restores the previous reservation status")
    @PatchMapping("/cancel/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<ReservationResponse> rejectCancellation(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser) {
        ReservationResponse response = idempotencyService.execute(
                idempotencyKey,
                "RESERVATION_CANCEL_REJECT",
                idempotencyService.actorScope(currentUser, null),
                java.util.Map.of("reservationId", id),
                "RESERVATION",
                () -> reservationService.rejectCancellation(id),
                item -> String.valueOf(item.getId()),
                reservationId -> reservationService.getReservation(
                        Long.valueOf(reservationId), currentUser, null));
        return ApiResponse.success("Đã từ chối yêu cầu hủy", response);
    }

    @Operation(summary = "Staff cancels reservation", description = "Staff/Admin cancels directly and decides whether paid money is refunded")
    @PatchMapping("/cancel/{id}/staff")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<ReservationResponse> cancelByStaff(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) CancelReservationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser) {
        CancelReservationRequest effectiveRequest = request != null
                ? request
                : new CancelReservationRequest();
        ReservationResponse response = idempotencyService.execute(
                idempotencyKey,
                "RESERVATION_CANCEL_STAFF",
                idempotencyService.actorScope(currentUser, null),
                java.util.Map.of("reservationId", id, "request", effectiveRequest),
                "RESERVATION",
                () -> reservationService.cancelByStaff(id, effectiveRequest),
                item -> String.valueOf(item.getId()),
                reservationId -> reservationService.getReservation(
                        Long.valueOf(reservationId), currentUser, null));
        return ApiResponse.success(Boolean.TRUE.equals(effectiveRequest.getRefundPayment())
                        ? "Đã tạo quyết định hủy và nghĩa vụ hoàn theo chính sách; reservation chỉ chốt khi refund hoàn tất"
                        : "Đã hủy reservation và ghi nhận toàn bộ số tiền giữ lại là phí phạt",
                response);
    }

    // ── Staff: xác nhận đặt phòng ────────────────────────────────────────────
    @Operation(summary = "Confirm Reservation", description = "API confirm a reservation by id")
    @PatchMapping("/confirm/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<ReservationResponse> confirmReservation(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser) {
        ReservationResponse response = idempotencyService.execute(
                idempotencyKey,
                "RESERVATION_CONFIRM",
                idempotencyService.actorScope(currentUser, null),
                java.util.Map.of("reservationId", id),
                "RESERVATION",
                () -> reservationService.confirmReservation(id),
                item -> String.valueOf(item.getId()),
                reservationId -> reservationService.getReservation(
                        Long.valueOf(reservationId), currentUser, null));
        return ApiResponse.success("Xác nhận đặt phòng thành công", response);
    }

    @Operation(summary = "Reject pending confirmation", description = "Staff rejects a paid booking and creates the required refund")
    @PatchMapping("/confirm/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<ReservationResponse> rejectConfirmation(
            @PathVariable Long id,
            @Valid @RequestBody RejectReservationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser) {
        ReservationResponse response = idempotencyService.execute(
                idempotencyKey,
                "RESERVATION_CONFIRM_REJECT",
                idempotencyService.actorScope(currentUser, null),
                java.util.Map.of("reservationId", id, "request", request),
                "RESERVATION",
                () -> reservationService.rejectConfirmation(id, request),
                item -> String.valueOf(item.getId()),
                reservationId -> reservationService.getReservation(
                        Long.valueOf(reservationId), currentUser, null));
        return ApiResponse.success("Đã từ chối booking và tạo nghĩa vụ hoàn tiền", response);
    }

    @Operation(summary = "Check in Reservation", description = "API check in a reservation and assign rooms")
    @PatchMapping("/check-in/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<ReservationResponse> checkIn(
            @PathVariable Long id,
            @Valid @RequestBody List<AssignRoomRequest> requests,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser) {
        ReservationResponse response = idempotencyService.execute(
                idempotencyKey,
                "RESERVATION_CHECKIN",
                idempotencyService.actorScope(currentUser, null),
                java.util.Map.of("reservationId", id, "rooms", requests),
                "RESERVATION",
                () -> reservationService.checkIn(id, requests),
                item -> String.valueOf(item.getId()),
                reservationId -> reservationService.getReservation(
                        Long.valueOf(reservationId), currentUser, null));
        return ApiResponse.success("Check-in thành công", response);
    }

    @Operation(summary = "Check out Reservation", description = "API check out a reservation by id")
    @PatchMapping("/check-out/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<ReservationResponse> checkOut(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser) {
        ReservationResponse response = idempotencyService.execute(
                idempotencyKey,
                "RESERVATION_CHECKOUT",
                idempotencyService.actorScope(currentUser, null),
                java.util.Map.of("reservationId", id),
                "RESERVATION",
                () -> reservationService.checkOut(id),
                item -> String.valueOf(item.getId()),
                reservationId -> reservationService.getReservation(
                        Long.valueOf(reservationId), currentUser, null));
        return ApiResponse.success("Check-out thành công", response);
    }

    @Operation(summary = "Update checkout additional fee", description = "Staff/Admin adds or edits the checkout additional fee")
    @PatchMapping("/check-out/{id}/additional-fee")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<ReservationResponse> updateCheckoutAdditionalFee(
            @PathVariable Long id,
            @Valid @RequestBody CheckoutRefundRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser) {
        ReservationResponse response = idempotencyService.execute(
                idempotencyKey,
                "RESERVATION_CHECKOUT_FEE",
                idempotencyService.actorScope(currentUser, null),
                java.util.Map.of("reservationId", id, "request", request),
                "RESERVATION",
                () -> reservationService.updateCheckoutAdditionalFee(id, request),
                item -> String.valueOf(item.getId()),
                reservationId -> reservationService.getReservation(
                        Long.valueOf(reservationId), currentUser, null));
        return ApiResponse.success("Đã cập nhật phụ phí checkout", response);
    }

    @Operation(summary = "Request checkout refund", description = "Create refund requests for the current checkout excess")
    @PatchMapping("/check-out/{id}/refund")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<ReservationResponse> requestCheckoutRefund(
            @PathVariable Long id,
            @Valid @RequestBody ReservationRefundRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser) {
        ReservationResponse response = idempotencyService.execute(
                idempotencyKey,
                "RESERVATION_CHECKOUT_REFUND",
                idempotencyService.actorScope(currentUser, null),
                java.util.Map.of("reservationId", id, "request", request),
                "RESERVATION",
                () -> reservationService.requestCheckoutRefund(id, request),
                item -> String.valueOf(item.getId()),
                reservationId -> reservationService.getReservation(
                        Long.valueOf(reservationId), currentUser, null));
        return ApiResponse.success("Đã tạo yêu cầu hoàn tiền trước check-out", response);
    }

    @Operation(summary = "Mark reservation no-show", description = "Staff marks an overdue confirmed reservation as no-show")
    @PatchMapping("/no-show/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<ReservationResponse> markNoShow(
            @PathVariable Long id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser) {
        ReservationResponse response = idempotencyService.execute(
                idempotencyKey,
                "RESERVATION_NO_SHOW",
                idempotencyService.actorScope(currentUser, null),
                java.util.Map.of("reservationId", id),
                "RESERVATION",
                () -> reservationService.markNoShow(id),
                item -> String.valueOf(item.getId()),
                reservationId -> reservationService.getReservation(
                        Long.valueOf(reservationId), currentUser, null));
        return ApiResponse.success("Đã ghi nhận khách không đến", response);
    }

    @Operation(summary = "Release active RoomHold manually",
            description = "Cancels an unpaid deposit aggregate and releases only ACTIVE holds")
    @PostMapping("/{id}/room-holds/release")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<ReservationResponse> releaseRoomHoldsManually(
            @PathVariable Long id,
            @Valid @RequestBody ManualRoomHoldReleaseRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser) {
        ReservationResponse response = idempotencyService.execute(
                idempotencyKey,
                "ROOM_HOLD_RELEASE_MANUAL",
                idempotencyService.actorScope(currentUser, null),
                java.util.Map.of("reservationId", id, "request", request),
                "RESERVATION",
                () -> paymentSessionExpiryService.releaseRoomHoldsManually(id, request),
                item -> String.valueOf(item.getId()),
                reservationId -> reservationService.getReservation(
                        Long.valueOf(reservationId), currentUser, null));
        businessMetrics.increment("hotel.room.hold.manual.release", "result", "completed");
        return ApiResponse.success("Đã giải phóng phiên giữ phòng", response);
    }

    @Operation(summary = "Calculate final payment", description = "API calculate final payment amount for a reservation")
    @GetMapping("/{id}/final-payment")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<FinalPaymentResponse> calculateFinalPayment(
            @PathVariable Long id,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser) {
        return ApiResponse.success(reservationService.calculateFinalPayment(id, currentUser));
    }

    @Operation(summary = "Read checkout reconciliation",
            description = "Read-only financial reconciliation. It never changes reservation, payment or refund state.")
    @GetMapping("/{id}/checkout-reconciliation")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<CheckoutReconciliationResponse> getCheckoutReconciliation(
            @PathVariable Long id,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser) {
        CheckoutReconciliationResponse response =
                reservationService.getCheckoutReconciliation(id, currentUser);
        businessMetrics.increment(
                "hotel.checkout.reconciliation.observed",
                "status", response.getStatus().name().toLowerCase(java.util.Locale.ROOT));
        return ApiResponse.success(response);
    }

    @Operation(summary = "Get all Reservations", description = "API retrieve all reservations")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<List<ReservationResponse>> getAllReservations() {
        return ApiResponse.success(reservationService.getAllReservations());
    }

    @Operation(summary = "Get checked-out reservation invoice", description = "Returns the immutable payment invoice snapshot for printing")
    @GetMapping("/{id}/invoice")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ReservationInvoiceResponse> getInvoice(
            @PathVariable Long id,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser) {
        return ApiResponse.success(reservationService.getInvoice(id, currentUser));
    }

    @GetMapping("/{id}/audit-logs")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<ReservationAuditLogResponse>> getAuditLogs(@PathVariable Long id) {
        return ApiResponse.success(reservationAuditService.getByReservation(id));
    }

    @Operation(summary = "Update Reservation", description = "API update reservation information by id")
    @PatchMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ReservationResponse> updateReservation(
            @PathVariable Long id,
            @AuthenticationPrincipal com.hotel.backend.entity.User currentUser,
            @Valid @RequestBody UpdateReservationRequest request) {
        return ApiResponse.success("Cập nhật thành công",
                reservationService.updateReservation(id, request, currentUser));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
