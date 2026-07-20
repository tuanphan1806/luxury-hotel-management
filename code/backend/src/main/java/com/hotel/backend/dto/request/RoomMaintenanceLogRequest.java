package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RoomMaintenanceLogRequest {
    @NotBlank
    private String note;
}
