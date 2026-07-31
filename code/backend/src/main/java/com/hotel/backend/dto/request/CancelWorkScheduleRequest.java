package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelWorkScheduleRequest(
        @NotBlank(message = "Lý do hủy lịch là bắt buộc")
        @Size(max = 500, message = "Lý do hủy lịch tối đa 500 ký tự")
        String reason) {
}
