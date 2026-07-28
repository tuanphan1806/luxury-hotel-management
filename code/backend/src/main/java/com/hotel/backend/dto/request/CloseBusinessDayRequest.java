package com.hotel.backend.dto.request;

import jakarta.validation.constraints.Size;

public record CloseBusinessDayRequest(
        @Size(max = 1000, message = "Ghi chú tối đa 1000 ký tự")
        String note) {
}
