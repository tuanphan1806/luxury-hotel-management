package com.hotel.backend.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CashMovementRequest {

    @NotNull(message = "Số tiền không được để trống")
    @DecimalMin(value = "1", message = "Số tiền phải lớn hơn 0")
    @Digits(integer = 19, fraction = 0, message = "Số tiền phải là số nguyên VND hợp lệ")
    private BigDecimal amount;

    @NotBlank(message = "Lý do thu/chi không được để trống")
    @Size(min = 5, max = 500, message = "Lý do phải từ 5 đến 500 ký tự")
    private String reason;
}
