package com.hotel.backend.dto.response;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;


@Builder
@Data
public class RoomTypeResponse {
    private Long id;
    private String code;
    private String typeName;
    private String typeNameEn;
    private String description;
    private String descriptionEn;
    private BigDecimal price;
    /**
     * Read-only public tariff summary. The legacy price field remains for
     * compatibility; package-aware clients should use these fields whenever
     * packagePricingEnabled and pricingAvailable are both true.
     */
    private Boolean packagePricingEnabled;
    private Boolean pricingAvailable;
    private Integer includedGuests;
    private Integer firstBlockMinutes;
    private BigDecimal firstBlockPrice;
    private Integer extraUnitMinutes;
    private BigDecimal extraUnitPrice;
    private BigDecimal overnightPrice;
    private BigDecimal dailyPrice;
    private BigDecimal extraGuestPrice;
    private Integer maxGuests;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String imageUrl;
    private List<String> imageUrls;
    private List<FacilityResponse.Summary> facilities;
    private Double averageRating;
    private Long totalReviews;

}
