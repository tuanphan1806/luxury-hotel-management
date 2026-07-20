package com.hotel.backend.dto.request;

import jakarta.validation.constraints.*;
import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class RoomRequest {

    @NotBlank(message = "Room name is required")
    @Size(max = 20, message = "Room name must not exceed 20 characters")
    private String roomName;

    @NotNull(message = "Room type is required")
    private Long roomTypeId;

    @NotNull(message = "Floor is required")
    @Min(value = 1, message = "Floor must be at least 1")
    private Integer floor;

    private String description;
}