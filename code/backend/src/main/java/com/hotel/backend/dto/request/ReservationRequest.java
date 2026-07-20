package com.hotel.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.util.Set;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationRequest {

    @NotNull
    private Long customerId;

    @NotNull
    private LocalDate checkIn;

    @NotNull
    private LocalDate checkOut;

    @Min(1)
    private Integer guestCount;

    private String note;

    @Valid
    @NotEmpty(message = "Phải chọn ít nhất một loại phòng")
    private Set<ReservationRoomTypeRequest> roomTypes;
}