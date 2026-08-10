package com.hotel.backend.pricing;

import com.hotel.backend.constant.InventoryProtectionMode;
import com.hotel.backend.constant.StayClassification;
import com.hotel.backend.constant.StayPackage;
import com.hotel.backend.entity.StayPolicyVersion;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Shared non-financial aggregate rules for both quote display and reservation
 * commitment. Line-level breakdowns remain the financial source of truth.
 */
@Component
public class PricingQuoteAggregates {

    public LocalDateTime inventoryProtectedUntil(
            LocalDateTime plannedCheckout,
            List<PricingBreakdown> lines,
            StayPolicyVersion policy) {
        LocalDateTime entitlementEnd = plannedCheckout;
        if (policy.getInventoryProtectionMode()
                == InventoryProtectionMode.PACKAGE_ENTITLEMENT) {
            entitlementEnd = lines.stream()
                    .flatMap(line -> line.cycles().stream())
                    .map(cycle -> inventoryEntitlementEnd(cycle, policy))
                    .max(LocalDateTime::compareTo)
                    .filter(value -> value.isAfter(plannedCheckout))
                    .orElse(plannedCheckout);
        }
        return entitlementEnd.plusMinutes(policy.getTurnoverBufferMinutes());
    }

    /**
     * An overnight guest may arrive late and still use up to the configured
     * maximum duration, capped at the configured hard checkout boundary. Inventory is
     * therefore reserved through that hard boundary from the initial quote;
     * otherwise a later booking could make the already-sold overnight right
     * impossible to honour at check-in. This protection affects availability
     * only and never increases the room charge.
     */
    private LocalDateTime inventoryEntitlementEnd(
            PricingCycleBreakdown cycle,
            StayPolicyVersion policy) {
        if (cycle.appliedPackage() != StayPackage.OVERNIGHT) {
            return cycle.packageIncludedCheckout();
        }
        LocalDate operationalNightDate =
                cycle.billableStart().toLocalTime()
                                .isBefore(policy.getOvernightEarlyMorningEnd())
                        ? cycle.billableStart().toLocalDate().minusDays(1)
                        : cycle.billableStart().toLocalDate();
        return operationalNightDate.plusDays(1)
                .atTime(policy.getOvernightHardCheckoutTime());
    }

    public StayPackage displayPackage(List<PricingBreakdown> lines) {
        return lines.stream()
                .map(PricingBreakdown::appliedPackage)
                .max(Comparator.comparingInt(this::packageRank))
                .orElseThrow();
    }

    public StayClassification displayClassification(
            List<PricingBreakdown> lines) {
        return lines.stream()
                .map(PricingBreakdown::stayClassification)
                .max(Comparator.comparingInt(this::classificationRank))
                .orElseThrow();
    }

    /**
     * Every line in one reservation shares the same stay window and policy,
     * therefore the package-cycle count must also be identical. Keeping this
     * assertion in one place prevents add-on services from inventing a second,
     * incompatible definition of a billable night.
     */
    public int commonPackageCycles(List<PricingBreakdown> lines) {
        int packageCycles = lines.stream()
                .findFirst()
                .map(PricingBreakdown::packageCycles)
                .orElseThrow(() -> new IllegalArgumentException(
                        "At least one pricing line is required"));
        boolean inconsistent = lines.stream()
                .anyMatch(line -> line.packageCycles() != packageCycles);
        if (inconsistent) {
            throw new IllegalArgumentException(
                    "All reservation pricing lines must share package cycles");
        }
        return packageCycles;
    }

    private int packageRank(StayPackage value) {
        return switch (value) {
            case HOURLY -> 1;
            case OVERNIGHT -> 2;
            case DAILY -> 3;
        };
    }

    private int classificationRank(StayClassification value) {
        return switch (value) {
            case DAY_STAY -> 1;
            case NIGHT_STAY -> 2;
            case MULTI_DAY -> 3;
        };
    }
}
