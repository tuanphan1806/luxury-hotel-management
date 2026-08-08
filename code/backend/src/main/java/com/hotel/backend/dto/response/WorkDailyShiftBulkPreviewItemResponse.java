package com.hotel.backend.dto.response;

import java.time.LocalDate;

public record WorkDailyShiftBulkPreviewItemResponse(
        LocalDate workDate,
        Long shiftTemplateId,
        String shiftName,
        String startTime,
        String endTime,
        String action,
String reason) {
}
