package com.hotel.backend.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CheckoutRefundRequest {
    @NotNull(message = "Phụ phí không được để trống")
    @Min(value = 0, message = "Phụ phí không được âm")
    private Long additionalFee;

    @NotBlank(message = "Lý do điều chỉnh phụ phí không được để trống")
    @Size(max = 80, message = "Mã lý do không được quá 80 ký tự")
    private String reasonCode;

    @NotBlank(message = "Ghi chú điều chỉnh phụ phí không được để trống")
    @Size(max = 500, message = "Ghi chú không được quá 500 ký tự")
    private String reason;
}
