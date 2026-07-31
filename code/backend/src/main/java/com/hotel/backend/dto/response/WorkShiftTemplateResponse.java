package com.hotel.backend.dto.response;

import java.time.Instant;
import java.time.LocalTime;

public record WorkShiftTemplateResponse(
        Long id,
        String code,
        String name,
        LocalTime startTime,
        LocalTime endTime,
        boolean crossesMidnight,
        Integer checkInEarlyMinutes,
        Integer lateToleranceMinutes,
        String color,
        Integer sortOrder,
        Boolean active,
        Instant createdAtUtc,
        Instant updatedAtUtc) {
}
