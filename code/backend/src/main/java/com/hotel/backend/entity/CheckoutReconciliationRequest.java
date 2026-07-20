package com.hotel.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.constant.CheckoutCorrectionType;
import com.hotel.backend.constant.CheckoutReconciliationRequestStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "checkout_reconciliation_requests")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutReconciliationRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CheckoutReconciliationRequestStatus status = CheckoutReconciliationRequestStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mismatch_snapshot_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode mismatchSnapshotJson;

    @Column(name = "reason_code", nullable = false, length = 80)
    private String reasonCode;

    @Column(name = "reason_note", nullable = false, columnDefinition = "text")
    private String reasonNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by")
    private User requestedBy;

    @Column(name = "requested_by_name", nullable = false, length = 150)
    private String requestedByName;

    @Column(name = "requested_by_role", nullable = false, length = 32)
    private String requestedByRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "correction_type", length = 32)
    private CheckoutCorrectionType correctionType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "correction_detail_json", columnDefinition = "jsonb")
    private JsonNode correctionDetailJson;

    @Column(name = "resolution_reason_code", length = 80)
    private String resolutionReasonCode;

    @Column(name = "resolution_note", columnDefinition = "text")
    private String resolutionNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private User resolvedBy;

    @Column(name = "resolved_by_name", length = 150)
    private String resolvedByName;

    @Column(name = "resolved_by_role", length = 32)
    private String resolvedByRole;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evidence_asset_id")
    private MediaAsset evidenceAsset;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "actor_scope", nullable = false, length = 190)
    private String actorScope;

    @Column(name = "correlation_id", nullable = false, length = 128)
    private String correlationId;

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;

    @Column(name = "resolved_at_utc")
    private Instant resolvedAtUtc;
}
