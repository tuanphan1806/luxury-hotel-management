package com.hotel.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

public record WorkShiftTemplateRequest(
        @NotBlank(message = "Mã ca không được để trống")
        @Size(max = 32, message = "Mã ca tối đa 32 ký tự")
        @Pattern(regexp = "[A-Za-z0-9_-]+", message = "Mã ca chỉ gồm chữ, số, _ hoặc -")
        String code,

        @NotBlank(message = "Tên ca không được để trống")
        @Size(max = 100, message = "Tên ca tối đa 100 ký tự")
        String name,

        @NotNull(message = "Giờ bắt đầu là bắt buộc")
        LocalTime startTime,

        @NotNull(message = "Giờ kết thúc là bắt buộc")
        LocalTime endTime,

        @NotNull(message = "Thời gian cho phép check-in sớm là bắt buộc")
        @Min(value = 0, message = "Số phút check-in sớm không được âm")
        @Max(value = 240, message = "Số phút check-in sớm tối đa là 240")
        Integer checkInEarlyMinutes,

        @NotNull(message = "Ngưỡng đi muộn là bắt buộc")
        @Min(value = 0, message = "Ngưỡng đi muộn không được âm")
        @Max(value = 240, message = "Ngưỡng đi muộn tối đa là 240")
        Integer lateToleranceMinutes,

        @NotBlank(message = "Màu hiển thị là bắt buộc")
        @Pattern(regexp = "#[0-9A-Fa-f]{6}", message = "Màu phải ở định dạng #RRGGBB")
        String color,

        @NotNull(message = "Thứ tự hiển thị là bắt buộc")
        @Min(value = 0, message = "Thứ tự hiển thị không được âm")
        Integer sortOrder,

        @NotNull(message = "Trạng thái hoạt động là bắt buộc")
        Boolean active) {
}
