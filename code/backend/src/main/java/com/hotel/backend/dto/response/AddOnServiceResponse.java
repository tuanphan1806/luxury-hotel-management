package com.hotel.backend.dto.response;

import com.hotel.backend.constant.AddOnPricingUnit;
import com.hotel.backend.constant.AddOnServiceCategory;
import com.hotel.backend.entity.AddOnService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddOnServiceResponse {
    private Long id;
    private String code;
    private String name;
    private String nameEn;
    private String description;
    private String descriptionEn;
    private String imageUrl;
    private AddOnServiceCategory category;
    private BigDecimal price;
    private AddOnPricingUnit pricingUnit;
    private boolean bookingEnabled;
    private boolean inStayEnabled;
    private boolean active;
    private int sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static AddOnServiceResponse from(AddOnService service) {
        return AddOnServiceResponse.builder()
                .id(service.getId())
                .code(service.getCode())
                .name(service.getName())
                .nameEn(service.getNameEn())
                .description(service.getDescription())
                .descriptionEn(service.getDescriptionEn())
                .imageUrl(service.getImageUrl())
                .category(service.getCategory())
                .price(service.getPrice())
                .pricingUnit(service.getPricingUnit())
                .bookingEnabled(service.isBookingEnabled())
                .inStayEnabled(service.isInStayEnabled())
                .active(service.isActive())
                .sortOrder(service.getSortOrder())
                .createdAt(service.getCreatedAt())
                .updatedAt(service.getUpdatedAt())
                .build();
    }
}
