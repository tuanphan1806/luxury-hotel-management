package com.hotel.backend.dto.response;
 
import com.hotel.backend.constant.HoldStatus;
import com.hotel.backend.entity.RoomHold;
import lombok.*;
 
import java.time.LocalDateTime;
 
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomHoldResponse {
 
    private Long id;
    private Long reservationRoomTypeId;
    private LocalDateTime expiresAt;
    private HoldStatus status;
 
    public static RoomHoldResponse from(RoomHold rh) {
        return RoomHoldResponse.builder()
                .id(rh.getId())
                .reservationRoomTypeId(rh.getReservationRoomType().getId())
                .expiresAt(rh.getExpiresAt())
                .status(rh.getStatus())
                .build();
    }
}