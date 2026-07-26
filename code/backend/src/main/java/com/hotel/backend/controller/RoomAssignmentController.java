package com.hotel.backend.controller;

import com.hotel.backend.constant.CleaningStatus;
import com.hotel.backend.constant.RoomStatus;
import com.hotel.backend.dto.request.TransferRoomRequest;
import com.hotel.backend.dto.response.RoomResponse;
import com.hotel.backend.service.RoomAssignmentUseCases;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomAssignmentController {

    private final RoomAssignmentUseCases roomAssignment;

    @Operation(summary = "Get available Rooms for reservation", description = "API retrieve available rooms for a reservation")
    @GetMapping("/available-for-reservation")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<List<RoomResponse>> getAvailableRoomsForReservation(
            @RequestParam Long reservationId,
            @RequestParam(required = false) Long roomTypeId) {
        return ResponseEntity.ok(roomAssignment.getAvailableRoomsForReservation(reservationId, roomTypeId));
    }

    @Operation(summary = "Update Room status", description = "API update room status by id")
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<RoomResponse> updateStatus(
            @PathVariable Long id,
            @RequestParam RoomStatus status) {
        return ResponseEntity.ok(roomAssignment.updateStatus(id, status));
    }

    @Operation(summary = "Update Room cleaning status", description = "API update room cleaning status by id")
    @PatchMapping("/{id}/cleaning-status")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<RoomResponse> updateCleaningStatus(
            @PathVariable Long id,
            @RequestParam CleaningStatus cleaningStatus) {
        return ResponseEntity.ok(roomAssignment.updateCleaningStatus(id, cleaningStatus));
    }

    @Operation(summary = "Transfer checked-in room", description = "Move the active stay and its guests to another available clean room")
    @PatchMapping("/{sourceRoomId}/transfer")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<RoomResponse> transferCheckedInRoom(
            @PathVariable Long sourceRoomId,
            @Valid @RequestBody TransferRoomRequest request) {
        return ResponseEntity.ok(
                roomAssignment.transferCheckedInRoom(sourceRoomId, request.getTargetRoomId()));
    }

    @Operation(summary = "Get active reservation by room", description = "Resolve the checked-in reservation currently occupying a room")
    @GetMapping("/{roomId}/active-reservation")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<Map<String, Long>> getActiveReservation(@PathVariable Long roomId) {
        return ResponseEntity.ok(
                Map.of("reservationId", roomAssignment.getActiveReservationId(roomId)));
    }
}
