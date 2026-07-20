package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class VerifyEmailRequest {
    @NotBlank
    private String secretCode;
}
