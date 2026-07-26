package com.hotel.backend.controller;

import com.hotel.backend.constant.ReservationServiceOrigin;
import com.hotel.backend.dto.request.AddOnServiceRequest;
import com.hotel.backend.dto.response.AddOnServiceResponse;
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.service.AddOnServiceCatalogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/add-on-services")
@RequiredArgsConstructor
public class AddOnServiceController {

    private final AddOnServiceCatalogService service;

    @GetMapping
    public ApiResponse<List<AddOnServiceResponse>> listActive(
            @RequestParam(required = false) ReservationServiceOrigin flow) {
        return ApiResponse.success(service.listActive(flow));
    }

    @GetMapping("/{id}")
    public ApiResponse<AddOnServiceResponse> getActive(@PathVariable Long id) {
        return ApiResponse.success(service.getActive(id));
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<AddOnServiceResponse>> listAll() {
        return ApiResponse.success(service.listAll());
    }

    @PostMapping("/admin")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AddOnServiceResponse> create(
            @Valid @RequestBody AddOnServiceRequest request) {
        return ApiResponse.success(
                "Tạo dịch vụ thêm thành công",
                service.create(request));
    }

    @PutMapping("/admin/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AddOnServiceResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody AddOnServiceRequest request) {
        return ApiResponse.success(
                "Cập nhật dịch vụ thêm thành công",
                service.update(id, request));
    }

    @PatchMapping("/admin/{id}/activation")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AddOnServiceResponse> setActive(
            @PathVariable Long id,
            @RequestParam boolean active) {
        return ApiResponse.success(
                active ? "Đã kích hoạt dịch vụ" : "Đã ngừng cung cấp dịch vụ",
                service.setActive(id, active));
    }
}
