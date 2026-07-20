package com.hotel.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class ResendVerificationRequest {
    @Email(message = "email invalid")
    @NotBlank
    private String email;
}
