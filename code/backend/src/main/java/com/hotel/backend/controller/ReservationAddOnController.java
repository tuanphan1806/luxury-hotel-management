package com.hotel.backend.controller;

import com.hotel.backend.dto.request.ReservationServiceStatusRequest;
import com.hotel.backend.dto.request.BatchServiceOrderRequest;
import com.hotel.backend.dto.request.ServiceOrderRequest;
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.BatchReservationServiceResponse;
import com.hotel.backend.dto.response.ReservationServiceResponse;
import com.hotel.backend.entity.User;
import com.hotel.backend.service.IdempotencyService;
import com.hotel.backend.service.ReservationAddOnService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reservations/{reservationId}/services")
@RequiredArgsConstructor
public class ReservationAddOnController {

    private final ReservationAddOnService service;
    private final IdempotencyService idempotencyService;

    @GetMapping
    public ApiResponse<List<ReservationServiceResponse>> list(
            @PathVariable Long reservationId,
            @AuthenticationPrincipal User currentUser,
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        return ApiResponse.success(
                service.listForReservation(reservationId, currentUser, guestToken));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReservationServiceResponse> request(
            @PathVariable Long reservationId,
            @AuthenticationPrincipal User currentUser,
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ServiceOrderRequest request) {
        String actorScope = idempotencyService.actorScope(currentUser, guestToken);
        ReservationServiceResponse response = idempotencyService.execute(
                idempotencyKey,
                "RESERVATION_SERVICE_REQUEST",
                actorScope,
                request,
                "RESERVATION_SERVICE",
                () -> service.requestInStay(
                        reservationId, request, currentUser, guestToken),
                item -> String.valueOf(item.getId()),
                resourceId -> service.listForReservation(
                                reservationId, currentUser, guestToken)
                        .stream()
                        .filter(item -> String.valueOf(item.getId()).equals(resourceId))
                        .findFirst()
                        .orElseThrow());
        return ApiResponse.success("Đã gửi yêu cầu dịch vụ", response);
    }

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<List<ReservationServiceResponse>> requestBatch(
            @PathVariable Long reservationId,
            @AuthenticationPrincipal User currentUser,
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody BatchServiceOrderRequest request) {
        String actorScope = idempotencyService.actorScope(currentUser, guestToken);
        BatchReservationServiceResponse response = idempotencyService.executeSnapshot(
                idempotencyKey,
                "RESERVATION_SERVICE_BATCH_REQUEST",
                actorScope,
                request,
                "RESERVATION_SERVICE_BATCH",
                String.valueOf(reservationId),
                () -> service.requestInStayBatch(
                        reservationId, request.getServices(), currentUser, guestToken),
                BatchReservationServiceResponse.class);
        return ApiResponse.success("Đã gửi các yêu cầu dịch vụ", response.getServices());
    }

    @PatchMapping("/{orderId}/status")
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public ApiResponse<ReservationServiceResponse> updateStatus(
            @PathVariable Long reservationId,
            @PathVariable Long orderId,
            @AuthenticationPrincipal User currentUser,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReservationServiceStatusRequest request) {
        ReservationServiceResponse response = idempotencyService.execute(
                idempotencyKey,
                "RESERVATION_SERVICE_STATUS",
                idempotencyService.actorScope(currentUser, null),
                request,
                "RESERVATION_SERVICE",
                () -> service.updateStatus(reservationId, orderId, request, currentUser),
                item -> String.valueOf(item.getId()),
                resourceId -> service.listForReservation(
                                reservationId, currentUser, null)
                        .stream()
                        .filter(item -> String.valueOf(item.getId()).equals(resourceId))
                        .findFirst()
                        .orElseThrow());
        return ApiResponse.success("Đã cập nhật trạng thái dịch vụ", response);
    }
}
