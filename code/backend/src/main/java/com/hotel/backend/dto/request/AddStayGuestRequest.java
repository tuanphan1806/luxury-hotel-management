package com.hotel.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Registers one additional guest in a physical room during an active stay.
 * Pricing and capacity are resolved from the reservation's immutable rate
 * snapshots, never from mutable catalogue defaults.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddStayGuestRequest {

    @NotNull(message = "Phòng lưu trú không được để trống")
    private Long reservationRoomId;

    @Valid
    @NotNull(message = "Thông tin khách không được để trống")
    private GuestRequest guest;
}
