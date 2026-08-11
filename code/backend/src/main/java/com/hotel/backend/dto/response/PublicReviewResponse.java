package com.hotel.backend.dto.response;

import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.entity.Review;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Public projection for room-type reviews. Booking and internal user identifiers
 * are intentionally excluded from the anonymous endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicReviewResponse {

    private Long id;
    private String userName;
    private String userImageUrl;
    private Long roomTypeId;
    private String roomTypeName;
    private String roomTypeNameEn;
    private Integer rating;
    private String comment;
    private LocalDateTime createdAt;
    private boolean verifiedStay;

    public static PublicReviewResponse from(Review review) {
        return PublicReviewResponse.builder()
                .id(review.getId())
                .userName(review.getUser().getFullName())
                .userImageUrl(review.getUser().getImageUrl())
                .roomTypeId(review.getRoomType().getId())
                .roomTypeName(review.getRoomType().getTypeName())
                .roomTypeNameEn(review.getRoomType().getTypeNameEn())
                .rating(review.getRating())
                .comment(review.getComment())
                .createdAt(review.getCreatedAt())
                .verifiedStay(review.getReservation() != null
                        && review.getReservation().getStatus()
                        == ReservationStatus.CHECKED_OUT)
                .build();
    }
}
