package com.hotel.backend.dto.request;
 
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;
 
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssignRoomRequest {
 
    // private Long reservationRoomId;
 
    @NotNull(message = "roomId không được để trống")
    private Long roomId;

    @NotEmpty(message = "Phải có ít nhất 1 khách trong phòng")
    @Valid
    private List<GuestRequest> guests;
}
