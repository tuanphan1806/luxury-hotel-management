package com.hotel.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WorkShiftRequirementRequest(
        @NotNull(message = "Số nhân sự cần là bắt buộc")
        @Min(value = 0, message = "Số nhân sự cần không được âm")
        @Max(value = 100, message = "Số nhân sự cần tối đa 100")
        Integer requiredStaff,

        @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
        String note) {
}
