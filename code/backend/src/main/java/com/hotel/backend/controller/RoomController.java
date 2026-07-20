package com.hotel.backend.controller;

import com.hotel.backend.constant.CleaningStatus;
import com.hotel.backend.constant.RoomStatus;
import com.hotel.backend.dto.request.RoomRequest;
import com.hotel.backend.dto.request.TransferRoomRequest;
import com.hotel.backend.dto.request.RoomMaintenanceRequest;
import com.hotel.backend.dto.request.RoomMaintenanceLogRequest;
import com.hotel.backend.dto.response.RoomPageResponse;
import com.hotel.backend.dto.response.RoomResponse;
import com.hotel.backend.service.RoomService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
@Slf4j(topic = "ROOM-CONTROLLER")
public class RoomController {

    private final RoomService roomService;

    @Operation(summary = "Create Room", description = "API create a new room")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RoomResponse> create(@Valid @RequestBody RoomRequest request) {
        return ResponseEntity.ok(roomService.create(request));
    }

    @Operation(summary = "Update Room", description = "API update room information by id")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RoomResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody RoomRequest request) {
        return ResponseEntity.ok(roomService.update(id, request));
    }

    @Operation(summary = "Get detail Room", description = "API retrieve room detail by id")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<RoomResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(roomService.getById(id));
    }

    @Operation(summary = "Get all Rooms", description = "API retrieve all rooms")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<List<RoomResponse>> getAll() {
        return ResponseEntity.ok(roomService.getAll());
    }


    @Operation(summary = "Get list Room", description = "API get list rooms")
    @GetMapping("/list")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public Map<String, Object> getList(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        RoomPageResponse pageResponse = roomService.findAll(keyword, sort, page, size);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", HttpStatus.OK.value());
        result.put("message", "Get room list successfully");
        result.put("data", pageResponse);
        return result;
    }


    @Operation(summary = "Search Rooms", description = "API search rooms by keyword, room status, or cleaning status")
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<List<RoomResponse>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) RoomStatus status,
            @RequestParam(required = false) CleaningStatus cleaningStatus) {
        return ResponseEntity.ok(roomService.search(keyword, status, cleaningStatus));
    }

    @Operation(summary = "Get available Rooms for reservation", description = "API retrieve available rooms for a reservation")
    @GetMapping("/available-for-reservation")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<List<RoomResponse>> getAvailableRoomsForReservation(
            @RequestParam Long reservationId,
            @RequestParam(required = false) Long roomTypeId) {
        return ResponseEntity.ok(roomService.getAvailableRoomsForReservation(reservationId, roomTypeId));
    }

    @Operation(summary = "Delete Room", description = "API delete room by id")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        roomService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Update Room status", description = "API update room status by id")
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<RoomResponse> updateStatus(@PathVariable Long id,
                                                     @RequestParam RoomStatus status) {
        return ResponseEntity.ok(roomService.updateStatus(id, status));
    }

    @Operation(summary = "Update Room cleaning status", description = "API update room cleaning status by id")
    @PatchMapping("/{id}/cleaning-status")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<RoomResponse> updateCleaningStatus(@PathVariable Long id,
                                                             @RequestParam CleaningStatus cleaningStatus) {
        return ResponseEntity.ok(roomService.updateCleaningStatus(id, cleaningStatus));
    }

    @Operation(summary = "Transfer checked-in room", description = "Move the active stay and its guests to another available clean room")
    @PatchMapping("/{sourceRoomId}/transfer")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<RoomResponse> transferCheckedInRoom(
            @PathVariable Long sourceRoomId,
            @Valid @RequestBody TransferRoomRequest request) {
        return ResponseEntity.ok(roomService.transferCheckedInRoom(sourceRoomId, request.getTargetRoomId()));
    }

    @Operation(summary = "Get active reservation by room", description = "Resolve the checked-in reservation currently occupying a room")
    @GetMapping("/{roomId}/active-reservation")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<Map<String, Long>> getActiveReservation(@PathVariable Long roomId) {
        return ResponseEntity.ok(Map.of("reservationId", roomService.getActiveReservationId(roomId)));
    }

    @Operation(summary = "Start room maintenance")
    @PatchMapping("/{roomId}/maintenance/start")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<RoomResponse> startMaintenance(
            @PathVariable Long roomId,
            @Valid @RequestBody RoomMaintenanceRequest request) {
        return ResponseEntity.ok(roomService.startMaintenance(roomId, request));
    }

    @Operation(summary = "Add room maintenance log")
    @PostMapping("/{roomId}/maintenance/logs")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<RoomResponse> addMaintenanceLog(
            @PathVariable Long roomId,
            @Valid @RequestBody RoomMaintenanceLogRequest request) {
        return ResponseEntity.ok(roomService.addMaintenanceLog(roomId, request.getNote()));
    }

    @Operation(summary = "Complete room maintenance")
    @PatchMapping("/{roomId}/maintenance/complete")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<RoomResponse> completeMaintenance(@PathVariable Long roomId) {
        return ResponseEntity.ok(roomService.completeMaintenance(roomId));
    }
}
