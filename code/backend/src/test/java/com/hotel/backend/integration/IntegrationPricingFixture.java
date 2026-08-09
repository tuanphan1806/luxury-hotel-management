package com.hotel.backend.integration;

import com.hotel.backend.constant.ExtraGuestBillingMode;
import com.hotel.backend.entity.RoomRateProfile;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.entity.StayPolicyVersion;
import com.hotel.backend.repository.RoomRateProfileRepository;
import com.hotel.backend.repository.RoomTypeRepository;
import com.hotel.backend.repository.StayPolicyVersionRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Creates integration-test room types through the same versioned pricing
 * aggregate used in production. Tests must never reintroduce the removed
 * {@code room_types.price} shortcut merely to make reservation fixtures pass.
 */
final class IntegrationPricingFixture {

    private IntegrationPricingFixture() {
    }

    static RoomType persist(
            RoomTypeRepository roomTypeRepository,
            RoomRateProfileRepository rateProfileRepository,
            StayPolicyVersionRepository stayPolicyVersionRepository,
            RoomType draft,
            int firstBlockMinutes,
            long firstBlockPrice,
            long extraHourPrice) {
        return persist(
                roomTypeRepository,
                rateProfileRepository,
                stayPolicyVersionRepository,
                draft,
                firstBlockMinutes,
                firstBlockPrice,
                extraHourPrice,
                // Lifecycle/concurrency suites use this overload to exercise
                // reservation behaviour rather than package classification.
                // Keep package prices neutral so the result cannot change just
                // because CI happens to run during the overnight window. Tests
                // that characterize pricing pass explicit overnight/daily
                // amounts through the overload below.
                firstBlockPrice,
                firstBlockPrice);
    }

    static RoomType persist(
            RoomTypeRepository roomTypeRepository,
            RoomRateProfileRepository rateProfileRepository,
            StayPolicyVersionRepository stayPolicyVersionRepository,
            RoomType draft,
            int firstBlockMinutes,
            long firstBlockPrice,
            long extraHourPrice,
            long overnightPrice,
            long dailyPrice) {
        String code = "IT_" + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 16)
                .toUpperCase();
        draft.setCode(code);
        draft.setActive(true);
        RoomType saved = roomTypeRepository.saveAndFlush(draft);

        Instant now = Instant.now();
        StayPolicyVersion policy = stayPolicyVersionRepository
                .findEffectiveByPolicyCode("DEFAULT_MOTEL_POLICY", now)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Integration test requires DEFAULT_MOTEL_POLICY seed"));

        rateProfileRepository.saveAndFlush(RoomRateProfile.builder()
                .roomType(saved)
                .stayPolicyVersion(policy)
                .profileVersion(1)
                .includedGuests(Math.max(1, Math.min(2, saved.getMaxGuests())))
                .firstBlockMinutes(firstBlockMinutes)
                .firstBlockPrice(BigDecimal.valueOf(firstBlockPrice))
                .extraUnitMinutes(60)
                .extraUnitPrice(BigDecimal.valueOf(extraHourPrice))
                .overnightPrice(BigDecimal.valueOf(overnightPrice))
                .dailyPrice(BigDecimal.valueOf(dailyPrice))
                .extraGuestPrice(BigDecimal.valueOf(50_000))
                .extraGuestBillingMode(ExtraGuestBillingMode.PER_PACKAGE_CYCLE)
                .effectiveFromUtc(now.minusSeconds(3600))
                .active(true)
                .createdAtUtc(now)
                .build());
        return saved;
    }
}
