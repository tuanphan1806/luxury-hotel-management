package com.hotel.backend.dto.response;
 
import lombok.*;
 
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomTypeRatingResponse {
 
    private Long roomTypeId;
    private Double averageRating;
    private long totalReviews;
}