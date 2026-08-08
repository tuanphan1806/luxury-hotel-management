package com.hotel.backend.dto.request;

import com.hotel.backend.constant.WorkShiftAssignmentPolicy;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

public record WorkDailyShiftRequest(
        @NotNull(message = "Mẫu ca là bắt buộc")
        @Positive(message = "Mẫu ca không hợp lệ")
        Long shiftTemplateId,

        @NotNull(message = "Ngày làm việc là bắt buộc")
        LocalDate workDate,

        @NotBlank(message = "Tên ca là bắt buộc")
        @Size(max = 100, message = "Tên ca tối đa 100 ký tự")
        String shiftName,

        @NotNull(message = "Giờ bắt đầu là bắt buộc")
        LocalTime startTime,

        @NotNull(message = "Giờ kết thúc là bắt buộc")
        LocalTime endTime,

        @NotNull(message = "Số nhân sự cần là bắt buộc")
        @Min(value = 1, message = "Ca làm việc phải cần ít nhất 1 nhân viên")
        @Max(value = 100, message = "Số nhân sự cần tối đa 100")
        Integer requiredStaff,

        @NotNull(message = "Trạng thái đăng ký ca là bắt buộc")
        Boolean registrationOpen,

        @NotNull(message = "Chính sách phân ca là bắt buộc")
        WorkShiftAssignmentPolicy assignmentPolicy,

        @NotNull(message = "Thời gian check-in sớm là bắt buộc")
        @Min(value = 0, message = "Số phút check-in sớm không được âm")
        @Max(value = 240, message = "Số phút check-in sớm tối đa 240")
        Integer checkInEarlyMinutes,

        @NotNull(message = "Thời gian cho phép đi muộn là bắt buộc")
        @Min(value = 0, message = "Số phút cho phép đi muộn không được âm")
        @Max(value = 240, message = "Số phút cho phép đi muộn tối đa 240")
        Integer lateToleranceMinutes,

        @NotBlank(message = "Màu ca là bắt buộc")
        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Màu ca không hợp lệ")
        String color,

        @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
        String note) {
}
