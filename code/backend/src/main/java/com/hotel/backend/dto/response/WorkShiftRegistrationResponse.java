package com.hotel.backend.dto.response;

import com.hotel.backend.constant.WorkShiftRegistrationStatus;

import java.time.Instant;
import java.time.LocalDate;

public record WorkShiftRegistrationResponse(
        Long id,
        Long employeeId,
        String employeeName,
        Long shiftTemplateId,
        String shiftCode,
        String shiftName,
        String shiftColor,
        LocalDate workDate,
        WorkShiftRegistrationStatus status,
        String staffNote,
        String adminReason,
        Long reviewedById,
        String reviewedByName,
        Instant reviewedAtUtc,
        Long assignmentId,
        Instant createdAtUtc,
        Instant updatedAtUtc) {
}
