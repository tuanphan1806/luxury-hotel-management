package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Instant;

@Data
public class SePayEventMatchRequest {
    @NotBlank(message = "paymentTransactionId không được để trống")
    private String paymentTransactionId;

    /** Required only when the provider timestamp could not be parsed automatically. */
    private Instant providerOccurredAtUtc;

    @Size(max = 500, message = "Ghi chú review không được quá 500 ký tự")
    private String note;
}
