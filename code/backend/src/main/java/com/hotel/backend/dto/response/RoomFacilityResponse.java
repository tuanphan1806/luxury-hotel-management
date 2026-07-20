package com.hotel.backend.dto.response;
import lombok.Data;

@Data
public class RoomFacilityResponse {
    private Long roomId;
    private String roomName;
    private Long facilityId;
    private String facilityName;
}
