package com.hotel.backend.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CloseCashierShiftRequest {

    @NotNull(message = "Tiền kiểm đếm cuối ca không được để trống")
    @DecimalMin(value = "0", message = "Tiền kiểm đếm cuối ca không được âm")
    @Digits(integer = 19, fraction = 0, message = "Tiền kiểm đếm phải là số nguyên VND hợp lệ")
    private BigDecimal countedCashAmount;

    @Size(max = 1000, message = "Ghi chú đóng ca tối đa 1000 ký tự")
    private String note;

    @Size(max = 500, message = "Lý do chênh lệch tối đa 500 ký tự")
    private String varianceReason;
}
