package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomTypeStatusRequest {

    @NotNull(message = "Trạng thái hoạt động không được để trống")
    private Boolean active;

    @Size(max = 500, message = "Lý do tối đa 500 ký tự")
    private String reason;
}
