package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelWorkDailyShiftRequest(
        @NotBlank(message = "Lý do hủy ca là bắt buộc")
        @Size(max = 500, message = "Lý do hủy tối đa 500 ký tự")
String reason) {
}
