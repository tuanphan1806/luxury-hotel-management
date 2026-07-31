package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record WorkScheduleAssignmentRequest(
        @NotNull(message = "Nhân viên là bắt buộc")
        @Positive(message = "Nhân viên không hợp lệ")
        Long employeeId,

        @NotNull(message = "Ca làm việc là bắt buộc")
        @Positive(message = "Ca làm việc không hợp lệ")
        Long shiftTemplateId,

        @NotNull(message = "Ngày làm việc là bắt buộc")
        LocalDate workDate,

        @Size(max = 1000, message = "Ghi chú tối đa 1000 ký tự")
        String note) {
}
