package com.hotel.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.constant.AuditCategory;
import com.hotel.backend.constant.AuditRiskLevel;
import com.hotel.backend.constant.ReservationAuditAction;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@Table(name = "reservation_audit_logs", indexes = {
        @Index(name = "idx_reservation_audit_reservation_created", columnList = "reservation_id,created_at")
})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationAuditLog extends AbstractEntity<Long> {
    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "reservation_code", length = 50)
    private String reservationCode;

    @Column(name = "target_type", length = 64)
    private String targetType;

    @Column(name = "target_id")
    private String targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 40)
    private ReservationAuditAction action;

    @Column(name = "action_code", length = 80)
    private String actionCode;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_name", nullable = false, length = 150)
    private String actorName;

    @Column(name = "actor_role", nullable = false, length = 32)
    private String actorRole;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value_json", columnDefinition = "jsonb")
    private JsonNode oldValueJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value_json", columnDefinition = "jsonb")
    private JsonNode newValueJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "jsonb")
    private JsonNode detailJson;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 16)
    private AuditRiskLevel riskLevel = AuditRiskLevel.NORMAL;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private AuditCategory category = AuditCategory.BUSINESS;

    @Column(name = "dedup_key", length = 191)
    private String dedupKey;

    @Column(name = "occurred_at_utc")
    private java.time.Instant occurredAtUtc;
}
