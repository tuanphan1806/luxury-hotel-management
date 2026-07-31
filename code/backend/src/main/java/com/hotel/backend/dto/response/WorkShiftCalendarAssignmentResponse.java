package com.hotel.backend.dto.response;

import com.hotel.backend.constant.WorkScheduleStatus;
import com.hotel.backend.constant.WorkShiftSessionStatus;

public record WorkShiftCalendarAssignmentResponse(
        Long id,
        Long employeeId,
        String employeeName,
        WorkScheduleStatus status,
        WorkShiftSessionStatus sessionStatus,
        boolean late,
        long lateMinutes) {
}
