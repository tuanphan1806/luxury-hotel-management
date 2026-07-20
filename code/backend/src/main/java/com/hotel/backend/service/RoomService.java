package com.hotel.backend.service;

import com.hotel.backend.constant.CleaningStatus;
import com.hotel.backend.constant.RoomStatus;
import com.hotel.backend.dto.request.RoomRequest;
import com.hotel.backend.dto.request.RoomMaintenanceRequest;
import com.hotel.backend.dto.response.RoomPageResponse;
import com.hotel.backend.dto.response.RoomResponse;

import java.util.List;

public interface RoomService {

    RoomResponse create(RoomRequest request);

    RoomResponse update(Long id, RoomRequest request);

    RoomResponse getById(Long id);

    List<RoomResponse> getAll();

    List<RoomResponse> search(String keyword, RoomStatus status, CleaningStatus cleaningStatus);

    List<RoomResponse> getAvailableRoomsForReservation(Long reservationId, Long roomTypeId);

    RoomPageResponse findAll(String keyword, String sort, int page, int size);

    void delete(Long id);

    RoomResponse updateStatus(Long id, RoomStatus status);

    RoomResponse updateCleaningStatus(Long id, CleaningStatus cleaningStatus);

    RoomResponse transferCheckedInRoom(Long sourceRoomId, Long targetRoomId);

    Long getActiveReservationId(Long roomId);

    RoomResponse startMaintenance(Long roomId, RoomMaintenanceRequest request);

    RoomResponse addMaintenanceLog(Long roomId, String note);

    RoomResponse completeMaintenance(Long roomId);
}
