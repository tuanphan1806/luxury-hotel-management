package com.hotel.backend.dto.request;
 
import jakarta.validation.constraints.*;
import lombok.*;
 
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoomTypeItemRequest {
 
    @NotNull(message = "roomTypeId không được để trống")
    private Long roomTypeId;
 
    @NotNull(message = "Số lượng không được để trống")
    @Min(value = 1, message = "Số lượng phòng phải ít nhất 1")
    private Integer quantity;
}