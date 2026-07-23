package com.hotel.backend.controller;

import com.hotel.backend.constant.CheckoutReconciliationRequestStatus;
import com.hotel.backend.dto.request.CheckoutReconciliationEscalationRequest;
import com.hotel.backend.dto.request.CheckoutReconciliationResolutionRequest;
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.CheckoutReconciliationRequestResponse;
import com.hotel.backend.entity.User;
import com.hotel.backend.service.CheckoutReconciliationRequestService;
import com.hotel.backend.service.IdempotencyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CheckoutReconciliationController {
    private final CheckoutReconciliationRequestService requestService;
    private final IdempotencyService idempotencyService;

    @PostMapping("/api/reservations/{reservationId}/checkout-reconciliation-requests")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<CheckoutReconciliationRequestResponse> create(
            @PathVariable Long reservationId,
            @Valid @RequestBody CheckoutReconciliationEscalationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        String actorScope = idempotencyService.actorScope(currentUser, null);
        CheckoutReconciliationRequestResponse response = idempotencyService.execute(
                idempotencyKey,
                "CHECKOUT_RECONCILIATION_REQUEST",
                actorScope,
                Map.of("reservationId", reservationId, "request", request),
                "CHECKOUT_RECONCILIATION_REQUEST",
                () -> requestService.create(
                        reservationId, request, idempotencyKey, actorScope, currentUser),
                item -> String.valueOf(item.getId()),
                itemId -> requestService.get(Long.valueOf(itemId)));
        return ApiResponse.success("Đã gửi yêu cầu ADMIN đối soát", response);
    }

    @GetMapping("/api/admin/checkout-reconciliation-requests")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Page<CheckoutReconciliationRequestResponse>> list(
            @RequestParam(required = false) CheckoutReconciliationRequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal User currentUser) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        return ApiResponse.success(requestService.list(
                status,
                PageRequest.of(Math.max(page, 0), safeSize,
                        Sort.by(Sort.Direction.DESC, "createdAtUtc")),
                currentUser));
    }

    @PatchMapping("/api/admin/checkout-reconciliation-requests/{id}/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CheckoutReconciliationRequestResponse> resolve(
            @PathVariable Long id,
            @Valid @RequestBody CheckoutReconciliationResolutionRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        CheckoutReconciliationRequestResponse response = idempotencyService.execute(
                idempotencyKey,
                "CHECKOUT_RECONCILIATION_RESOLVE",
                idempotencyService.actorScope(currentUser, null),
                Map.of("requestId", id, "resolution", request),
                "CHECKOUT_RECONCILIATION_REQUEST",
                () -> requestService.resolve(id, request, currentUser),
                item -> String.valueOf(item.getId()),
                itemId -> requestService.get(Long.valueOf(itemId)));
        return ApiResponse.success("Đã xử lý yêu cầu đối soát", response);
    }
}
