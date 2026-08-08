package com.hotel.backend.dto.response;

import java.util.List;

public record WorkDailyShiftBulkCreateResponse(
        int requestedCount,
        int createdCount,
        int skippedExistingCount,
List<WorkShiftCalendarSlotResponse> createdShifts) {
}
