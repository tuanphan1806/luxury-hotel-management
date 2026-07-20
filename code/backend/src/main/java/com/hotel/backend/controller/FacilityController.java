package com.hotel.backend.controller;

import com.hotel.backend.dto.request.FacilityRequest;
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.FacilityResponse;
import com.hotel.backend.service.FacilityService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

/**
 * REST Controller cho Facility.
 *
 * Base URL: /api/facilities
 *
 * GET    /api/facilities                          → getAll()
 * GET    /api/facilities?type=WIFI                → getByType()
 * GET    /api/facilities?keyword=pool             → search()
 * GET    /api/facilities/{id}                     → getById()
 * POST   /api/facilities                          → create()
 * PUT    /api/facilities/{id}                     → update()
 * DELETE /api/facilities/{id}                     → delete()
 */
@RestController
@RequestMapping("/api/facilities")
@RequiredArgsConstructor
@Slf4j(topic = "FACILITY-CONTROLLER")
public class FacilityController {

    private final FacilityService facilityService;

    @Operation(summary = "Get list Facility", description = "API retrieve facilities or filter by type or keyword")
    @GetMapping
    public ResponseEntity<ApiResponse<List<FacilityResponse>>> getAll(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword) {

        List<FacilityResponse> data;

        if (keyword != null && !keyword.isBlank()) {
            data = facilityService.search(keyword);
        } else if (type != null && !type.isBlank()) {
            data = facilityService.getByType(type);
        } else {
            data = facilityService.getAll();
        }

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @Operation(summary = "Get detail Facility", description = "API retrieve facility detail by id")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<FacilityResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(facilityService.getById(id)));
    }

    @Operation(summary = "Create Facility", description = "API create a new facility")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<FacilityResponse>> create(
            @Valid @RequestBody FacilityRequest request) {

        FacilityResponse created = facilityService.create(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo tiện nghi thành công", created));
    }

    @Operation(summary = "Update Facility", description = "API update facility information by id")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<FacilityResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody FacilityRequest request) {

        FacilityResponse updated = facilityService.update(id, request);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật tiện nghi thành công", updated));
    }

    @Operation(summary = "Delete Facility", description = "API delete facility by id")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        facilityService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Xóa tiện nghi thành công"));
    }
}
