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
    private String typeName;
    private String typeNameEn;
    private String description;
    private String descriptionEn;
    private BigDecimal price;
    private Integer maxGuests;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String imageUrl;
    private List<FacilityResponse.Summary> facilities;
    private Double averageRating;
    private Long totalReviews;

}
