package com.hotel.backend.service;

import com.hotel.backend.constant.CleaningStatus;
import com.hotel.backend.constant.RoomStatus;
import com.hotel.backend.entity.Room;
import com.hotel.backend.entity.RoomType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoomViewMapperTest {

    @Test
    void preservesExistingAuditSnapshotFields() {
        RoomType roomType = RoomType.builder().typeName("Deluxe").build();
        roomType.setId(9L);
        Room room = Room.builder()
                .roomName("D-201")
                .roomType(roomType)
                .floor(2)
                .status(RoomStatus.MAINTENANCE)
                .cleaningStatus(CleaningStatus.DIRTY)
                .maintenanceReason("Điều hòa")
                .maintenanceExpectedCompletedDate(LocalDate.of(2026, 7, 24))
                .build();
        room.setId(21L);

        Map<String, Object> snapshot = RoomViewMapper.auditSnapshot(room);

        assertEquals(21L, snapshot.get("id"));
        assertEquals("D-201", snapshot.get("roomName"));
        assertEquals(9L, snapshot.get("roomTypeId"));
        assertEquals("MAINTENANCE", snapshot.get("status"));
        assertEquals("DIRTY", snapshot.get("cleaningStatus"));
        assertEquals("Điều hòa", snapshot.get("maintenanceReason"));
    }
}
