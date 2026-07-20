package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ManualRefundFallbackOpenRequest {
    @NotBlank(message = "Lý do mở xác nhận thủ công không được để trống")
    @Size(max = 255, message = "Lý do mở xác nhận thủ công tối đa 255 ký tự")
    private String reason;
}
