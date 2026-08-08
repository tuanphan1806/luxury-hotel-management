package com.hotel.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public record WorkDailyShiftBulkRequest(
        @NotNull(message = "Ngày bắt đầu là bắt buộc") LocalDate from,
        @NotNull(message = "Ngày kết thúc là bắt buộc") LocalDate to,
        @NotEmpty(message = "Phải chọn ít nhất một thứ trong tuần") Set<DayOfWeek> weekdays,
        @NotEmpty(message = "Phải chọn ít nhất một ca")
        @Size(max = 20, message = "Chỉ tạo tối đa 20 mẫu ca trong một lần")
List<@Valid WorkDailyShiftBulkItemRequest> shifts) {
}
