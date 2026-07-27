package com.hotel.backend.pricing;

import com.hotel.backend.constant.PricingTransitionReason;
import com.hotel.backend.constant.StayClassification;
import com.hotel.backend.constant.StayPackage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pricing algorithm V2 for hourly, overnight and rolling 24-hour stays.
 *
 * <p>The class is stateless and deterministic. It never reads current database
 * state, never trusts a frontend total, and never includes turnover time in
 * chargeable stay duration.</p>
 */
@Component
public final class MotelPackagePricingEngine implements PricingEngine {

    @Override
    public PricingBreakdown calculate(
            PricingRequest request,
            RoomRateDefinition rate,
            StayPolicyDefinition policy) {
        validateCapacity(request, rate);

        long actualMinutes = ceilingMinutes(request.checkIn(), request.checkOut());
        int fullDays = Math.toIntExact(actualMinutes / policy.dailyDurationMinutes());
        int remainderMinutes =
                Math.toIntExact(actualMinutes % policy.dailyDurationMinutes());

        List<PricingCycleBreakdown> cycles = new ArrayList<>();
        LocalDateTime cycleStart = request.checkIn();
        for (int index = 0; index < fullDays; index++) {
            LocalDateTime cycleEnd =
                    cycleStart.plusMinutes(policy.dailyDurationMinutes());
            cycles.add(dailyCycle(
                    cycles.size() + 1,
                    cycleStart,
                    cycleEnd,
                    policy.dailyDurationMinutes(),
                    rate,
                    policy,
                    PricingTransitionReason.DAILY_DURATION,
                    0));
            cycleStart = cycleEnd;
        }

        if (fullDays == 0) {
            cycles.add(calculateSingleCycle(
                    1,
                    request.checkIn(),
                    request.checkOut(),
                    actualMinutes,
                    rate,
                    policy));
        } else if (remainderMinutes > policy.graceMinutes()) {
            int waivedBoundaryGrace = policy.graceMinutes();
            LocalDateTime billableRemainderStart =
                    cycleStart.plusMinutes(waivedBoundaryGrace);
            long billableRemainderMinutes =
                    remainderMinutes - (long) waivedBoundaryGrace;
            cycles.add(calculateSingleCycle(
                    cycles.size() + 1,
                    billableRemainderStart,
                    request.checkOut(),
                    billableRemainderMinutes,
                    rate,
                    policy));
        }

        BigDecimal roomChargePerRoom = cycles.stream()
                .map(PricingCycleBreakdown::roomChargePerRoom)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal roomCharge =
                roomChargePerRoom.multiply(BigDecimal.valueOf(request.roomQuantity()));

        int extraGuestCount = Math.max(
                request.lineGuestCount()
                        - rate.includedGuests() * request.roomQuantity(),
                0);
        BigDecimal extraGuestCharge = rate.extraGuestPrice()
                .multiply(BigDecimal.valueOf(extraGuestCount))
                .multiply(BigDecimal.valueOf(cycles.size()));

        StayPackage appliedPackage = cycles.stream()
                .map(PricingCycleBreakdown::appliedPackage)
                .max(Comparator.comparingInt(this::packageRank))
                .orElseThrow();
        PricingTransitionReason transitionReason =
                aggregateTransitionReason(cycles, appliedPackage);
        int chargedExtraUnits = cycles.stream()
                .mapToInt(PricingCycleBreakdown::chargedExtraUnits)
                .sum();
        LocalDateTime packageIncludedCheckout = cycles.stream()
                .map(PricingCycleBreakdown::packageIncludedCheckout)
                .max(LocalDateTime::compareTo)
                .orElseThrow();

        return new PricingBreakdown(
                actualMinutes,
                classifyStay(request.checkIn(), request.checkOut(), actualMinutes, policy),
                appliedPackage,
                transitionReason,
                fullDays,
                remainderMinutes,
                cycles.size(),
                chargedExtraUnits,
                packageIncludedCheckout,
                roomChargePerRoom,
                roomCharge,
                extraGuestCount,
                extraGuestCharge,
                roomCharge.add(extraGuestCharge),
                cycles);
    }

