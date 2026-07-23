package com.hotel.backend.controller;

import com.hotel.backend.constant.AuditCategory;
import com.hotel.backend.constant.AuditRiskLevel;
import com.hotel.backend.constant.AuditScope;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.ReservationAuditLogResponse;
import com.hotel.backend.service.ReservationAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminAuditController {
    private final ReservationAuditService auditService;

    @GetMapping
    public ApiResponse<Page<ReservationAuditLogResponse>> search(
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String actorRole,
            @RequestParam(required = false) ReservationAuditAction action,
            @RequestParam(required = false) AuditCategory category,
            @RequestParam(required = false) AuditScope scope,
            @RequestParam(required = false) AuditRiskLevel riskLevel,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.success(auditService.search(
                targetType, targetId, actor, actorRole, action, category, scope, riskLevel,
                from, to, page, size));
    }

    @GetMapping("/actors")
    public ApiResponse<List<String>> actors(@RequestParam(required = false) String query) {
        return ApiResponse.success(auditService.findActors(query));
    }
}
