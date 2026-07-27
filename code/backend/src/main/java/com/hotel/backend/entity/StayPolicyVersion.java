package com.hotel.backend.entity;

import com.hotel.backend.constant.InventoryProtectionMode;
import com.hotel.backend.persistence.LocalTimeWithoutTimezoneType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.time.LocalTime;

/**
 * Versioned operational stay policy shared by room-rate profiles.
 *
 * <p>Financial amounts deliberately do not live here. Turnover protection is
 * an inventory concern and must never be added to chargeable stay duration.</p>
 */
@Entity
@Table(
        name = "stay_policy_versions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_stay_policy_code_version",
                columnNames = {"policy_code", "policy_version"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StayPolicyVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_code", nullable = false, length = 64)
    private String policyCode;

    @Column(name = "policy_version", nullable = false)
    private Integer policyVersion;

    @Column(name = "grace_minutes", nullable = false)
    private Integer graceMinutes;

    @Column(name = "overnight_start_time", nullable = false, columnDefinition = "time")
    @Type(LocalTimeWithoutTimezoneType.class)
    private LocalTime overnightStartTime;

    @Column(name = "overnight_early_morning_end", nullable = false, columnDefinition = "time")
    @Type(LocalTimeWithoutTimezoneType.class)
    private LocalTime overnightEarlyMorningEnd;

    @Column(name = "overnight_hard_checkout_time", nullable = false, columnDefinition = "time")
    @Type(LocalTimeWithoutTimezoneType.class)
    private LocalTime overnightHardCheckoutTime;

    @Column(name = "overnight_maximum_minutes", nullable = false)
    private Integer overnightMaximumMinutes;

    @Column(name = "daily_threshold_minutes", nullable = false)
    private Integer dailyThresholdMinutes;

    @Column(name = "daily_duration_minutes", nullable = false)
    private Integer dailyDurationMinutes;

    @Column(name = "turnover_buffer_minutes", nullable = false)
    private Integer turnoverBufferMinutes;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "inventory_protection_mode", nullable = false, length = 32)
    private InventoryProtectionMode inventoryProtectionMode =
            InventoryProtectionMode.PACKAGE_ENTITLEMENT;

    @Column(name = "effective_from_utc", nullable = false)
    private Instant effectiveFromUtc;

    @Column(name = "effective_to_utc")
    private Instant effectiveToUtc;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private User createdBy;

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;
}
