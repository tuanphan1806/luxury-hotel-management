
package com.hotel.backend.controller;

import com.hotel.backend.dto.request.GalleryRequest;
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.GalleryResponse;
import com.hotel.backend.service.GalleryService;
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
 * REST Controller cho Gallery.
 *
 * Base URL: /api/galleries
 *
 * GET    /api/galleries                           → getAll()
 * GET    /api/galleries?type=INTERIOR             → getByType()
 * GET    /api/galleries?keyword=pool              → search()
 * GET    /api/galleries/{id}                      → getById()
 * POST   /api/galleries                           → create()
 * PUT    /api/galleries/{id}                      → update()
 * DELETE /api/galleries/{id}                      → delete()
 */
@RestController
@RequestMapping("/api/galleries")
@RequiredArgsConstructor
@Slf4j(topic = "GALLERY-CONTROLLER")
public class GalleryController {

    private final GalleryService galleryService;

    @Operation(summary = "Get list Gallery", description = "API retrieve galleries or filter by type or keyword")
    @GetMapping
    public ResponseEntity<ApiResponse<List<GalleryResponse>>> getAll(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword) {

        List<GalleryResponse> data;

        if (keyword != null && !keyword.isBlank()) {
            data = galleryService.search(keyword);
        } else if (type != null && !type.isBlank()) {
            data = galleryService.getByType(type);
        } else {
            data = galleryService.getAll();
        }

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @Operation(summary = "Get detail Gallery", description = "API retrieve gallery detail by id")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<GalleryResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(
                ApiResponse.success(galleryService.getById(id))
        );
    }

    @Operation(summary = "Create Gallery", description = "API create a new gallery image")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<GalleryResponse>> create(
            @Valid @RequestBody GalleryRequest request) {

        GalleryResponse created = galleryService.create(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo ảnh thành công", created));
    }

    @Operation(summary = "Update Gallery", description = "API update gallery image information by id")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<GalleryResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody GalleryRequest request) {

        GalleryResponse updated = galleryService.update(id, request);

        return ResponseEntity.ok(
                ApiResponse.success("Cập nhật ảnh thành công", updated)
        );
    }

    @Operation(summary = "Delete Gallery", description = "API delete gallery image by id")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {

        galleryService.delete(id);

        return ResponseEntity.ok(
                ApiResponse.success("Xóa ảnh thành công")
        );
    }
}
