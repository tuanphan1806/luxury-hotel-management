package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Explicit operator reason for cancelling a refund obligation. */
@Data
public class CancelRefundRequest {
    @NotBlank(message = "Lý do hủy refund không được để trống")
    @Size(max = 255, message = "Lý do hủy refund không được quá 255 ký tự")
    private String reason;
}
