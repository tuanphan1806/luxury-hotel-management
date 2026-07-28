package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ExtendReservationRequest {

    @NotNull(message = "Thời gian trả phòng mới không được để trống")
    private LocalDateTime newCheckOut;

    @Size(max = 500, message = "Ghi chú gia hạn không được quá 500 ký tự")
    private String reason;
}
