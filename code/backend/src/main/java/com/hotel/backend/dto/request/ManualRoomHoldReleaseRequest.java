package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ManualRoomHoldReleaseRequest {
    @NotBlank(message = "Mã lý do không được để trống")
    @Size(max = 80, message = "Mã lý do không được quá 80 ký tự")
    private String reasonCode;

    @NotBlank(message = "Ghi chú lý do không được để trống")
    @Size(max = 500, message = "Ghi chú không được quá 500 ký tự")
    private String note;
}
