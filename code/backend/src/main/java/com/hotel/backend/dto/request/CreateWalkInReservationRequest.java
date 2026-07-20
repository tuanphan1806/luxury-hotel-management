package com.hotel.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateWalkInReservationRequest {

    private Long customerProfileId;

    @Valid
    private CustomerProfileRequest customer;

    @NotNull(message = "Ngày check-out không được để trống")
    private LocalDateTime checkOut;

    @Min(value = 1, message = "Số khách phải ít nhất 1 người")
    private Integer guestCount;

    private String note;

    @NotEmpty(message = "Phải chọn ít nhất 1 loại phòng")
    @Valid
    private List<RoomTypeItemRequest> roomTypes;
}
