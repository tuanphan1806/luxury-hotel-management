package com.hotel.backend.controller;
 
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.GuestResponse;
import com.hotel.backend.service.GuestService;
import com.hotel.backend.dto.request.GuestRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
 
import java.util.List;
 
@RestController
@RequestMapping("/api/guests")
@RequiredArgsConstructor
@Slf4j(topic = "GUEST-CONTROLLER")
public class GuestController {
 
    private final GuestService guestService;

    @Operation(summary = "Get retained guests", description = "Staff/Admin retrieves current and recently checked-out guests")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<List<GuestResponse>> getAllGuests() {
        return ApiResponse.success(guestService.getAllGuests());
    }
 
    // ── Staff: xem danh sách khách trong 1 phòng ─────────────────────────────
    @Operation(summary = "Get guests by reservation room", description = "API retrieve guests in a reservation room")
    @GetMapping("/reservation-room/{reservationRoomId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<List<GuestResponse>> getGuestsByReservationRoom(
            @PathVariable Long reservationRoomId) {
        return ApiResponse.success(guestService.getGuestsByReservationRoom(reservationRoomId));
    }
 
    // ── Staff: xem toàn bộ khách của 1 reservation ───────────────────────────
    @Operation(summary = "Get guests by reservation", description = "API retrieve all guests in a reservation")
    @GetMapping("/reservation/{reservationId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<List<GuestResponse>> getGuestsByReservation(
            @PathVariable Long reservationId) {
        return ApiResponse.success(guestService.getGuestsByReservation(reservationId));
    }

    @Operation(summary = "Update guest", description = "Staff/Admin updates guest identity and contact information")
    @PatchMapping("/{guestId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<GuestResponse> updateGuest(
            @PathVariable Long guestId,
            @Valid @RequestBody GuestRequest request) {
        return ApiResponse.success("Cập nhật thông tin khách thành công",
                guestService.updateGuest(guestId, request));
    }
}