    private PricingCycleBreakdown calculateSingleCycle(
            int sequence,
            LocalDateTime billableStart,
            LocalDateTime plannedSegmentEnd,
            long billableMinutes,
            RoomRateDefinition rate,
            StayPolicyDefinition policy) {
        if (billableMinutes >= policy.dailyThresholdMinutes()) {
            return dailyCycle(
                    sequence,
                    billableStart,
                    plannedSegmentEnd,
                    billableMinutes,
                    rate,
                    policy,
                    PricingTransitionReason.DAILY_DURATION,
                    0);
        }

        if (usesOvernightWindow(billableStart, plannedSegmentEnd, policy)) {
            return overnightCycle(
                    sequence,
                    billableStart,
                    plannedSegmentEnd,
                    billableMinutes,
                    rate,
                    policy);
        }

        return hourlyCycle(
                sequence,
                billableStart,
                plannedSegmentEnd,
                billableMinutes,
                rate,
                policy);
    }

    private PricingCycleBreakdown hourlyCycle(
            int sequence,
            LocalDateTime start,
            LocalDateTime end,
            long minutes,
            RoomRateDefinition rate,
            StayPolicyDefinition policy) {
        long chargeableExtraMinutes = Math.max(
                minutes - rate.firstBlockMinutes() - policy.graceMinutes(),
                0);
        int extraUnits = ceilingUnits(
                chargeableExtraMinutes, rate.extraUnitMinutes());
        BigDecimal rawRoomCharge = rate.firstBlockPrice().add(
                rate.extraUnitPrice().multiply(BigDecimal.valueOf(extraUnits)));

        if (rawRoomCharge.compareTo(rate.dailyPrice()) >= 0) {
            return dailyCycle(
                    sequence,
                    start,
                    end,
                    minutes,
                    rate,
                    policy,
                    PricingTransitionReason.PRICE_CAP,
                    extraUnits);
        }

        LocalDateTime entitlement = start.plusMinutes(
                rate.firstBlockMinutes()
                        + (long) extraUnits * rate.extraUnitMinutes());
        return new PricingCycleBreakdown(
                sequence,
                StayPackage.HOURLY,
                PricingTransitionReason.HOURLY_WINDOW,
                start,
                end,
                entitlement,
                minutes,
                extraUnits,
                rawRoomCharge);
    }

    private PricingCycleBreakdown overnightCycle(
            int sequence,
            LocalDateTime start,
            LocalDateTime end,
            long minutes,
            RoomRateDefinition rate,
            StayPolicyDefinition policy) {
        LocalDate operationalNightDate = operationalNightDate(start, policy);
        LocalDateTime nominalStart =
                operationalNightDate.atTime(policy.overnightStartTime());
        LocalDateTime nominalEnd =
                nominalStart.plusMinutes(policy.overnightMaximumMinutes());
        LocalDateTime hardCheckout = operationalNightDate
                .plusDays(1)
                .atTime(policy.overnightHardCheckoutTime());
        LocalDateTime arrivalEntitlement =
                start.plusMinutes(policy.overnightMaximumMinutes());
        LocalDateTime includedCheckout =
                earlierOf(laterOf(nominalEnd, arrivalEntitlement), hardCheckout);

        long earlyMinutes = start.isBefore(nominalStart)
                ? Math.max(
                        Duration.between(start, nominalStart).toMinutes()
                                - policy.graceMinutes(),
                        0)
                : 0;
        int earlyUnits = ceilingUnits(earlyMinutes, rate.extraUnitMinutes());

        long lateMinutes = end.isAfter(includedCheckout)
                ? Math.max(
                        Duration.between(includedCheckout, end).toMinutes()
                                - policy.graceMinutes(),
                        0)
                : 0;
        int lateUnits = ceilingUnits(lateMinutes, rate.extraUnitMinutes());
        int chargedExtraUnits = earlyUnits + lateUnits;
        BigDecimal rawRoomCharge = rate.overnightPrice().add(
                rate.extraUnitPrice()
                        .multiply(BigDecimal.valueOf(chargedExtraUnits)));

        if (rawRoomCharge.compareTo(rate.dailyPrice()) >= 0) {
            return dailyCycle(
                    sequence,
                    start,
                    end,
                    minutes,
                    rate,
                    policy,
                    PricingTransitionReason.PRICE_CAP,
                    chargedExtraUnits);
        }

        return new PricingCycleBreakdown(
                sequence,
                StayPackage.OVERNIGHT,
                PricingTransitionReason.OVERNIGHT_WINDOW,
                start,
                end,
                includedCheckout,
                minutes,
                chargedExtraUnits,
                rawRoomCharge);
    }

