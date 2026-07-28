package com.hotel.backend.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.constant.PricingAlgorithmVersion;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable server-side evidence for a customer-visible price quote.
 */
@Entity
@Immutable
@Table(name = "pricing_quotes")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PricingQuote {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stay_policy_version_id", nullable = false)
    private StayPolicyVersion stayPolicyVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_algorithm_version", nullable = false, length = 32)
    private PricingAlgorithmVersion pricingAlgorithmVersion;

    @Column(name = "check_in", nullable = false)
    private LocalDateTime checkIn;

    @Column(name = "check_out", nullable = false)
    private LocalDateTime checkOut;

    @Column(name = "guest_count", nullable = false)
    private Integer guestCount;

    @Column(name = "room_charge", nullable = false, precision = 14, scale = 2)
    private BigDecimal roomCharge;

    @Column(name = "extra_guest_charge", nullable = false, precision = 14, scale = 2)
    private BigDecimal extraGuestCharge;

    @Column(name = "service_charge", nullable = false, precision = 14, scale = 2)
    private BigDecimal serviceCharge;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "inventory_protected_until", nullable = false)
    private LocalDateTime inventoryProtectedUntil;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String requestHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "quote_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String quoteHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode requestJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode responseJson;

    @Column(name = "created_at_utc", nullable = false)
    private Instant createdAtUtc;

    @Column(name = "expires_at_utc", nullable = false)
    private Instant expiresAtUtc;
}
