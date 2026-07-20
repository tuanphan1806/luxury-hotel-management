package com.hotel.backend.dto.response;

import com.hotel.backend.constant.CleaningStatus;
import com.hotel.backend.constant.RoomStatus;
import com.hotel.backend.entity.Room;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class RoomResponse {

    private Long id;
    private String roomName;
    private Integer floor;
    private RoomStatus status;
    private CleaningStatus cleaningStatus;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // RoomType info (flatten, không nest cả object)
    private Long roomTypeId;
    private String roomTypeName;
    private String roomTypeNameEn;
    private BigDecimal price;
    private String maintenanceReason;
    private LocalDate maintenanceExpectedCompletedDate;
    private List<MaintenanceHistoryItem> maintenanceHistory;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class MaintenanceHistoryItem {
        private LocalDateTime date;
        private String action;
        private String note;
    }

    // Static mapper
    public static RoomResponse from(Room room) {
        RoomResponseBuilder builder = RoomResponse.builder()
                .id(room.getId())
                .roomName(room.getRoomName())
                .floor(room.getFloor())
                .status(room.getStatus())
                .cleaningStatus(room.getCleaningStatus())
                .description(room.getDescription())
                .createdAt(room.getCreatedAt())
                .updatedAt(room.getUpdatedAt());

        builder.maintenanceReason(room.getMaintenanceReason())
                .maintenanceExpectedCompletedDate(room.getMaintenanceExpectedCompletedDate())
                .maintenanceHistory(room.getMaintenanceHistory().stream()
                        .map(log -> MaintenanceHistoryItem.builder()
                                .date(log.getCreatedAt())
                                .action(log.getAction())
                                .note(log.getNote())
                                .build())
                        .toList());

        if (room.getRoomType() != null) {
            builder.roomTypeId(room.getRoomType().getId())
                   .roomTypeName(room.getRoomType().getTypeName())
                   .roomTypeNameEn(room.getRoomType().getTypeNameEn())
                   .price(room.getRoomType().getPrice());
        }

        return builder.build();
    }
}