    private PricingCycleBreakdown dailyCycle(
            int sequence,
            LocalDateTime start,
            LocalDateTime end,
            long minutes,
            RoomRateDefinition rate,
            StayPolicyDefinition policy,
            PricingTransitionReason reason,
            int chargedExtraUnits) {
        return new PricingCycleBreakdown(
                sequence,
                StayPackage.DAILY,
                reason,
                start,
                end,
                start.plusMinutes(policy.dailyDurationMinutes()),
                minutes,
                chargedExtraUnits,
                rate.dailyPrice());
    }

    private StayClassification classifyStay(
            LocalDateTime checkIn,
            LocalDateTime checkOut,
            long actualMinutes,
            StayPolicyDefinition policy) {
        if (actualMinutes > policy.dailyDurationMinutes()) {
            return StayClassification.MULTI_DAY;
        }
        if (usesOvernightWindow(checkIn, checkOut, policy)) {
            return StayClassification.NIGHT_STAY;
        }
        return StayClassification.DAY_STAY;
    }

    private boolean usesOvernightWindow(
            LocalDateTime start,
            LocalDateTime end,
            StayPolicyDefinition policy) {
        boolean crossesMidnight =
                end.toLocalDate().isAfter(start.toLocalDate());
        boolean earlyMorningArrival =
                start.toLocalTime().isBefore(policy.overnightEarlyMorningEnd());
        return crossesMidnight || earlyMorningArrival;
    }

    private LocalDate operationalNightDate(
            LocalDateTime start,
            StayPolicyDefinition policy) {
        if (start.toLocalTime().isBefore(policy.overnightEarlyMorningEnd())) {
            return start.toLocalDate().minusDays(1);
        }
        return start.toLocalDate();
    }

    private PricingTransitionReason aggregateTransitionReason(
            List<PricingCycleBreakdown> cycles,
            StayPackage appliedPackage) {
        if (cycles.stream().anyMatch(cycle ->
                cycle.transitionReason() == PricingTransitionReason.DAILY_DURATION)) {
            return PricingTransitionReason.DAILY_DURATION;
        }
        if (cycles.stream().anyMatch(cycle ->
                cycle.transitionReason() == PricingTransitionReason.PRICE_CAP)) {
            return PricingTransitionReason.PRICE_CAP;
        }
        if (appliedPackage == StayPackage.OVERNIGHT) {
            return PricingTransitionReason.OVERNIGHT_WINDOW;
        }
        return PricingTransitionReason.HOURLY_WINDOW;
    }

    private void validateCapacity(
            PricingRequest request,
            RoomRateDefinition rate) {
        long maximumGuests =
                (long) rate.maxGuests() * request.roomQuantity();
        if (request.lineGuestCount() > maximumGuests) {
            throw new IllegalArgumentException(
                    "lineGuestCount exceeds room type capacity for selected quantity");
        }
    }

    private long ceilingMinutes(
            LocalDateTime start,
            LocalDateTime end) {
        Duration duration = Duration.between(start, end);
        long seconds = duration.getSeconds();
        int nanos = duration.getNano();
        long minutes = Math.floorDiv(seconds, 60);
        if (Math.floorMod(seconds, 60) != 0 || nanos > 0) {
            minutes++;
        }
        return Math.max(1L, minutes);
    }

    private int ceilingUnits(long minutes, int unitMinutes) {
        if (minutes <= 0) {
            return 0;
        }
        return Math.toIntExact((minutes + unitMinutes - 1L) / unitMinutes);
    }

    private int packageRank(StayPackage stayPackage) {
        return switch (stayPackage) {
            case HOURLY -> 1;
            case OVERNIGHT -> 2;
            case DAILY -> 3;
        };
    }

    private LocalDateTime earlierOf(
            LocalDateTime first,
            LocalDateTime second) {
        return first.isBefore(second) ? first : second;
    }

    private LocalDateTime laterOf(
            LocalDateTime first,
            LocalDateTime second) {
        return first.isAfter(second) ? first : second;
    }
}
