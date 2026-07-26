package com.hotel.backend.entity;

import com.hotel.backend.constant.AddOnPricingUnit;
import com.hotel.backend.constant.ReservationServiceOrigin;
import com.hotel.backend.constant.ReservationServiceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "reservation_services", indexes = {
        @Index(name = "idx_reservation_services_reservation_status",
                columnList = "reservation_id,status,id"),
        @Index(name = "idx_reservation_services_service", columnList = "service_id,id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationServiceOrder extends AbstractEntity<Long> {

    @Version
    @Builder.Default
    @Column(nullable = false)
    private Long version = 0L;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private AddOnService service;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ReservationServiceOrigin origin;

    @Column(name = "service_code_snapshot", nullable = false, length = 64)
    private String serviceCodeSnapshot;

    @Column(name = "service_name_snapshot", nullable = false, length = 255)
    private String serviceNameSnapshot;

    @Column(name = "service_name_en_snapshot", length = 255)
    private String serviceNameEnSnapshot;

    @Column(name = "service_image_url_snapshot", length = 500)
    private String serviceImageUrlSnapshot;

    @Column(name = "unit_price_snapshot", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPriceSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_unit_snapshot", nullable = false, length = 32)
    private AddOnPricingUnit pricingUnitSnapshot;

    @Column(nullable = false)
    private Integer quantity;

    @Builder.Default
    @Column(name = "pricing_multiplier", nullable = false)
    private Integer pricingMultiplier = 1;

    @Column(name = "billable_quantity", nullable = false)
    private Integer billableQuantity;

    @Column(name = "total_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ReservationServiceStatus status;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "requested_at_utc", nullable = false)
    private Instant requestedAtUtc;

    @Column(name = "confirmed_at_utc")
    private Instant confirmedAtUtc;

    @Column(name = "fulfilled_at_utc")
    private Instant fulfilledAtUtc;

    @Column(name = "cancelled_at_utc")
    private Instant cancelledAtUtc;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_user_id")
    private User requestedByUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_updated_by_user_id")
    private User lastUpdatedByUser;
}
