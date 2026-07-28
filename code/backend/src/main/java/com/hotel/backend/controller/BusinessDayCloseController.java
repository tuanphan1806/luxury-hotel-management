package com.hotel.backend.controller;

import com.hotel.backend.dto.request.CloseBusinessDayRequest;
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.BusinessDayCloseResponse;
import com.hotel.backend.dto.response.FinancialJournalEntryResponse;
import com.hotel.backend.entity.User;
import com.hotel.backend.service.BusinessDayCloseService;
import com.hotel.backend.service.IdempotencyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/accounting")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class BusinessDayCloseController {
    private final BusinessDayCloseService businessDayCloseService;
    private final IdempotencyService idempotencyService;

    @GetMapping("/business-days/{businessDate}/preview")
    public ApiResponse<BusinessDayCloseResponse> preview(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate businessDate,
            @AuthenticationPrincipal User currentUser) {
        return ApiResponse.success(businessDayCloseService.preview(businessDate, currentUser));
    }

    @PostMapping("/business-days/{businessDate}/close")
    public ApiResponse<BusinessDayCloseResponse> close(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate businessDate,
            @Valid @RequestBody CloseBusinessDayRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        BusinessDayCloseResponse response = idempotencyService.execute(
                idempotencyKey,
                "BUSINESS_DAY_CLOSE",
                idempotencyService.actorScope(currentUser, null),
                Map.of("businessDate", businessDate, "request", request),
                "BUSINESS_DAY",
                () -> businessDayCloseService.close(businessDate, request, currentUser),
                item -> item.businessDate().toString(),
                itemId -> businessDayCloseService.preview(LocalDate.parse(itemId), currentUser));
        return ApiResponse.success("Đã khóa ngày nghiệp vụ", response);
    }

    @GetMapping("/business-days")
    public ApiResponse<Page<BusinessDayCloseResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal User currentUser) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        return ApiResponse.success(businessDayCloseService.list(
                PageRequest.of(Math.max(page, 0), safeSize), currentUser));
    }

    @GetMapping("/journal")
    public ApiResponse<Page<FinancialJournalEntryResponse>> journal(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate businessDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @AuthenticationPrincipal User currentUser) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0), safeSize,
                Sort.by(Sort.Order.desc("postedAtUtc"), Sort.Order.desc("id")));
        return ApiResponse.success(
                businessDayCloseService.journal(businessDate, pageable, currentUser));
    }
}
