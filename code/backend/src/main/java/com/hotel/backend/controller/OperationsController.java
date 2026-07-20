package com.hotel.backend.controller;

import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.OperationsAttentionResponse;
import com.hotel.backend.dto.response.OperationsDashboardResponse;
import com.hotel.backend.service.OperationsAttentionService;
import com.hotel.backend.service.OperationsDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operations")
@RequiredArgsConstructor
public class OperationsController {
    private final OperationsAttentionService attentionService;
    private final OperationsDashboardService dashboardService;

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<OperationsDashboardResponse> getDashboardSummary() {
        return ApiResponse.success(dashboardService.getSummary());
    }

    @GetMapping("/attention")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ApiResponse<OperationsAttentionResponse> getAttentionQueue() {
        return ApiResponse.success(attentionService.getQueue());
    }
}
