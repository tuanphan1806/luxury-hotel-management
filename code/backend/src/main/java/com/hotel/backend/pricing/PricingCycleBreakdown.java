package com.hotel.backend.pricing;

import com.hotel.backend.constant.PricingTransitionReason;
import com.hotel.backend.constant.StayPackage;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Explainable charge for one package cycle within a line-level calculation.
 */
public record PricingCycleBreakdown(
        int sequence,
        StayPackage appliedPackage,
        PricingTransitionReason transitionReason,
        LocalDateTime billableStart,
        LocalDateTime plannedSegmentEnd,
        LocalDateTime packageIncludedCheckout,
        long billableMinutes,
        int chargedExtraUnits,
        BigDecimal roomChargePerRoom) {

    public PricingCycleBreakdown {
        if (sequence < 1 || billableMinutes < 1 || chargedExtraUnits < 0) {
            throw new IllegalArgumentException("invalid pricing cycle values");
        }
        appliedPackage = Objects.requireNonNull(appliedPackage, "appliedPackage");
        transitionReason =
                Objects.requireNonNull(transitionReason, "transitionReason");
        billableStart = Objects.requireNonNull(billableStart, "billableStart");
        plannedSegmentEnd =
                Objects.requireNonNull(plannedSegmentEnd, "plannedSegmentEnd");
        packageIncludedCheckout =
                Objects.requireNonNull(
                        packageIncludedCheckout, "packageIncludedCheckout");
        roomChargePerRoom =
                Objects.requireNonNull(roomChargePerRoom, "roomChargePerRoom");
        if (roomChargePerRoom.signum() < 0) {
            throw new IllegalArgumentException("roomChargePerRoom must not be negative");
        }
    }
}
