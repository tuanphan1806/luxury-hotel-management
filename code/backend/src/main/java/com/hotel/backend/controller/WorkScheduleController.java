package com.hotel.backend.controller;

import com.hotel.backend.constant.WorkScheduleStatus;
import com.hotel.backend.dto.request.CancelWorkScheduleRequest;
import com.hotel.backend.dto.request.CancelWorkDailyShiftRequest;
import com.hotel.backend.dto.request.WorkDailyShiftBulkRequest;
import com.hotel.backend.dto.request.WorkDailyShiftRequest;
import com.hotel.backend.dto.request.WorkAttendanceRequest;
import com.hotel.backend.dto.request.WorkScheduleAssignmentRequest;
import com.hotel.backend.dto.request.WorkShiftTemplateRequest;
import com.hotel.backend.dto.request.WorkShiftRegistrationCreateRequest;
import com.hotel.backend.dto.request.WorkShiftRegistrationReviewRequest;
import com.hotel.backend.dto.request.WorkShiftRequirementRequest;
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.WorkDailyShiftBulkCreateResponse;
import com.hotel.backend.dto.response.WorkDailyShiftBulkPreviewResponse;
import com.hotel.backend.dto.response.WorkShiftCalendarSlotResponse;
import com.hotel.backend.dto.response.WorkShiftMonthCalendarResponse;
import com.hotel.backend.dto.response.WorkShiftRegistrationResponse;
import com.hotel.backend.dto.response.WorkScheduleResponse;
import com.hotel.backend.dto.response.WorkShiftTemplateResponse;
import com.hotel.backend.entity.User;
import com.hotel.backend.service.IdempotencyService;
import com.hotel.backend.service.WorkDailyShiftService;
import com.hotel.backend.service.WorkScheduleService;
import com.hotel.backend.service.WorkShiftTemplateService;
import com.hotel.backend.service.WorkforceCalendarService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import java.time.YearMonth;
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
    private final WorkDailyShiftService dailyShiftService;
    private final WorkShiftTemplateService templateService;
    private final WorkforceCalendarService calendarService;
    private final IdempotencyService idempotencyService;

    @GetMapping("/templates")
    public ApiResponse<List<WorkShiftTemplateResponse>> templates(
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @AuthenticationPrincipal User currentUser) {
        return ApiResponse.success(templateService.list(includeInactive, currentUser));
    }

    @GetMapping("/calendar")
    public ApiResponse<WorkShiftMonthCalendarResponse> calendar(
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @AuthenticationPrincipal User currentUser) {
        YearMonth safeMonth = month != null
                ? month
                : YearMonth.now(HOTEL_ZONE);
        return ApiResponse.success(calendarService.month(safeMonth, currentUser));
    }

    @PostMapping("/daily-shifts")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WorkShiftCalendarSlotResponse> createDailyShift(
            @Valid @RequestBody WorkDailyShiftRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        WorkShiftCalendarSlotResponse response = idempotencyService.execute(
                idempotencyKey,
                "WORK_DAILY_SHIFT_CREATE",
                idempotencyService.actorScope(currentUser, null),
                request,
                "WORK_DAILY_SHIFT",
                () -> dailyShiftService.create(request, currentUser),
                item -> String.valueOf(item.dailyShiftId()),
                itemId -> dailyShiftService.get(Long.valueOf(itemId), currentUser));
        return ApiResponse.success("Đã mở ca làm việc trong ngày", response);
    }

    @PutMapping("/daily-shifts/{dailyShiftId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WorkShiftCalendarSlotResponse> updateDailyShift(
            @PathVariable Long dailyShiftId,
            @Valid @RequestBody WorkDailyShiftRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        WorkShiftCalendarSlotResponse response = idempotencyService.execute(
                idempotencyKey,
                "WORK_DAILY_SHIFT_UPDATE",
                idempotencyService.actorScope(currentUser, null),
                Map.of("dailyShiftId", dailyShiftId, "request", request),
                "WORK_DAILY_SHIFT",
                () -> dailyShiftService.update(dailyShiftId, request, currentUser),
                item -> String.valueOf(item.dailyShiftId()),
                itemId -> dailyShiftService.get(Long.valueOf(itemId), currentUser));
        return ApiResponse.success("Đã cập nhật ca làm việc trong ngày", response);
    }

    @PostMapping("/daily-shifts/{dailyShiftId}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WorkShiftCalendarSlotResponse> cancelDailyShift(
            @PathVariable Long dailyShiftId,
            @Valid @RequestBody CancelWorkDailyShiftRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        WorkShiftCalendarSlotResponse response = idempotencyService.execute(
                idempotencyKey,
                "WORK_DAILY_SHIFT_CANCEL",
                idempotencyService.actorScope(currentUser, null),
                Map.of("dailyShiftId", dailyShiftId, "request", request),
                "WORK_DAILY_SHIFT",
                () -> dailyShiftService.cancel(dailyShiftId, request, currentUser),
                item -> String.valueOf(item.dailyShiftId()),
                itemId -> dailyShiftService.get(Long.valueOf(itemId), currentUser));
        return ApiResponse.success("Đã hủy ca làm việc trong ngày", response);
    }

    @PostMapping("/daily-shifts/{dailyShiftId}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WorkShiftCalendarSlotResponse> restoreDailyShift(
            @PathVariable Long dailyShiftId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        WorkShiftCalendarSlotResponse response = idempotencyService.execute(
                idempotencyKey,
                "WORK_DAILY_SHIFT_RESTORE",
                idempotencyService.actorScope(currentUser, null),
                Map.of("dailyShiftId", dailyShiftId),
                "WORK_DAILY_SHIFT",
                () -> dailyShiftService.restore(dailyShiftId, currentUser),
                item -> String.valueOf(item.dailyShiftId()),
                itemId -> dailyShiftService.get(Long.valueOf(itemId), currentUser));
        return ApiResponse.success(
                "Đã khôi phục ca; phân công và yêu cầu cũ không tự khôi phục",
                response);
    }

    @DeleteMapping("/daily-shifts/{dailyShiftId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WorkShiftCalendarSlotResponse> deleteUnusedDailyShift(
            @PathVariable Long dailyShiftId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        WorkShiftCalendarSlotResponse response = idempotencyService.executeSnapshot(
                idempotencyKey,
                "WORK_DAILY_SHIFT_DELETE_UNUSED",
                idempotencyService.actorScope(currentUser, null),
                Map.of("dailyShiftId", dailyShiftId),
                "WORK_DAILY_SHIFT",
                String.valueOf(dailyShiftId),
                () -> dailyShiftService.deleteUnused(dailyShiftId, currentUser),
                WorkShiftCalendarSlotResponse.class);
        return ApiResponse.success("Đã xóa ca trống khỏi lịch", response);
    }

    @PostMapping("/daily-shifts/bulk/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WorkDailyShiftBulkPreviewResponse> previewDailyShifts(
            @Valid @RequestBody WorkDailyShiftBulkRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ApiResponse.success(dailyShiftService.previewBulk(request, currentUser));
    }

    @PostMapping("/daily-shifts/bulk")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WorkDailyShiftBulkCreateResponse> createDailyShifts(
            @Valid @RequestBody WorkDailyShiftBulkRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        WorkDailyShiftBulkCreateResponse response = idempotencyService.executeSnapshot(
                idempotencyKey,
                "WORK_DAILY_SHIFT_BULK_CREATE",
                idempotencyService.actorScope(currentUser, null),
                request,
                "WORK_DAILY_SHIFT_BULK",
                request.from() + "_" + request.to(),
                () -> dailyShiftService.createBulk(request, currentUser),
                WorkDailyShiftBulkCreateResponse.class);
        return ApiResponse.success(
                "Đã tạo nhanh các ca hợp lệ; ca trùng được giữ nguyên",
                response);
    }

    @PostMapping("/registration-requests")
    @PreAuthorize("hasRole('STAFF')")
    public ApiResponse<WorkShiftRegistrationResponse> createRegistrationRequest(
            @Valid @RequestBody WorkShiftRegistrationCreateRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        WorkShiftRegistrationResponse response = idempotencyService.execute(
                idempotencyKey,
                "WORK_SHIFT_REGISTRATION_CREATE",
                idempotencyService.actorScope(currentUser, null),
                request,
                "WORK_SHIFT_REQUEST",
                () -> calendarService.createRequest(request, currentUser),
                item -> String.valueOf(item.id()),
                itemId -> calendarService.getRequest(Long.valueOf(itemId), currentUser));
        return ApiResponse.success("Đã gửi yêu cầu đăng ký ca", response);
    }

    @PostMapping("/registration-requests/{requestId}/cancel")
    @PreAuthorize("hasRole('STAFF')")
    public ApiResponse<WorkShiftRegistrationResponse> cancelRegistrationRequest(
            @PathVariable Long requestId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        WorkShiftRegistrationResponse response = idempotencyService.execute(
                idempotencyKey,
                "WORK_SHIFT_REGISTRATION_CANCEL",
                idempotencyService.actorScope(currentUser, null),
                Map.of("requestId", requestId),
                "WORK_SHIFT_REQUEST",
                () -> calendarService.cancelRequest(requestId, currentUser),
                item -> String.valueOf(item.id()),
                itemId -> calendarService.getRequest(Long.valueOf(itemId), currentUser));
        return ApiResponse.success("Đã hủy yêu cầu đăng ký ca", response);
    }

    @PostMapping("/registration-requests/{requestId}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WorkShiftRegistrationResponse> approveRegistrationRequest(
            @PathVariable Long requestId,
            @Valid @RequestBody WorkShiftRegistrationReviewRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        WorkShiftRegistrationResponse response = idempotencyService.execute(
                idempotencyKey,
                "WORK_SHIFT_REGISTRATION_APPROVE",
                idempotencyService.actorScope(currentUser, null),
                Map.of("requestId", requestId, "request", request),
                "WORK_SHIFT_REQUEST",
                () -> calendarService.approveRequest(requestId, request, currentUser),
                item -> String.valueOf(item.id()),
                itemId -> calendarService.getRequest(Long.valueOf(itemId), currentUser));
        return ApiResponse.success("Đã duyệt và phân ca cho nhân viên", response);
    }

    @PostMapping("/registration-requests/{requestId}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WorkShiftRegistrationResponse> rejectRegistrationRequest(
            @PathVariable Long requestId,
            @Valid @RequestBody WorkShiftRegistrationReviewRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        WorkShiftRegistrationResponse response = idempotencyService.execute(
                idempotencyKey,
                "WORK_SHIFT_REGISTRATION_REJECT",
                idempotencyService.actorScope(currentUser, null),
                Map.of("requestId", requestId, "request", request),
                "WORK_SHIFT_REQUEST",
                () -> calendarService.rejectRequest(requestId, request, currentUser),
                item -> String.valueOf(item.id()),
                itemId -> calendarService.getRequest(Long.valueOf(itemId), currentUser));
        return ApiResponse.success("Đã từ chối yêu cầu đăng ký ca", response);
    }

    @PutMapping("/requirements/{workDate}/{shiftTemplateId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<WorkShiftCalendarSlotResponse> updateRequirement(
            @PathVariable
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate,
            @PathVariable Long shiftTemplateId,
            @Valid @RequestBody WorkShiftRequirementRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        WorkShiftCalendarSlotResponse response = idempotencyService.execute(
                idempotencyKey,
                "WORK_SHIFT_REQUIREMENT_UPDATE",
                idempotencyService.actorScope(currentUser, null),
                Map.of(
                        "workDate", workDate,
                        "shiftTemplateId", shiftTemplateId,
                        "request", request),
                "WORK_SHIFT_REQUIREMENT",
                () -> calendarService.updateRequirement(
                        workDate,
                        shiftTemplateId,
                        request,
                        currentUser),
                item -> item.shiftTemplateId() + "@" + workDate,
                ignored -> calendarService.getSlot(
                        workDate,
                        shiftTemplateId,
                        currentUser));
        return ApiResponse.success("Đã cập nhật nhu cầu nhân sự", response);
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
