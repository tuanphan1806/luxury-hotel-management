package com.hotel.backend.dto.response;

import java.time.LocalDate;
import java.util.List;

public record WorkShiftMonthCalendarResponse(
        String month,
        LocalDate from,
        LocalDate to,
        List<WorkShiftCalendarDayResponse> days) {
}
