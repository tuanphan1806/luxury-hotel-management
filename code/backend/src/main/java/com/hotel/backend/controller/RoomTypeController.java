package com.hotel.backend.controller;

import com.hotel.backend.dto.request.RoomTypeRequest;
import com.hotel.backend.dto.request.RoomTypeStatusRequest;
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.RoomTypeResponse;
import com.hotel.backend.service.RoomTypeService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.math.BigDecimal;
import java.util.List;

/**
 * REST Controller cho RoomType.
 *
 * Base URL: /api/room-types
 *
 * GET    /api/room-types                          → getAll()
 * GET    /api/room-types/{id}                     → getById()
 * GET    /api/room-types?minPrice=&maxPrice=      → getByPriceRange()
 * POST   /api/room-types                          → create()
 * PUT    /api/room-types/{id}                     → update()
 * DELETE /api/room-types/{id}                     → delete()
 */
@RestController
@RequestMapping("/api/room-types")
@RequiredArgsConstructor
@Slf4j(topic = "ROOM-TYPE-CONTROLLER")
public class RoomTypeController {

    private final RoomTypeService roomTypeService;

    @Operation(
            summary = "Get list Room Type",
            description = "Retrieve room types. With Pricing V2 enabled, "
                    + "minPrice/maxPrice filter the effective overnight rate; "
                    + "rates come exclusively from the effective versioned rate profile.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<RoomTypeResponse>>> getAll(
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice) {

        List<RoomTypeResponse> data = (minPrice != null && maxPrice != null)
                ? roomTypeService.getByPriceRange(minPrice, maxPrice)
                : roomTypeService.getAll();

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @Operation(summary = "Get all room types for administration")
    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<RoomTypeResponse>>> getAllForAdmin() {
        return ResponseEntity.ok(ApiResponse.success(
                roomTypeService.getAllForAdmin()));
    }

    @Operation(summary = "Get detail Room Type", description = "API retrieve room type detail by id")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<RoomTypeResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(roomTypeService.getById(id)));
    }

    @Operation(
            summary = "Create Room Type",
            description = "Create a room type and its complete effective "
                    + "hourly/overnight/daily rate profile atomically.")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RoomTypeResponse>> create(
            @Valid @RequestBody RoomTypeRequest request) {

        RoomTypeResponse created = roomTypeService.create(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo loại phòng thành công", created));
    }

    @Operation(
            summary = "Update Room Type",
            description = "Update catalog information and, when rates change, "
                    + "append a new effective rate-profile version. Existing "
                    + "reservation snapshots are unchanged.")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RoomTypeResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody RoomTypeRequest request) {

        RoomTypeResponse updated = roomTypeService.update(id, request);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật loại phòng thành công", updated));
    }

    @Operation(summary = "Activate or deactivate a room type")
    @PatchMapping("/{id}/active")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RoomTypeResponse>> setActive(
            @PathVariable Long id,
            @Valid @RequestBody RoomTypeStatusRequest request) {
        RoomTypeResponse updated = roomTypeService.setActive(id, request);
        String message = Boolean.TRUE.equals(request.getActive())
                ? "Kích hoạt loại phòng thành công"
                : "Ngừng hoạt động loại phòng thành công";
        return ResponseEntity.ok(ApiResponse.success(message, updated));
    }

    @Operation(
            summary = "Permanently delete an unused room type",
            description = "Only an inactive room type without rooms, bookings, reviews or financial quote history can be deleted.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        roomTypeService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Xóa loại phòng thành công"));
    }
}
