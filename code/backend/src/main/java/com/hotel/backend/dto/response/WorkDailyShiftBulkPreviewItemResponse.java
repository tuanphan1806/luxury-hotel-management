package com.hotel.backend.dto.response;

import com.hotel.backend.constant.WorkDailyShiftStatus;
import java.time.LocalDate;

public record WorkDailyShiftBulkPreviewItemResponse(
        LocalDate workDate,
        Long shiftTemplateId,
        String shiftName,
        String startTime,
        String endTime,
        String action,
        WorkDailyShiftStatus existingStatus,
String reason) {
}
