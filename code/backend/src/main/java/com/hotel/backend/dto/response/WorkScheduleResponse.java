package com.hotel.backend.dto.response;

import com.hotel.backend.constant.WorkScheduleStatus;
import com.hotel.backend.constant.WorkShiftSessionStatus;

import java.time.Instant;
import java.time.LocalDate;

public record WorkScheduleResponse(
        Long id,
        Long employeeId,
        String employeeName,
        Long shiftTemplateId,
        String shiftCode,
        String shiftName,
        String shiftColor,
        LocalDate workDate,
        Instant scheduledStartUtc,
        Instant scheduledEndUtc,
        Integer checkInEarlyMinutes,
        Integer lateToleranceMinutes,
        WorkScheduleStatus status,
        Long sessionId,
        WorkShiftSessionStatus sessionStatus,
        Instant actualCheckInUtc,
        Instant actualCheckOutUtc,
        boolean autoCheckOut,
        boolean late,
        long lateMinutes,
        Long cashierShiftId,
        String note,
        String cancellationReason,
        Instant createdAtUtc,
        Instant updatedAtUtc) {
}
