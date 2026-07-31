package com.hotel.backend.service;

import com.hotel.backend.dto.response.RoomPageResponse;
import com.hotel.backend.dto.response.RoomResponse;
import com.hotel.backend.entity.Room;
import org.springframework.data.domain.Page;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Response and audit-view mapping extracted from room orchestration.
 */
public final class RoomViewMapper {

    private RoomViewMapper() {
    }

    public static RoomPageResponse toPage(int page, int size, Page<Room> rooms) {
        List<RoomResponse> roomList = rooms.stream()
                .map(RoomResponse::summary)
                .toList();

        RoomPageResponse response = new RoomPageResponse();
        response.setPageNumber(page);
        response.setPageSize(size);
        response.setTotalElements(rooms.getTotalElements());
        response.setTotalPages(rooms.getTotalPages());
        response.setRooms(roomList);
        return response;
    }

    public static Map<String, Object> auditSnapshot(Room room) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", room.getId());
        value.put("roomName", room.getRoomName());
        value.put("roomTypeId", room.getRoomType() != null ? room.getRoomType().getId() : null);
        value.put("floor", room.getFloor());
        value.put("status", room.getStatus() != null ? room.getStatus().name() : null);
        value.put("cleaningStatus", room.getCleaningStatus() != null
                ? room.getCleaningStatus().name() : null);
        value.put("maintenanceReason", room.getMaintenanceReason());
        value.put("maintenanceExpectedCompletedDate", room.getMaintenanceExpectedCompletedDate());
        return value;
    }
}
