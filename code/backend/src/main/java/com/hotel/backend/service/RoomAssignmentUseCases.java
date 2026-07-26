package com.hotel.backend.service;

import com.hotel.backend.constant.CleaningStatus;
import com.hotel.backend.constant.RoomStatus;
import com.hotel.backend.dto.response.RoomResponse;

import java.util.List;

public interface RoomAssignmentUseCases {

    List<RoomResponse> getAvailableRoomsForReservation(Long reservationId, Long roomTypeId);

    RoomResponse updateStatus(Long id, RoomStatus status);

    RoomResponse updateCleaningStatus(Long id, CleaningStatus cleaningStatus);

    RoomResponse transferCheckedInRoom(Long sourceRoomId, Long targetRoomId);

    Long getActiveReservationId(Long roomId);
}
