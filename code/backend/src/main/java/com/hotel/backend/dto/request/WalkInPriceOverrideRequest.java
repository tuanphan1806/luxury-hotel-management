package com.hotel.backend.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalkInPriceOverrideRequest {
    @NotNull(message = "roomTypeId không được để trống")
    private Long roomTypeId;

    /** Total stay price for one physical room, in whole VND. */
    @NotNull(message = "Giá lưu trú mới không được để trống")
    @PositiveOrZero(message = "Giá lưu trú mới không được âm")
    @Digits(integer = 15, fraction = 0, message = "Giá lưu trú mới phải là số VND nguyên")
    private BigDecimal newUnitPrice;

    @NotBlank(message = "Mã lý do thay đổi giá không được để trống")
    @Size(max = 80, message = "Mã lý do không được quá 80 ký tự")
    private String reasonCode;

    @NotBlank(message = "Ghi chú thay đổi giá không được để trống")
    @Size(max = 500, message = "Ghi chú thay đổi giá không được quá 500 ký tự")
    private String note;
}
