package com.hotel.backend.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PricingQuoteRoomRequest {

    @NotNull(message = "Room type id không được để trống")
    private Long roomTypeId;

    @NotNull(message = "Số lượng phòng không được để trống")
    @Min(value = 1, message = "Số lượng phòng phải ít nhất 1")
    @Max(value = 100, message = "Số lượng phòng vượt giới hạn cho phép")
    private Integer quantity;

    @NotNull(message = "Số khách của loại phòng không được để trống")
    @Min(value = 1, message = "Số khách của loại phòng phải ít nhất 1")
    @Max(value = 1000, message = "Số khách của loại phòng vượt giới hạn cho phép")
    private Integer lineGuestCount;
}
