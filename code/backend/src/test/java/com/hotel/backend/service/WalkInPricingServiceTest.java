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
import com.hotel.backend.pricing.PricingQuoteAggregates;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalkInPricingServiceTest {

    @Mock
    private RoomRateProfileRepository rateProfileRepository;

    private PricingV2Properties properties;
    private WalkInPricingService service;
    private StayPolicyVersion policy;
    private RoomType standard;
    private RoomType deluxe;

    @BeforeEach
    void setUp() {
        properties = new PricingV2Properties();
        properties.setEngineV2Enabled(true);

        service = new WalkInPricingService(
                properties,
                new MotelPackagePricingEngine(),
                new PricingDefinitionFactory(),
                new PricingQuoteAggregates(),
                rateProfileRepository,
                new StayWindowValidationService(properties));

        policy = StayPolicyVersion.builder()
                .id(11L)
                .policyCode("DEFAULT_MOTEL_POLICY")
                .policyVersion(1)
                .graceMinutes(15)
                .overnightStartTime(LocalTime.of(20, 0))
                .overnightEarlyMorningEnd(LocalTime.of(8, 0))
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
        standard = roomType(1L, "STANDARD", "Phòng đơn", 2);
        deluxe = roomType(2L, "DELUXE", "Phòng đôi", 3);
    }

    @Test
    void allocatesUndetailedGuestsToIncludedCapacityBeforeChargingExtraGuest() {
        RoomRateProfile standardRate = rate(
                21L, standard, 1, "70000", "20000", "170000", "300000");
        RoomRateProfile deluxeRate = rate(
                22L, deluxe, 2, "100000", "25000", "220000", "400000");
        when(rateProfileRepository.findEffectiveByRoomTypeCodeForUpdate(
                eq("STANDARD"), any(Instant.class)))
                .thenReturn(List.of(standardRate));
        when(rateProfileRepository.findEffectiveByRoomTypeCodeForUpdate(
                eq("DELUXE"), any(Instant.class)))
                .thenReturn(List.of(deluxeRate));

        WalkInPricingService.Calculation calculation =
                service.calculateIfEligible(
                                LocalDateTime.of(2026, 8, 1, 10, 0),
                                LocalDateTime.of(2026, 8, 1, 12, 0),
                                3,
                                List.of(
                                        new WalkInPricingService.LineInput(
                                                standard, 1, null, 1),
                                        new WalkInPricingService.LineInput(
                                                deluxe, 1, null, 1)))
                        .orElseThrow();

        assertEquals(1, line(calculation, "STANDARD").lineGuestCount());
        assertEquals(2, line(calculation, "DELUXE").lineGuestCount());
        assertMoney("170000", calculation.roomCharge());
        assertMoney("0", calculation.extraGuestCharge());
        assertMoney("170000", calculation.totalBeforeServices());
        assertEquals(StayPackage.HOURLY, calculation.displayPackage());
    }

    @Test
    void allocatesOverflowWithinPhysicalCapacityAndChargesTheCorrectLine() {
        RoomRateProfile standardRate = rate(
                21L, standard, 1, "70000", "20000", "170000", "300000");
        RoomRateProfile deluxeRate = rate(
                22L, deluxe, 2, "100000", "25000", "220000", "400000");
        when(rateProfileRepository.findEffectiveByRoomTypeCodeForUpdate(
                eq("STANDARD"), any(Instant.class)))
                .thenReturn(List.of(standardRate));
        when(rateProfileRepository.findEffectiveByRoomTypeCodeForUpdate(
                eq("DELUXE"), any(Instant.class)))
                .thenReturn(List.of(deluxeRate));

        WalkInPricingService.Calculation calculation =
                service.calculateIfEligible(
                                LocalDateTime.of(2026, 8, 1, 10, 0),
                                LocalDateTime.of(2026, 8, 1, 12, 0),
                                4,
                                List.of(
                                        new WalkInPricingService.LineInput(
                                                standard, 1, null, 1),
                                        new WalkInPricingService.LineInput(
                                                deluxe, 1, null, 1)))
                        .orElseThrow();

        WalkInPricingService.CalculatedLine standardLine =
                line(calculation, "STANDARD");
        assertEquals(2, standardLine.lineGuestCount());
        assertEquals(1, standardLine.breakdown().extraGuestCount());
        assertEquals(2, line(calculation, "DELUXE").lineGuestCount());
        assertMoney("170000", calculation.roomCharge());
        assertMoney("50000", calculation.extraGuestCharge());
        assertMoney("220000", calculation.totalBeforeServices());
    }

    @Test
    void allCanonicalRatesAndMaximumGuestsProduceTheExactWalkInTotal() {
        RoomType executive = roomType(3L, "EXECUTIVE", "Phòng Executive", 3);
        RoomType suite = roomType(4L, "SUITE", "Phòng Suite", 4);
        RoomType family = roomType(5L, "FAMILY", "Phòng gia đình", 6);
        RoomType presidential = roomType(
                6L, "PRESIDENTIAL", "Phòng Tổng thống", 6);

        List<RoomRateProfile> rates = List.of(
                rate(21L, standard, 1, "70000", "20000", "170000", "300000"),
                rate(22L, deluxe, 2, "100000", "25000", "220000", "400000"),
                rate(23L, executive, 2, "120000", "30000", "270000", "480000"),
                rate(24L, suite, 2, "150000", "35000", "350000", "600000"),
                rate(25L, family, 4, "130000", "30000", "330000", "550000"),
                rate(26L, presidential, 4, "200000", "50000", "450000", "850000"));
        for (RoomRateProfile rate : rates) {
            when(rateProfileRepository.findEffectiveByRoomTypeCodeForUpdate(
                    eq(rate.getRoomType().getCode()), any(Instant.class)))
                    .thenReturn(List.of(rate));
        }

        WalkInPricingService.Calculation calculation =
                service.calculateIfEligible(
                                LocalDateTime.of(2026, 8, 1, 20, 0),
                                LocalDateTime.of(2026, 8, 4, 4, 0),
                                24,
                                List.of(
                                        new WalkInPricingService.LineInput(
                                                standard, 1, 2, 2),
                                        new WalkInPricingService.LineInput(
                                                deluxe, 1, 3, 3),
                                        new WalkInPricingService.LineInput(
                                                executive, 1, 3, 3),
                                        new WalkInPricingService.LineInput(
                                                suite, 1, 4, 4),
                                        new WalkInPricingService.LineInput(
                                                family, 1, 6, 6),
                                        new WalkInPricingService.LineInput(
                                                presidential, 1, 6, 6)))
                        .orElseThrow();

        assertMoney("8150000", calculation.roomCharge());
        assertMoney("1350000", calculation.extraGuestCharge());
        assertMoney("9500000", calculation.totalBeforeServices());
        assertEquals(3, calculation.packageCycles());
        assertEquals(6, calculation.lines().size());
    }

    @Test
    void returnsCompatibilityBoundaryWhenAnySelectedRoomTypeIsOutsideCanary() {
        properties.setEngineV2RoomTypeCodes("STANDARD");

        Optional<WalkInPricingService.Calculation> calculation =
                service.calculateIfEligible(
                        LocalDateTime.of(2026, 8, 1, 10, 0),
                        LocalDateTime.of(2026, 8, 1, 12, 0),
                        2,
                        List.of(
                                new WalkInPricingService.LineInput(
                                        standard, 1, null, 1),
                                new WalkInPricingService.LineInput(
                                        deluxe, 1, null, 1)));

        assertFalse(calculation.isPresent());
        verifyNoInteractions(rateProfileRepository);
    }

    @Test
    void rejectsDeclaredGuestTotalBelowRequiredRoomGuests() {
        RoomRateProfile standardRate = rate(
                21L, standard, 1, "70000", "20000", "170000", "300000");
        when(rateProfileRepository.findEffectiveByRoomTypeCodeForUpdate(
                eq("STANDARD"), any(Instant.class)))
                .thenReturn(List.of(standardRate));

        AppException exception = assertThrows(
                AppException.class,
                () -> service.calculateIfEligible(
                        LocalDateTime.of(2026, 8, 1, 10, 0),
                        LocalDateTime.of(2026, 8, 1, 12, 0),
                                1,
                                List.of(new WalkInPricingService.LineInput(
                                standard, 2, null, 0))));

        assertEquals(ErrorCode.INVALID_REQUEST, exception.getErrorCode());
    }

    @Test
    void exposesPricingEngineCyclesAtDailyGraceBoundary() {
        RoomRateProfile standardRate = rate(
                21L, standard, 1, "70000", "20000", "170000", "300000");
        when(rateProfileRepository.findEffectiveByRoomTypeCodeForUpdate(
                eq("STANDARD"), any(Instant.class)))
                .thenReturn(List.of(standardRate));

        WalkInPricingService.Calculation withinGrace =
                service.calculateIfEligible(
                                LocalDateTime.of(2026, 8, 1, 12, 0),
                                LocalDateTime.of(2026, 8, 2, 12, 15),
                                1,
                                List.of(new WalkInPricingService.LineInput(
                                        standard, 1, null, 1)))
                        .orElseThrow();
        WalkInPricingService.Calculation beyondGrace =
                service.calculateIfEligible(
                                LocalDateTime.of(2026, 8, 1, 12, 0),
                                LocalDateTime.of(2026, 8, 2, 12, 16),
                                1,
                                List.of(new WalkInPricingService.LineInput(
                                        standard, 1, null, 1)))
                        .orElseThrow();

        assertEquals(1, withinGrace.packageCycles());
        assertEquals(2, beyondGrace.packageCycles());
    }

    @Test
    void rejectsAmbiguousEffectiveRatesForWalkIn() {
        RoomRateProfile standardRate = rate(
                21L, standard, 1, "70000", "20000", "170000", "300000");
        when(rateProfileRepository.findEffectiveByRoomTypeCodeForUpdate(
                eq("STANDARD"), any(Instant.class)))
                .thenReturn(List.of(standardRate, standardRate));

        AppException exception = assertThrows(
                AppException.class,
                () -> service.calculateIfEligible(
                        LocalDateTime.of(2026, 8, 1, 10, 0),
                        LocalDateTime.of(2026, 8, 1, 12, 0),
                        1,
                        List.of(new WalkInPricingService.LineInput(
                                standard, 1, 1, 0))));

        assertEquals(ErrorCode.PRICING_PROFILE_NOT_FOUND, exception.getErrorCode());
    }

    private WalkInPricingService.CalculatedLine line(
            WalkInPricingService.Calculation calculation,
            String roomTypeCode) {
        return calculation.lines().stream()
                .filter(line -> roomTypeCode.equals(line.roomType().getCode()))
                .findFirst()
                .orElseThrow();
    }

    private RoomType roomType(
            long id,
            String code,
            String name,
            int maxGuests) {
        RoomType roomType = RoomType.builder()
                .code(code)
                .typeName(name)
                .maxGuests(maxGuests)
                .build();
        roomType.setId(id);
        return roomType;
    }

    private RoomRateProfile rate(
            long id,
            RoomType roomType,
            int includedGuests,
            String firstBlockPrice,
            String extraUnitPrice,
            String overnightPrice,
            String dailyPrice) {
        return RoomRateProfile.builder()
                .id(id)
                .roomType(roomType)
                .stayPolicyVersion(policy)
                .profileVersion(1)
                .includedGuests(includedGuests)
                .firstBlockMinutes(120)
                .firstBlockPrice(new BigDecimal(firstBlockPrice))
                .extraUnitMinutes(60)
                .extraUnitPrice(new BigDecimal(extraUnitPrice))
                .overnightPrice(new BigDecimal(overnightPrice))
                .dailyPrice(new BigDecimal(dailyPrice))
                .extraGuestPrice(new BigDecimal("50000"))
                .extraGuestBillingMode(
                        ExtraGuestBillingMode.PER_PACKAGE_CYCLE)
                .effectiveFromUtc(Instant.parse("2026-01-01T00:00:00Z"))
                .active(true)
                .createdAtUtc(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
