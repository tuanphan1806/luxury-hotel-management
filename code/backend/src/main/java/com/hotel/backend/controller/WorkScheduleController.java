package com.hotel.backend.controller;

import com.hotel.backend.constant.WorkScheduleStatus;
import com.hotel.backend.dto.request.CancelWorkScheduleRequest;
import com.hotel.backend.dto.request.WorkAttendanceRequest;
import com.hotel.backend.dto.request.WorkScheduleAssignmentRequest;
import com.hotel.backend.dto.request.WorkShiftTemplateRequest;
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.WorkScheduleResponse;
import com.hotel.backend.dto.response.WorkShiftTemplateResponse;
import com.hotel.backend.entity.User;
import com.hotel.backend.service.IdempotencyService;
import com.hotel.backend.service.WorkScheduleService;
import com.hotel.backend.service.WorkShiftTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/work-schedules")
@PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
@RequiredArgsConstructor
public class WorkScheduleController {

    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final WorkScheduleService workScheduleService;
    private final WorkShiftTemplateService templateService;
    private final IdempotencyService idempotencyService;

    @GetMapping("/templates")
    public ApiResponse<List<WorkShiftTemplateResponse>> templates(
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @AuthenticationPrincipal User currentUser) {
        return ApiResponse.success(templateService.list(includeInactive, currentUser));
    }

    @PostMapping("/templates")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WorkShiftTemplateResponse> createTemplate(
            @Valid @RequestBody WorkShiftTemplateRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        WorkShiftTemplateResponse response = idempotencyService.execute(
                idempotencyKey,
                "WORK_SHIFT_TEMPLATE_CREATE",
                idempotencyService.actorScope(currentUser, null),
                request,
                "WORK_SHIFT_TEMPLATE",
                () -> templateService.create(request, currentUser),
                item -> String.valueOf(item.id()),
                itemId -> templateService.get(Long.valueOf(itemId), currentUser));
        return ApiResponse.success("Đã tạo mẫu ca làm việc", response);
    }

    @PutMapping("/templates/{templateId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WorkShiftTemplateResponse> updateTemplate(
            @PathVariable Long templateId,
            @Valid @RequestBody WorkShiftTemplateRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        WorkShiftTemplateResponse response = idempotencyService.execute(
                idempotencyKey,
                "WORK_SHIFT_TEMPLATE_UPDATE",
                idempotencyService.actorScope(currentUser, null),
                Map.of("templateId", templateId, "request", request),
                "WORK_SHIFT_TEMPLATE",
                () -> templateService.update(templateId, request, currentUser),
                item -> String.valueOf(item.id()),
                itemId -> templateService.get(Long.valueOf(itemId), currentUser));
        return ApiResponse.success("Đã cập nhật mẫu ca làm việc", response);
    }

