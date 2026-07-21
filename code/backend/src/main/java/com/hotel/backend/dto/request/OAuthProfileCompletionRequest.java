package com.hotel.backend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OAuthProfileCompletionRequest(
        @NotBlank
        @Size(max = 512)
        String ticket,

        @NotBlank
        @Email
        @Size(max = 255)
        String email) {
}
