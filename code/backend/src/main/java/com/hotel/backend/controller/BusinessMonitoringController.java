package com.hotel.backend.controller;

import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.BusinessMonitoringSummaryResponse;
import com.hotel.backend.service.BusinessMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/monitoring")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class BusinessMonitoringController {
    private final BusinessMonitoringService monitoringService;

    @GetMapping("/summary")
    public ApiResponse<BusinessMonitoringSummaryResponse> summary() {
        return ApiResponse.success(monitoringService.summary());
    }
}
