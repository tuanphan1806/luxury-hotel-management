package com.hotel.backend.dto.response;
 
import java.util.List;

import com.hotel.backend.constant.AssignStatus;
import com.hotel.backend.entity.ReservationRoom;
import lombok.*;
 
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationRoomResponse {
 
    private Long id;
    private Long reservationRoomTypeId;
    private Long roomId;
    private String roomName;
    private AssignStatus status;
    private Long assignedById;
    private List<GuestResponse> guests;
 
    public static ReservationRoomResponse from(ReservationRoom rr) {
        return ReservationRoomResponse.builder()
                .id(rr.getId())
                .reservationRoomTypeId(rr.getReservationRoomType().getId())
                .roomId(rr.getRoom() != null ? rr.getRoom().getId() : null)
                .roomName(rr.getRoom() != null ? rr.getRoom().getRoomName() : null)
                .status(rr.getStatus())
                .assignedById(rr.getAssignedBy() != null ? rr.getAssignedBy().getId() : null)
                .build();
    }
}