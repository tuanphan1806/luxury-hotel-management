package com.hotel.backend.dto.request;
 
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;
 
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateReviewRequest {
 
    @NotNull(message = "reservationId không được để trống")
    private Long reservationId;

    @NotNull(message = "roomTypeId không được để trống")
    private Long roomTypeId;
 
    @NotNull(message = "Điểm đánh giá không được để trống")
    @Min(value = 1, message = "Điểm đánh giá tối thiểu là 1")
    @Max(value = 5, message = "Điểm đánh giá tối đa là 5")
    private Integer rating;
 
    private String comment;
}
