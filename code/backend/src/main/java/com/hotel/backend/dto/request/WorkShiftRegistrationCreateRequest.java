package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record WorkShiftRegistrationCreateRequest(
        @NotNull(message = "Ca làm việc là bắt buộc")
        @Positive(message = "Ca làm việc không hợp lệ")
        Long shiftTemplateId,

        @NotNull(message = "Ngày làm việc là bắt buộc")
        LocalDate workDate,

        @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
        String note) {
}
