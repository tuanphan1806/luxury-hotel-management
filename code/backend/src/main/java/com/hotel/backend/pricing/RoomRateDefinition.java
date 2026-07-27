package com.hotel.backend.pricing;

import com.hotel.backend.constant.ExtraGuestBillingMode;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable input used by the pure pricing engine.
 *
 * <p>This is deliberately separated from the JPA entity so calculations do not
 * depend on an open persistence context and historical snapshots can be replayed
 * with exactly the rates that were committed.</p>
 */
public record RoomRateDefinition(
        String roomTypeCode,
        int includedGuests,
        int maxGuests,
        int firstBlockMinutes,
        BigDecimal firstBlockPrice,
        int extraUnitMinutes,
        BigDecimal extraUnitPrice,
        BigDecimal overnightPrice,
        BigDecimal dailyPrice,
        BigDecimal extraGuestPrice,
        ExtraGuestBillingMode extraGuestBillingMode) {

    public RoomRateDefinition {
        if (roomTypeCode == null || roomTypeCode.isBlank()) {
            throw new IllegalArgumentException("roomTypeCode must not be blank");
        }
        if (includedGuests < 1 || maxGuests < includedGuests) {
            throw new IllegalArgumentException(
                    "guest capacity must satisfy 1 <= includedGuests <= maxGuests");
        }
        if (firstBlockMinutes < 1 || extraUnitMinutes < 1) {
            throw new IllegalArgumentException("pricing time units must be positive");
        }

        firstBlockPrice = requireNonNegative(firstBlockPrice, "firstBlockPrice");
        extraUnitPrice = requireNonNegative(extraUnitPrice, "extraUnitPrice");
        overnightPrice = requireNonNegative(overnightPrice, "overnightPrice");
        dailyPrice = requireNonNegative(dailyPrice, "dailyPrice");
        extraGuestPrice = requireNonNegative(extraGuestPrice, "extraGuestPrice");
        extraGuestBillingMode =
                Objects.requireNonNull(extraGuestBillingMode, "extraGuestBillingMode");

        if (extraGuestBillingMode != ExtraGuestBillingMode.PER_PACKAGE_CYCLE) {
            throw new IllegalArgumentException(
                    "Only PER_PACKAGE_CYCLE is supported by pricing algorithm V2");
        }
        if (overnightPrice.compareTo(firstBlockPrice) < 0
                || dailyPrice.compareTo(overnightPrice) < 0) {
            throw new IllegalArgumentException(
                    "Rates must satisfy firstBlockPrice <= overnightPrice <= dailyPrice");
        }
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return value;
    }
}
