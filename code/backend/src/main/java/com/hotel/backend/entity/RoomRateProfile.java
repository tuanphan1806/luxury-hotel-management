package com.hotel.backend.entity;

import com.hotel.backend.constant.ExtraGuestBillingMode;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable-by-version financial configuration for one room type.
 *
 * <p>A price change closes the current row and inserts a new version. Existing
 * reservations retain the referenced version and their committed snapshot.</p>
 */
@Entity
@Table(
        name = "room_rate_profiles",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_room_rate_profile_version",
                columnNames = {"room_type_id", "profile_version"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomRateProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false)
    private RoomType roomType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stay_policy_version_id", nullable = false)
    private StayPolicyVersion stayPolicyVersion;

    @Column(name = "profile_version", nullable = false)
    private Integer profileVersion;

    @Column(name = "included_guests", nullable = false)
    private Integer includedGuests;

    @Column(name = "first_block_minutes", nullable = false)
    private Integer firstBlockMinutes;

    @Column(name = "first_block_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal firstBlockPrice;

    @Column(name = "extra_unit_minutes", nullable = false)
    private Integer extraUnitMinutes;

    @Column(name = "extra_unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal extraUnitPrice;

    @Column(name = "overnight_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal overnightPrice;

    @Column(name = "daily_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal dailyPrice;

    @Column(name = "extra_guest_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal extraGuestPrice;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "extra_guest_billing_mode", nullable = false, length = 32)
    private ExtraGuestBillingMode extraGuestBillingMode =
            ExtraGuestBillingMode.PER_PACKAGE_CYCLE;

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
