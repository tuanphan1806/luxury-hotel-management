package com.hotel.backend.dto.request;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class RoomMaintenanceRequest {
    @NotBlank
    private String reason;

    @NotNull
    @FutureOrPresent
    private LocalDate expectedCompletedDate;
}
