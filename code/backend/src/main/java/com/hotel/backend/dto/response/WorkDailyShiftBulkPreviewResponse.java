package com.hotel.backend.dto.response;

import java.util.List;

public record WorkDailyShiftBulkPreviewResponse(
        int candidateCount,
        int creatableCount,
        int skippedExistingCount,
List<WorkDailyShiftBulkPreviewItemResponse> items) {
}
