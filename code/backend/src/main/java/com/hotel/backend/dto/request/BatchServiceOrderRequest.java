package com.hotel.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchServiceOrderRequest {

    @NotEmpty(message = "Phải chọn ít nhất một dịch vụ")
    @Size(max = 20, message = "Mỗi lần chỉ được thêm tối đa 20 dịch vụ")
    private List<@NotNull(message = "Dịch vụ không được để trống")
            @Valid ServiceOrderRequest> services;
}
