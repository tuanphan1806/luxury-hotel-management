package com.hotel.backend.dto.response;

import java.time.LocalDate;
import java.util.List;

public record WorkShiftCalendarDayResponse(
        LocalDate date,
        boolean past,
        boolean today,
        List<WorkShiftCalendarSlotResponse> slots) {
}
