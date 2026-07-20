package com.hotel.backend.dto.request;

import jakarta.validation.constraints.Min;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateReservationRequest {

    @Min(value = 1, message = "Số khách phải ít nhất 1 người")
    private Integer guestCount;

    private String note;
}