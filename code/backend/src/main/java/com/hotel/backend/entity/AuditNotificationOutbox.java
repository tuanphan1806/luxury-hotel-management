package com.hotel.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.constant.AuditNotificationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "audit_notification_outbox")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditNotificationOutbox {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "audit_log_id", nullable = false)
    private ReservationAuditLog auditLog;

    @Column(name = "notification_type", nullable = false, length = 32)
    private String notificationType;

    @Column(name = "recipient_email", nullable = false, length = 255)
    private String recipientEmail;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AuditNotificationStatus status = AuditNotificationStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode payloadJson;

    @Builder.Default
    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "next_attempt_at_utc", nullable = false)
    private Instant nextAttemptAtUtc;

    @Column(name = "last_attempt_at_utc")
    private Instant lastAttemptAtUtc;

    @Column(name = "sent_at_utc")
    private Instant sentAtUtc;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;

    @Column(name = "updated_at_utc", nullable = false)
    private Instant updatedAtUtc;
}
