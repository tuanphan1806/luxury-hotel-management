package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SePayEventIgnoreRequest {
    @NotBlank(message = "Lý do ignore không được để trống")
    @Size(max = 500, message = "Lý do ignore không được quá 500 ký tự")
    private String reason;
}
