package com.hotel.backend.pricing;

import com.hotel.backend.constant.PricingTransitionReason;
import com.hotel.backend.constant.StayClassification;
import com.hotel.backend.constant.StayPackage;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Pure, line-level result. Service, tax, discount, minibar, damage and manual
 * adjustments are intentionally outside the room-price cap and outside this
 * record.
 */
public record PricingBreakdown(
        long actualMinutes,
        StayClassification stayClassification,
        StayPackage appliedPackage,
        PricingTransitionReason transitionReason,
        int fullDays,
        int remainderMinutes,
        int packageCycles,
        int chargedExtraUnits,
        LocalDateTime packageIncludedCheckout,
        BigDecimal roomChargePerRoom,
        BigDecimal roomCharge,
        int extraGuestCount,
        BigDecimal extraGuestCharge,
        BigDecimal lineTotalBeforeServices,
        List<PricingCycleBreakdown> cycles) {

    public PricingBreakdown {
        if (actualMinutes < 1
                || fullDays < 0
                || remainderMinutes < 0
                || packageCycles < 1
                || chargedExtraUnits < 0
                || extraGuestCount < 0) {
            throw new IllegalArgumentException("invalid aggregate pricing values");
        }
        stayClassification =
                Objects.requireNonNull(stayClassification, "stayClassification");
        appliedPackage = Objects.requireNonNull(appliedPackage, "appliedPackage");
        transitionReason =
                Objects.requireNonNull(transitionReason, "transitionReason");
        packageIncludedCheckout =
                Objects.requireNonNull(
                        packageIncludedCheckout, "packageIncludedCheckout");
        roomChargePerRoom = requireNonNegative(roomChargePerRoom, "roomChargePerRoom");
        roomCharge = requireNonNegative(roomCharge, "roomCharge");
        extraGuestCharge =
                requireNonNegative(extraGuestCharge, "extraGuestCharge");
        lineTotalBeforeServices =
                requireNonNegative(lineTotalBeforeServices, "lineTotalBeforeServices");
        cycles = List.copyOf(Objects.requireNonNull(cycles, "cycles"));
        if (cycles.size() != packageCycles) {
            throw new IllegalArgumentException("packageCycles must match cycles size");
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
