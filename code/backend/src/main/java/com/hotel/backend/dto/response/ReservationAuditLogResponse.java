package com.hotel.backend.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.constant.AuditCategory;
import com.hotel.backend.constant.AuditRiskLevel;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.entity.ReservationAuditLog;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ReservationAuditLogResponse {
    private Long id;
    private Long reservationId;
    private String reservationCode;
    private String targetType;
    private String targetId;
    private ReservationAuditAction action;
    private String actionCode;
    private String actorName;
    private String actorRole;
    private String details;
    private JsonNode oldValue;
    private JsonNode newValue;
    private JsonNode detail;
    private AuditRiskLevel riskLevel;
    private AuditCategory category;
    private String correlationId;
    private Instant occurredAtUtc;

    public static ReservationAuditLogResponse from(ReservationAuditLog log) {
        return ReservationAuditLogResponse.builder()
                .id(log.getId())
                .reservationId(log.getReservationId())
                .reservationCode(log.getReservationCode())
                .targetType(log.getTargetType())
                .targetId(log.getTargetId())
                .action(log.getAction())
                .actionCode(log.getActionCode())
                .actorName(log.getActorName()).actorRole(log.getActorRole())
                .details(log.getDetails())
                .oldValue(log.getOldValueJson())
                .newValue(log.getNewValueJson())
                .detail(log.getDetailJson())
                .riskLevel(log.getRiskLevel())
                .category(log.getCategory())
                .correlationId(log.getCorrelationId())
                .occurredAtUtc(log.getOccurredAtUtc())
                .build();
    }
}
