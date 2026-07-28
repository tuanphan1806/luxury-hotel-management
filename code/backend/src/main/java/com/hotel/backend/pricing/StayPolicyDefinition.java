package com.hotel.backend.pricing;

import com.hotel.backend.constant.InventoryProtectionMode;

import java.time.LocalTime;
import java.util.Objects;

/**
 * Immutable operational policy used by pricing V2.
 *
 * <p>Turnover is carried here for the inventory caller, but is never added to a
 * chargeable duration by the pricing engine.</p>
 */
public record StayPolicyDefinition(
        int graceMinutes,
        LocalTime overnightStartTime,
        LocalTime overnightEarlyMorningEnd,
        int earlyMorningOvernightMinimumMinutes,
        LocalTime overnightHardCheckoutTime,
        int overnightMaximumMinutes,
        int dailyThresholdMinutes,
        int dailyDurationMinutes,
        int turnoverBufferMinutes,
        boolean remainderCycleStartsAtBoundary,
        InventoryProtectionMode inventoryProtectionMode) {

    public StayPolicyDefinition {
        if (graceMinutes < 0 || graceMinutes > 60) {
            throw new IllegalArgumentException("graceMinutes must be between 0 and 60");
        }
        overnightStartTime =
                Objects.requireNonNull(overnightStartTime, "overnightStartTime");
        overnightEarlyMorningEnd =
                Objects.requireNonNull(
                        overnightEarlyMorningEnd, "overnightEarlyMorningEnd");
        if (earlyMorningOvernightMinimumMinutes < 0
                || earlyMorningOvernightMinimumMinutes > dailyDurationMinutes) {
            throw new IllegalArgumentException(
                    "earlyMorningOvernightMinimumMinutes must be between 0 and dailyDurationMinutes");
        }
        overnightHardCheckoutTime =
                Objects.requireNonNull(
                        overnightHardCheckoutTime, "overnightHardCheckoutTime");
        if (overnightMaximumMinutes < 1
                || dailyThresholdMinutes < 1
                || dailyDurationMinutes < 60
                || dailyThresholdMinutes > dailyDurationMinutes
                || overnightMaximumMinutes > dailyDurationMinutes) {
            throw new IllegalArgumentException("invalid overnight/daily duration policy");
        }
        if (turnoverBufferMinutes < 0 || turnoverBufferMinutes > 1440) {
            throw new IllegalArgumentException(
                    "turnoverBufferMinutes must be between 0 and 1440");
        }
        inventoryProtectionMode =
                Objects.requireNonNull(inventoryProtectionMode, "inventoryProtectionMode");
    }
}
