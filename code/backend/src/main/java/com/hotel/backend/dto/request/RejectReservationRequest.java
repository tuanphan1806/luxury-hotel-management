package com.hotel.backend.dto.request;

import com.hotel.backend.constant.RefundChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Staff decision when a paid booking cannot be confirmed. */
@Data
public class RejectReservationRequest {
    @NotBlank(message = "Lý do từ chối đặt phòng không được để trống")
    @Size(max = 255, message = "Lý do từ chối đặt phòng không được quá 255 ký tự")
    private String reason;

    private RefundChannel refundChannel;
}
