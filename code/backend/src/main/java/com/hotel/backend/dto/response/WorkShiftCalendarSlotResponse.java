package com.hotel.backend.dto.response;

import com.hotel.backend.constant.WorkDailyShiftStatus;
import com.hotel.backend.constant.WorkShiftAssignmentPolicy;
import java.time.Instant;
import java.util.List;

public record WorkShiftCalendarSlotResponse(
        Long dailyShiftId,
        WorkDailyShiftStatus dailyShiftStatus,
        Long shiftTemplateId,
        String shiftCode,
        String shiftName,
        String shiftColor,
        String startTime,
        String endTime,
        boolean crossesMidnight,
        boolean started,
        boolean ended,
        Instant completedAtUtc,
        String cancellationReason,
        int checkInEarlyMinutes,
        int lateToleranceMinutes,
        int requiredStaff,
        int assignedCount,
        int pendingRequestCount,
        int availableSlots,
        boolean registrationOpen,
        WorkShiftAssignmentPolicy assignmentPolicy,
        String requirementNote,
        WorkShiftCalendarAssignmentResponse currentUserAssignment,
        WorkShiftRegistrationResponse currentUserRequest,
        List<WorkShiftCalendarAssignmentResponse> assignments,
        List<WorkShiftRegistrationResponse> requests) {
}
