package com.hotel.backend.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationRoomTypeRequest {

    @NotNull(message = "Room type id không được để trống")
    private Long roomTypeId;

    @NotNull(message = "Số lượng phòng không được để trống")
    @Min(value = 1, message = "Số lượng phòng phải lớn hơn 0")
    private Integer quantity;
}