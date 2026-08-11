package com.hotel.backend.dto.request;
 
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.*;
 
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateReviewRequest {
 
    @Min(value = 1, message = "Điểm đánh giá tối thiểu là 1")
    @Max(value = 5, message = "Điểm đánh giá tối đa là 5")
    private Integer rating;
 
    @Size(max = 1000, message = "Nhận xét không được vượt quá 1000 ký tự")
    private String comment;
}
