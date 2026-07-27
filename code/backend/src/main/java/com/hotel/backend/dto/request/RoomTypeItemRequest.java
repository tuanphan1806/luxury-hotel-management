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
    @Max(value = 100, message = "Số lượng phòng vượt giới hạn cho phép")
    private Integer quantity;

    /**
     * Optional only for backward compatibility. Pricing V2 requires this value
     * for every selected room-type line.
     */
    @Min(value = 1, message = "Số khách của loại phòng phải ít nhất 1")
    @Max(value = 1000, message = "Số khách của loại phòng vượt giới hạn cho phép")
    private Integer lineGuestCount;
}
