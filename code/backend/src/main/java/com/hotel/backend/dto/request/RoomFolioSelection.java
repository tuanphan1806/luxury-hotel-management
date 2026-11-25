package com.hotel.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record RoomFolioSelection(@NotNull List<@NotNull @Valid Units> services,
                                 @PositiveOrZero long additionalFee, @PositiveOrZero Long useCredit) {
    public record Units(@NotNull @Positive Long orderId, @Positive int quantity) {}
}
