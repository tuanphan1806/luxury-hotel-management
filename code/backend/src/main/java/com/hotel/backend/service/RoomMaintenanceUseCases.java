package com.hotel.backend.service;

import com.hotel.backend.dto.request.RoomMaintenanceRequest;
import com.hotel.backend.dto.response.RoomResponse;

public interface RoomMaintenanceUseCases {

    RoomResponse startMaintenance(Long roomId, RoomMaintenanceRequest request);

    RoomResponse addMaintenanceLog(Long roomId, String note);

    RoomResponse completeMaintenance(Long roomId);
}
