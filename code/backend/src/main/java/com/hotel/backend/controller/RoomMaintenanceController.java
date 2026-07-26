package com.hotel.backend.controller;

import com.hotel.backend.dto.request.RoomMaintenanceLogRequest;
import com.hotel.backend.dto.request.RoomMaintenanceRequest;
import com.hotel.backend.dto.response.RoomResponse;
import com.hotel.backend.service.RoomMaintenanceUseCases;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomMaintenanceController {

    private final RoomMaintenanceUseCases roomMaintenance;

    @Operation(summary = "Start room maintenance")
    @PatchMapping("/{roomId}/maintenance/start")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<RoomResponse> startMaintenance(
            @PathVariable Long roomId,
            @Valid @RequestBody RoomMaintenanceRequest request) {
        return ResponseEntity.ok(roomMaintenance.startMaintenance(roomId, request));
    }

    @Operation(summary = "Add room maintenance log")
    @PostMapping("/{roomId}/maintenance/logs")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<RoomResponse> addMaintenanceLog(
            @PathVariable Long roomId,
            @Valid @RequestBody RoomMaintenanceLogRequest request) {
        return ResponseEntity.ok(roomMaintenance.addMaintenanceLog(roomId, request.getNote()));
    }

    @Operation(summary = "Complete room maintenance")
    @PatchMapping("/{roomId}/maintenance/complete")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<RoomResponse> completeMaintenance(@PathVariable Long roomId) {
        return ResponseEntity.ok(roomMaintenance.completeMaintenance(roomId));
    }
}
