package com.hotel.backend.dto.request;

import jakarta.validation.constraints.Size;

public record WorkShiftRegistrationReviewRequest(
        @Size(max = 500, message = "Lý do tối đa 500 ký tự")
        String reason) {
}
