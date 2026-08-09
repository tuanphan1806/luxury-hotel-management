package com.hotel.backend.pricing;

import com.hotel.backend.entity.RoomRateProfile;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.entity.StayPolicyVersion;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class PricingDefinitionFactory {

    public RoomRateDefinition roomRate(RoomRateProfile profile) {
        Objects.requireNonNull(profile, "profile");
        RoomType roomType = Objects.requireNonNull(profile.getRoomType(), "roomType");
        return new RoomRateDefinition(
                roomType.getCode(),
                profile.getIncludedGuests(),
                roomType.getMaxGuests(),
                profile.getFirstBlockMinutes(),
                profile.getFirstBlockPrice(),
                profile.getExtraUnitMinutes(),
                profile.getExtraUnitPrice(),
                profile.getOvernightPrice(),
                profile.getDailyPrice(),
                profile.getExtraGuestPrice(),
                profile.getExtraGuestBillingMode());
    }

    public StayPolicyDefinition stayPolicy(StayPolicyVersion policy) {
        Objects.requireNonNull(policy, "policy");
        return new StayPolicyDefinition(
                policy.getGraceMinutes(),
                policy.getOvernightStartTime(),
                policy.getOvernightEarlyMorningEnd(),
                policy.getEarlyMorningOvernightMinimumMinutes(),
                policy.getOvernightRefundLockTime(),
                policy.getOvernightHardCheckoutTime(),
                policy.getOvernightMaximumMinutes(),
                policy.getDailyThresholdMinutes(),
                policy.getDailyDurationMinutes(),
                policy.getTurnoverBufferMinutes(),
                Boolean.TRUE.equals(policy.getRemainderCycleStartsAtBoundary()),
                policy.getInventoryProtectionMode());
    }
}
