package com.hotel.backend.dto.request;

import com.hotel.backend.constant.RefundChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SePayEventRefundRequest {
    @NotNull(message = "Kênh hoàn tiền không được để trống")
    private RefundChannel refundChannel;

    @NotBlank(message = "Lý do hoàn tiền không được để trống")
    @Size(max = 255, message = "Lý do hoàn tiền không được quá 255 ký tự")
    private String reason;
}
