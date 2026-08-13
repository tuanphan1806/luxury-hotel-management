package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoveStayGuestRequest {

    @NotNull(message = "Phòng đích không được để trống")
    private Long targetReservationRoomId;

    @NotBlank(message = "Lý do chuyển phòng không được để trống")
    @Size(min = 5, max = 500,
            message = "Lý do chuyển phòng phải từ 5 đến 500 ký tự")
    private String reason;
}
