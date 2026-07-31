package com.hotel.backend.dto.response;

import java.util.List;

public record WorkShiftCalendarSlotResponse(
        Long shiftTemplateId,
        String shiftCode,
        String shiftName,
        String shiftColor,
        String startTime,
        String endTime,
        boolean crossesMidnight,
        int requiredStaff,
        int assignedCount,
        int pendingRequestCount,
        int availableSlots,
        String requirementNote,
        WorkShiftCalendarAssignmentResponse currentUserAssignment,
        WorkShiftRegistrationResponse currentUserRequest,
        List<WorkShiftCalendarAssignmentResponse> assignments,
        List<WorkShiftRegistrationResponse> requests) {
}
