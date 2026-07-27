package com.hotel.backend.pricing;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One line-level pricing request. A reservation containing multiple room types
 * is calculated by aggregating one result per line.
 */
public record PricingRequest(
        LocalDateTime checkIn,
        LocalDateTime checkOut,
        int roomQuantity,
        int lineGuestCount) {

    public PricingRequest {
        checkIn = Objects.requireNonNull(checkIn, "checkIn");
        checkOut = Objects.requireNonNull(checkOut, "checkOut");
        if (!checkOut.isAfter(checkIn)) {
            throw new IllegalArgumentException("checkOut must be after checkIn");
        }
        if (roomQuantity < 1) {
            throw new IllegalArgumentException("roomQuantity must be positive");
        }
        if (lineGuestCount < 1) {
            throw new IllegalArgumentException("lineGuestCount must be positive");
        }
        if (lineGuestCount < roomQuantity) {
            throw new IllegalArgumentException(
                    "lineGuestCount must include at least one guest per room");
        }
    }
}
