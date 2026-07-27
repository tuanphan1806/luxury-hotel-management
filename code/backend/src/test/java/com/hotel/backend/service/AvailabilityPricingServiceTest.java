package com.hotel.backend.service;

import com.hotel.backend.config.PricingV2Properties;
import com.hotel.backend.constant.ExtraGuestBillingMode;
import com.hotel.backend.constant.InventoryProtectionMode;
import com.hotel.backend.constant.StayPackage;
import com.hotel.backend.entity.RoomRateProfile;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.entity.StayPolicyVersion;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.pricing.MotelPackagePricingEngine;
import com.hotel.backend.pricing.PricingDefinitionFactory;
import com.hotel.backend.repository.RoomRateProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvailabilityPricingServiceTest {

    @Mock
    private RoomRateProfileRepository rateProfileRepository;

    private PricingV2Properties properties;
    private AvailabilityPricingService service;
    private RoomType roomType;
    private RoomRateProfile rateProfile;

    @BeforeEach
    void setUp() {
        properties = new PricingV2Properties();
        properties.setEngineV2Enabled(true);
        service = new AvailabilityPricingService(
                properties,
                new MotelPackagePricingEngine(),
                new PricingDefinitionFactory(),
                rateProfileRepository);

        StayPolicyVersion policy = StayPolicyVersion.builder()
                .id(11L)
                .policyCode("DEFAULT_MOTEL_POLICY")
                .policyVersion(1)
                .graceMinutes(15)
                .overnightStartTime(LocalTime.of(20, 0))
                .overnightEarlyMorningEnd(LocalTime.of(6, 0))
                .overnightHardCheckoutTime(LocalTime.NOON)
                .overnightMaximumMinutes(720)
                .dailyThresholdMinutes(1200)
                .dailyDurationMinutes(1440)
                .turnoverBufferMinutes(30)
                .inventoryProtectionMode(
                        InventoryProtectionMode.PACKAGE_ENTITLEMENT)
                .effectiveFromUtc(Instant.parse("2026-01-01T00:00:00Z"))
                .active(true)
                .createdAtUtc(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        roomType = RoomType.builder()
                .code("STANDARD")
                .typeName("Phòng tiêu chuẩn")
                .maxGuests(2)
                .build();
        roomType.setId(1L);
        rateProfile = RoomRateProfile.builder()
                .id(21L)
                .roomType(roomType)
                .stayPolicyVersion(policy)
                .profileVersion(1)
                .includedGuests(1)
                .firstBlockMinutes(120)
                .firstBlockPrice(new BigDecimal("70000"))
                .extraUnitMinutes(60)
                .extraUnitPrice(new BigDecimal("20000"))
                .overnightPrice(new BigDecimal("170000"))
                .dailyPrice(new BigDecimal("300000"))
                .extraGuestPrice(new BigDecimal("50000"))
                .extraGuestBillingMode(
                        ExtraGuestBillingMode.PER_PACKAGE_CYCLE)
                .effectiveFromUtc(Instant.parse("2026-01-01T00:00:00Z"))
                .active(true)
                .createdAtUtc(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    @Test
    void exposesTheSameVersionedRateUsedByTheFinalQuote() {
        when(rateProfileRepository.findEffectiveByRoomTypeCode(
                eq("STANDARD"), any(Instant.class)))
                .thenReturn(List.of(rateProfile));

        AvailabilityPricingService.Estimate estimate = service.estimate(
                        roomType,
                        LocalDateTime.of(2026, 8, 1, 12, 0),
                        LocalDateTime.of(2026, 8, 2, 12, 15))
                .orElseThrow();

        assertEquals(120, estimate.firstBlockMinutes());
        assertEquals(0, new BigDecimal("70000")
                .compareTo(estimate.firstBlockPrice()));
        assertEquals(0, new BigDecimal("300000")
                .compareTo(estimate.estimatedPricePerRoom()));
        assertEquals(StayPackage.DAILY, estimate.estimatedPackage());
    }

    @Test
    void keepsLegacyCompatibilityOutsideThePricingV2Canary() {
        properties.setEngineV2RoomTypeCodes("DELUXE");

        assertTrue(service.estimate(
                roomType,
                LocalDateTime.of(2026, 8, 1, 12, 0),
                LocalDateTime.of(2026, 8, 1, 14, 0)).isEmpty());
        verifyNoInteractions(rateProfileRepository);
    }

    @Test
    void rejectsAmbiguousEffectiveRates() {
        when(rateProfileRepository.findEffectiveByRoomTypeCode(
                eq("STANDARD"), any(Instant.class)))
                .thenReturn(List.of(rateProfile, rateProfile));

        AppException exception = assertThrows(
                AppException.class,
                () -> service.estimate(
                        roomType,
                        LocalDateTime.of(2026, 8, 1, 12, 0),
                        LocalDateTime.of(2026, 8, 1, 14, 0)));

        assertEquals(ErrorCode.PRICING_PROFILE_NOT_FOUND, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("chồng thời gian"));
    }
}
