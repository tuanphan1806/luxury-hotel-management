package com.hotel.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.constant.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Append-only, line-level pricing evidence.
 *
 * <p>Each lifecycle repricing appends another sequence instead of mutating a
 * previous snapshot. The invoice consumes the final applicable snapshot.</p>
 */
@Entity
@Immutable
@Table(
        name = "reservation_rate_snapshots",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_rate_snapshot_sequence",
                columnNames = {"reservation_room_type_id", "snapshot_sequence"}))
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationRateSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_room_type_id", nullable = false)
    private ReservationRoomType reservationRoomType;

    @Column(name = "snapshot_sequence", nullable = false)
    private Integer snapshotSequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "snapshot_stage", nullable = false, length = 24)
    private RateSnapshotStage snapshotStage;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stay_policy_version_id", nullable = false)
    private StayPolicyVersion stayPolicyVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rate_profile_id", nullable = false)
    private RoomRateProfile rateProfile;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_algorithm_version", nullable = false, length = 32)
    private PricingAlgorithmVersion pricingAlgorithmVersion;

    @Column(name = "committed_check_in", nullable = false)
    private LocalDateTime committedCheckIn;

    @Column(name = "committed_check_out", nullable = false)
    private LocalDateTime committedCheckOut;

    @Column(name = "actual_check_in")
    private LocalDateTime actualCheckIn;

    @Column(name = "actual_check_out")
    private LocalDateTime actualCheckOut;

    @Enumerated(EnumType.STRING)
    @Column(name = "stay_classification", nullable = false, length = 24)
    private StayClassification stayClassification;

    @Enumerated(EnumType.STRING)
    @Column(name = "initial_package", nullable = false, length = 24)
    private StayPackage initialPackage;

    @Enumerated(EnumType.STRING)
    @Column(name = "applied_package", nullable = false, length = 24)
    private StayPackage appliedPackage;

    @Enumerated(EnumType.STRING)
    @Column(name = "max_package_reached", nullable = false, length = 24)
    private StayPackage maxPackageReached;

    @Enumerated(EnumType.STRING)
    @Column(name = "transition_reason", nullable = false, length = 40)
    private PricingTransitionReason transitionReason;

    @Column(name = "included_guests", nullable = false)
    private Integer includedGuests;

    @Column(name = "max_guests_snapshot", nullable = false)
    private Integer maxGuestsSnapshot;

    @Column(name = "line_guest_count", nullable = false)
    private Integer lineGuestCount;

    @Column(name = "room_quantity", nullable = false)
    private Integer roomQuantity;

    @Column(name = "extra_guest_count", nullable = false)
    private Integer extraGuestCount;

    @Column(name = "first_block_minutes", nullable = false)
    private Integer firstBlockMinutes;

    @Column(name = "first_block_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal firstBlockPrice;

    @Column(name = "extra_unit_minutes", nullable = false)
    private Integer extraUnitMinutes;

    @Column(name = "extra_unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal extraUnitPrice;

    @Column(name = "grace_minutes", nullable = false)
    private Integer graceMinutes;

    @Column(name = "overnight_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal overnightPrice;

    @Column(name = "overnight_included_checkout")
    private LocalDateTime overnightIncludedCheckout;

    @Column(name = "daily_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal dailyPrice;

    @Column(name = "daily_duration_minutes", nullable = false)
    private Integer dailyDurationMinutes;

    @Column(name = "full_days", nullable = false)
    private Integer fullDays;

    @Column(name = "remainder_minutes", nullable = false)
    private Integer remainderMinutes;

    @Column(name = "charged_extra_units", nullable = false)
    private Integer chargedExtraUnits;

    @Column(name = "minimum_committed_room_charge", nullable = false, precision = 14, scale = 2)
    private BigDecimal minimumCommittedRoomCharge;

    @Column(name = "final_room_charge", nullable = false, precision = 14, scale = 2)
    private BigDecimal finalRoomCharge;

    @Column(name = "extra_guest_charge", nullable = false, precision = 14, scale = 2)
    private BigDecimal extraGuestCharge;

    @Column(name = "allocated_service_charge", nullable = false, precision = 14, scale = 2)
    private BigDecimal allocatedServiceCharge;

    @Column(name = "adjustment_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal adjustmentAmount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "breakdown_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode breakdownJson;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "snapshot_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String snapshotHash;

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;
}
