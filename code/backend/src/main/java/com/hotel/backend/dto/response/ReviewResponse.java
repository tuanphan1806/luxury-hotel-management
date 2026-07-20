package com.hotel.backend.dto.response;
 
import com.hotel.backend.entity.Review;
import lombok.*;
 
import java.time.LocalDateTime;
 
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewResponse {
 
    private Long id;
    private Long userId;
    private String userName;
    private String userImageUrl;
    private Long roomTypeId;
    private String roomTypeName;
    private String roomTypeNameEn;
    private Long reservationId;
    private String reservationCode;
    private Integer rating;
    private String comment;
    private LocalDateTime createdAt;
 
    public static ReviewResponse from(Review review) {
        return ReviewResponse.builder()
                .id(review.getId())
                .userId(review.getUser().getId())
                .userName(review.getUser().getFullName())
                .userImageUrl(review.getUser().getImageUrl())
                .roomTypeId(review.getRoomType().getId())
                .roomTypeName(review.getRoomType().getTypeName())
                .roomTypeNameEn(review.getRoomType().getTypeNameEn())
                .reservationId(review.getReservation().getId())
                .reservationCode(review.getReservation().getReservationCode())
                .rating(review.getRating())
                .comment(review.getComment())
                .createdAt(review.getCreatedAt())
                .build();
    }
}