    @GetMapping("/assignments")
    public ApiResponse<List<WorkScheduleResponse>> assignments(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) WorkScheduleStatus status,
            @AuthenticationPrincipal User currentUser) {
        LocalDate today = LocalDate.now(HOTEL_ZONE);
        LocalDate safeFrom = from != null ? from : today.minusDays(7);
        LocalDate safeTo = to != null ? to : today.plusDays(21);
        return ApiResponse.success(workScheduleService.list(
                safeFrom, safeTo, employeeId, status, currentUser));
    }

    @GetMapping("/assignments/{assignmentId}")
    public ApiResponse<WorkScheduleResponse> assignment(
            @PathVariable Long assignmentId,
            @AuthenticationPrincipal User currentUser) {
        return ApiResponse.success(workScheduleService.get(assignmentId, currentUser));
    }

    @PostMapping("/assignments")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WorkScheduleResponse> createAssignment(
            @Valid @RequestBody WorkScheduleAssignmentRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        WorkScheduleResponse response = idempotencyService.execute(
                idempotencyKey,
                "WORK_SCHEDULE_CREATE",
                idempotencyService.actorScope(currentUser, null),
                request,
                "WORK_SCHEDULE",
                () -> workScheduleService.create(request, currentUser),
                item -> String.valueOf(item.id()),
                itemId -> workScheduleService.get(Long.valueOf(itemId), currentUser));
        return ApiResponse.success("Đã phân ca làm việc", response);
    }

    @PutMapping("/assignments/{assignmentId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WorkScheduleResponse> updateAssignment(
            @PathVariable Long assignmentId,
            @Valid @RequestBody WorkScheduleAssignmentRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        WorkScheduleResponse response = idempotencyService.execute(
                idempotencyKey,
                "WORK_SCHEDULE_UPDATE",
                idempotencyService.actorScope(currentUser, null),
                Map.of("assignmentId", assignmentId, "request", request),
                "WORK_SCHEDULE",
                () -> workScheduleService.update(assignmentId, request, currentUser),
                item -> String.valueOf(item.id()),
                itemId -> workScheduleService.get(Long.valueOf(itemId), currentUser));
        return ApiResponse.success("Đã cập nhật lịch làm việc", response);
    }

    @PostMapping("/assignments/{assignmentId}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WorkScheduleResponse> cancelAssignment(
            @PathVariable Long assignmentId,
            @Valid @RequestBody CancelWorkScheduleRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        WorkScheduleResponse response = idempotencyService.execute(
                idempotencyKey,
                "WORK_SCHEDULE_CANCEL",
                idempotencyService.actorScope(currentUser, null),
                Map.of("assignmentId", assignmentId, "request", request),
                "WORK_SCHEDULE",
                () -> workScheduleService.cancel(assignmentId, request, currentUser),
                item -> String.valueOf(item.id()),
                itemId -> workScheduleService.get(Long.valueOf(itemId), currentUser));
        return ApiResponse.success("Đã hủy lịch làm việc", response);
    }

    @GetMapping("/current")
    @PreAuthorize("hasRole('STAFF')")
    public ApiResponse<WorkScheduleResponse> current(
            @AuthenticationPrincipal User currentUser) {
        return ApiResponse.success(workScheduleService.current(currentUser));
    }

    @PostMapping("/assignments/{assignmentId}/check-in")
    @PreAuthorize("hasRole('STAFF')")
    public ApiResponse<WorkScheduleResponse> checkIn(
            @PathVariable Long assignmentId,
            @Valid @RequestBody WorkAttendanceRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        WorkScheduleResponse response = idempotencyService.execute(
                idempotencyKey,
                "WORK_SHIFT_CHECK_IN",
                idempotencyService.actorScope(currentUser, null),
                Map.of("assignmentId", assignmentId, "request", request),
                "WORK_SCHEDULE",
                () -> workScheduleService.checkIn(assignmentId, request, currentUser),
                item -> String.valueOf(item.id()),
                itemId -> workScheduleService.get(Long.valueOf(itemId), currentUser));
        return ApiResponse.success("Đã check-in và mở ca thu ngân", response);
    }

    @PostMapping("/assignments/{assignmentId}/check-out")
    @PreAuthorize("hasRole('STAFF')")
    public ApiResponse<WorkScheduleResponse> checkOut(
            @PathVariable Long assignmentId,
            @Valid @RequestBody WorkAttendanceRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        WorkScheduleResponse response = idempotencyService.execute(
                idempotencyKey,
                "WORK_SHIFT_CHECK_OUT",
                idempotencyService.actorScope(currentUser, null),
                Map.of("assignmentId", assignmentId, "request", request),
                "WORK_SCHEDULE",
                () -> workScheduleService.checkOut(assignmentId, request, currentUser),
                item -> String.valueOf(item.id()),
                itemId -> workScheduleService.get(Long.valueOf(itemId), currentUser));
        return ApiResponse.success("Đã checkout và đóng ca thu ngân", response);
    }
}
