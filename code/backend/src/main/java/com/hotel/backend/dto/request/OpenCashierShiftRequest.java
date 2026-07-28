package com.hotel.backend.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class OpenCashierShiftRequest {

    @NotNull(message = "Tiền đầu ca không được để trống")
    @DecimalMin(value = "0", message = "Tiền đầu ca không được âm")
    @Digits(integer = 19, fraction = 0, message = "Tiền đầu ca phải là số nguyên VND hợp lệ")
    private BigDecimal openingCashAmount;

    @Size(max = 1000, message = "Ghi chú tối đa 1000 ký tự")
    private String note;
}
