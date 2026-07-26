package com.hotel.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
public class ServiceOrderRequest {

    @NotNull(message = "Dịch vụ không được để trống")
    private Long serviceId;

    @Min(value = 1, message = "Số lượng dịch vụ phải ít nhất là 1")
    @Max(value = 99, message = "Số lượng dịch vụ tối đa là 99")
    private Integer quantity;

    @Size(max = 1000, message = "Ghi chú dịch vụ tối đa 1000 ký tự")
    private String notes;
}
