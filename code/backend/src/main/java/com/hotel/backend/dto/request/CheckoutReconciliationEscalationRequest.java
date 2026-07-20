package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CheckoutReconciliationEscalationRequest {
    @NotBlank(message = "Mã lý do không được để trống")
    @Size(max = 80, message = "Mã lý do không được quá 80 ký tự")
    private String reasonCode;

    @NotBlank(message = "Mô tả sai lệch không được để trống")
    @Size(max = 1000, message = "Mô tả sai lệch không được quá 1000 ký tự")
    private String note;
}
