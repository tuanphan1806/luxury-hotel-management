package com.hotel.backend.pricing;

public interface PricingEngine {

    PricingBreakdown calculate(
            PricingRequest request,
            RoomRateDefinition rate,
            StayPolicyDefinition policy);
}
