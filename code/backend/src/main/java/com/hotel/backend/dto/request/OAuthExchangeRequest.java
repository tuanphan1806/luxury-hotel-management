package com.hotel.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OAuthExchangeRequest(
        @NotBlank
        @Size(max = 512)
        String ticket) {
}
