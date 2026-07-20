package com.hotel.backend.entity;

import com.hotel.backend.constant.IdempotencyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "idempotency_requests", uniqueConstraints = {
        @UniqueConstraint(name = "uk_idempotency_scope",
                columnNames = {"request_key", "operation", "actor_scope"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_key", nullable = false, length = 128)
    private String requestKey;

    @Column(nullable = false, length = 80)
    private String operation;

    @Column(name = "actor_scope", nullable = false, length = 190)
    private String actorScope;

    // SHA-256 is always a 64-character hexadecimal value. Keep the entity
    // Keep the JDBC type aligned with PostgreSQL's fixed-width bpchar column;
    // without this Hibernate treats a Java String as VARCHAR during validate.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false,
            length = 64, columnDefinition = "CHAR(64)")
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_json", columnDefinition = "TEXT")
    private String responseJson;

    @Column(name = "resource_type", length = 80)
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(name = "expires_at_utc", nullable = false)
    private Instant expiresAtUtc;

    @Column(name = "completed_at_utc")
    private Instant completedAtUtc;

    @CreationTimestamp
    @Column(name = "created_at_utc", nullable = false, updatable = false)
    private Instant createdAtUtc;

    @UpdateTimestamp
    @Column(name = "updated_at_utc", nullable = false)
    private Instant updatedAtUtc;

    @Version
    @Column(nullable = false)
    private Long version;
}
