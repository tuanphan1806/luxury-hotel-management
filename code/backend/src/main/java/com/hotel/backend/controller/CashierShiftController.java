package com.hotel.backend.controller;

import com.hotel.backend.dto.request.CashMovementRequest;
import com.hotel.backend.dto.request.CloseCashierShiftRequest;
import com.hotel.backend.dto.request.OpenCashierShiftRequest;
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.CashMovementResponse;
import com.hotel.backend.dto.response.CashierShiftResponse;
import com.hotel.backend.entity.User;
import com.hotel.backend.service.CashierShiftService;
import com.hotel.backend.service.IdempotencyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/accounting/cashier-shifts")
@PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
@RequiredArgsConstructor
public class CashierShiftController {

    private final CashierShiftService cashierShiftService;
    private final IdempotencyService idempotencyService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CashierShiftResponse> open(
            @Valid @RequestBody OpenCashierShiftRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        CashierShiftResponse response = idempotencyService.execute(
                idempotencyKey,
                "CASHIER_SHIFT_OPEN",
                idempotencyService.actorScope(currentUser, null),
                request,
                "CASHIER_SHIFT",
                () -> cashierShiftService.open(request, currentUser),
                item -> String.valueOf(item.id()),
                itemId -> cashierShiftService.get(Long.valueOf(itemId), currentUser));
        return ApiResponse.success("Đã mở ca thu ngân", response);
    }

    @GetMapping("/current")
    public ApiResponse<CashierShiftResponse> current(
            @AuthenticationPrincipal User currentUser) {
        return ApiResponse.success(cashierShiftService.current(currentUser));
    }

    @GetMapping
    public ApiResponse<Page<CashierShiftResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal User currentUser) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                safeSize,
                Sort.by(Sort.Order.desc("openedAtUtc"), Sort.Order.desc("id")));
        return ApiResponse.success(cashierShiftService.list(pageable, currentUser));
    }

    @GetMapping("/{shiftId}")
    public ApiResponse<CashierShiftResponse> get(
            @PathVariable Long shiftId,
            @AuthenticationPrincipal User currentUser) {
        return ApiResponse.success(cashierShiftService.get(shiftId, currentUser));
    }

    @GetMapping("/{shiftId}/preview-close")
    public ApiResponse<CashierShiftResponse> previewClose(
            @PathVariable Long shiftId,
            @AuthenticationPrincipal User currentUser) {
        return ApiResponse.success(cashierShiftService.previewClose(shiftId, currentUser));
    }

    @PostMapping("/{shiftId}/cash-in")
    public ApiResponse<CashMovementResponse> cashIn(
            @PathVariable Long shiftId,
            @Valid @RequestBody CashMovementRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        CashMovementResponse response = idempotencyService.execute(
                idempotencyKey,
                "CASHIER_SHIFT_CASH_IN",
                idempotencyService.actorScope(currentUser, null),
                Map.of("shiftId", shiftId, "request", request),
                "CASH_MOVEMENT",
                () -> cashierShiftService.addCashIn(
                        shiftId, request, idempotencyKey, currentUser),
                item -> String.valueOf(item.id()),
                itemId -> cashierShiftService.getMovement(Long.valueOf(itemId), currentUser));
        return ApiResponse.success("Đã ghi nhận tiền thu vào két", response);
    }

    @PostMapping("/{shiftId}/cash-out")
    public ApiResponse<CashMovementResponse> cashOut(
            @PathVariable Long shiftId,
            @Valid @RequestBody CashMovementRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        CashMovementResponse response = idempotencyService.execute(
                idempotencyKey,
                "CASHIER_SHIFT_CASH_OUT",
                idempotencyService.actorScope(currentUser, null),
                Map.of("shiftId", shiftId, "request", request),
                "CASH_MOVEMENT",
                () -> cashierShiftService.addCashOut(
                        shiftId, request, idempotencyKey, currentUser),
                item -> String.valueOf(item.id()),
                itemId -> cashierShiftService.getMovement(Long.valueOf(itemId), currentUser));
        return ApiResponse.success("Đã ghi nhận tiền chi khỏi két", response);
    }

    @PostMapping("/{shiftId}/close")
    public ApiResponse<CashierShiftResponse> close(
            @PathVariable Long shiftId,
            @Valid @RequestBody CloseCashierShiftRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        CashierShiftResponse response = idempotencyService.execute(
                idempotencyKey,
                "CASHIER_SHIFT_CLOSE",
                idempotencyService.actorScope(currentUser, null),
                Map.of("shiftId", shiftId, "request", request),
                "CASHIER_SHIFT",
                () -> cashierShiftService.close(shiftId, request, currentUser),
                item -> String.valueOf(item.id()),
                itemId -> cashierShiftService.get(Long.valueOf(itemId), currentUser));
        return ApiResponse.success("Đã đóng ca thu ngân", response);
    }
}
