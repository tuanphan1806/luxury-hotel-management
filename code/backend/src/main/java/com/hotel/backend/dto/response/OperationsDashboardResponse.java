package com.hotel.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class OperationsDashboardResponse {
    private LocalDateTime generatedAt;

    private long arrivalsToday;
    private long departuresToday;
    private long activeStays;
    private long bookingsCreatedToday;
    private long pendingConfirmations;
    private long cancellationRequests;

    private long totalRooms;
    private long availableRooms;
    private long occupiedRooms;
    private long maintenanceRooms;
    private long dirtyRooms;
    private int occupancyRate;

    // User là tài khoản đăng nhập; CustomerProfile là hồ sơ khách đặt/lưu trú.
    private long customerAccounts;
    private long customerProfiles;
}
