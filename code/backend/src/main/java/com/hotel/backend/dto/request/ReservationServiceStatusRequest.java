package com.hotel.backend.dto.request;

import com.hotel.backend.constant.ReservationServiceStatus;
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
public class ReservationServiceStatusRequest {

    @NotNull(message = "Trạng thái dịch vụ không được để trống")
    private ReservationServiceStatus status;

    @Size(max = 500, message = "Lý do hủy tối đa 500 ký tự")
    private String cancellationReason;
}
