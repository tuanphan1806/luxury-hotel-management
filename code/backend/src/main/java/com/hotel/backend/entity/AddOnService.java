package com.hotel.backend.entity;

import com.hotel.backend.constant.AddOnPricingUnit;
import com.hotel.backend.constant.AddOnServiceCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "service_catalog", indexes = {
        @Index(name = "idx_service_catalog_active_sort", columnList = "is_active,sort_order,id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddOnService extends AbstractEntity<Long> {

    @Version
    @Builder.Default
    @Column(nullable = false)
    private Long version = 0L;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "name_en", length = 255)
    private String nameEn;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "description_en", columnDefinition = "TEXT")
    private String descriptionEn;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AddOnServiceCategory category;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_unit", nullable = false, length = 32)
    private AddOnPricingUnit pricingUnit;

    @Builder.Default
    @Column(name = "booking_enabled", nullable = false)
    private boolean bookingEnabled = true;

    @Builder.Default
    @Column(name = "in_stay_enabled", nullable = false)
    private boolean inStayEnabled = true;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;
}
