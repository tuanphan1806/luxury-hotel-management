package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TransferRoomRequest {
    @NotNull
    private Long targetRoomId;
}
