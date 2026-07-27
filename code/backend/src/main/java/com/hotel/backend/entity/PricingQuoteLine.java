package com.hotel.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.constant.PricingTransitionReason;
import com.hotel.backend.constant.StayClassification;
import com.hotel.backend.constant.StayPackage;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Immutable
@Table(
        name = "pricing_quote_lines",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_pricing_quote_room_type",
                columnNames = {"pricing_quote_id", "room_type_id"}))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PricingQuoteLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pricing_quote_id", nullable = false)
    private PricingQuote pricingQuote;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false)
    private RoomType roomType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rate_profile_id", nullable = false)
    private RoomRateProfile rateProfile;

    @Column(name = "room_type_code_snapshot", nullable = false, length = 40)
    private String roomTypeCodeSnapshot;

    @Column(name = "rate_profile_version", nullable = false)
    private Integer rateProfileVersion;

    @Column(name = "room_quantity", nullable = false)
    private Integer roomQuantity;

    @Column(name = "line_guest_count", nullable = false)
    private Integer lineGuestCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "stay_classification", nullable = false, length = 24)
    private StayClassification stayClassification;

    @Enumerated(EnumType.STRING)
    @Column(name = "applied_package", nullable = false, length = 24)
    private StayPackage appliedPackage;

    @Enumerated(EnumType.STRING)
    @Column(name = "transition_reason", nullable = false, length = 40)
    private PricingTransitionReason transitionReason;

    @Column(name = "package_included_checkout", nullable = false)
    private LocalDateTime packageIncludedCheckout;

    @Column(name = "room_charge", nullable = false, precision = 14, scale = 2)
    private BigDecimal roomCharge;

    @Column(name = "extra_guest_charge", nullable = false, precision = 14, scale = 2)
    private BigDecimal extraGuestCharge;

    @Column(name = "line_total_before_services", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotalBeforeServices;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "breakdown_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode breakdownJson;
}
