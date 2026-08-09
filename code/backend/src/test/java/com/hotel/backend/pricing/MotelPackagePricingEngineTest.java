package com.hotel.backend.pricing;

import com.hotel.backend.constant.ExtraGuestBillingMode;
import com.hotel.backend.constant.InventoryProtectionMode;
import com.hotel.backend.constant.PricingTransitionReason;
import com.hotel.backend.constant.StayPackage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MotelPackagePricingEngineTest {

    private final MotelPackagePricingEngine engine =
            new MotelPackagePricingEngine();
    private final StayPolicyDefinition policy = defaultPolicy();

    @Test
    void rejectsASelectedRoomWithoutAtLeastOneGuest() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new PricingRequest(
                        at(2026, 8, 1, 10, 0),
                        at(2026, 8, 1, 12, 0),
                        2,
                        1));

        assertEquals(
                "lineGuestCount must include at least one guest per room",
                exception.getMessage());
    }

    @Test
    void hourlyGraceIsSubtractedBeforeRoundingTheExtraUnit() {
        LocalDateTime checkIn = at(2026, 8, 1, 8, 0);

        assertMoney("70000", calculate(standard(), checkIn, checkIn.plusHours(2))
                .roomChargePerRoom());
        assertMoney("70000", calculate(
                standard(), checkIn, checkIn.plusHours(2).plusMinutes(15))
                .roomChargePerRoom());
        assertMoney("90000", calculate(
                standard(), checkIn, checkIn.plusHours(2).plusMinutes(16))
                .roomChargePerRoom());
        assertMoney("90000", calculate(
                standard(), checkIn, checkIn.plusHours(3).plusMinutes(15))
                .roomChargePerRoom());
        assertMoney("110000", calculate(
                standard(), checkIn, checkIn.plusHours(3).plusMinutes(16))
                .roomChargePerRoom());
    }

    @Test
    void aTenHourDaytimeStayRemainsHourly() {
        LocalDateTime checkIn = at(2026, 8, 1, 8, 0);

        PricingBreakdown result =
                calculate(standard(), checkIn, checkIn.plusHours(10));

        assertEquals(StayPackage.HOURLY, result.appliedPackage());
        assertMoney("230000", result.roomChargePerRoom());
    }

    @Test
    void overnightLateArrivalBuysTwelveHoursButNeverPastNoon() {
        LocalDateTime checkIn = at(2026, 8, 1, 22, 0);
        PricingBreakdown lateEvening =
                calculate(standard(), checkIn, at(2026, 8, 2, 5, 0));

        assertEquals(StayPackage.OVERNIGHT, lateEvening.appliedPackage());
        assertMoney("170000", lateEvening.roomChargePerRoom());
        assertEquals(at(2026, 8, 2, 10, 0),
                lateEvening.packageIncludedCheckout());

        LocalDateTime earlyMorning = at(2026, 8, 2, 1, 0);
        PricingBreakdown cappedAtNoon =
                calculate(standard(), earlyMorning, at(2026, 8, 2, 7, 0));
        assertEquals(at(2026, 8, 2, 12, 0),
                cappedAtNoon.packageIncludedCheckout());
    }

    @Test
    void earlyMorningArrivalBeforeEightUsesTheOvernightPackage() {
        PricingBreakdown result = calculate(
                standard(),
                at(2026, 8, 2, 7, 30),
                at(2026, 8, 2, 9, 30));

        assertEquals(StayPackage.OVERNIGHT, result.appliedPackage());
        assertMoney("170000", result.roomChargePerRoom());
        assertEquals(
                at(2026, 8, 2, 12, 0),
                result.packageIncludedCheckout());
    }

    @Test
    void overnightEarlyAndLateGraceUseMinutePrecision() {
        PricingBreakdown earlyWithinGrace = calculate(
                standard(),
                at(2026, 8, 1, 19, 50),
                at(2026, 8, 2, 5, 0));
        PricingBreakdown earlyOutsideGrace = calculate(
                standard(),
                at(2026, 8, 1, 19, 40),
                at(2026, 8, 2, 5, 0));

        assertMoney("170000", earlyWithinGrace.roomChargePerRoom());
        assertMoney("190000", earlyOutsideGrace.roomChargePerRoom());

        LocalDateTime checkIn = at(2026, 8, 1, 22, 0);
        assertMoney("170000", calculate(
                standard(), checkIn, at(2026, 8, 2, 10, 15))
                .roomChargePerRoom());
        assertMoney("190000", calculate(
                standard(), checkIn, at(2026, 8, 2, 10, 16))
                .roomChargePerRoom());
        assertMoney("190000", calculate(
                standard(), checkIn, at(2026, 8, 2, 11, 15))
                .roomChargePerRoom());
        assertMoney("210000", calculate(
                standard(), checkIn, at(2026, 8, 2, 11, 16))
                .roomChargePerRoom());
        assertMoney("210000", calculate(
                standard(), checkIn, at(2026, 8, 2, 12, 0))
                .roomChargePerRoom());
    }

    @Test
    void overnightGraceRoundsAnyPartialMinuteUpConsistently() {
        LocalDateTime checkIn = at(2026, 8, 1, 22, 0);

        assertMoney("190000", calculate(
                standard(),
                checkIn,
                at(2026, 8, 2, 10, 15).plusNanos(1))
                .roomChargePerRoom());
        assertMoney("190000", calculate(
                standard(),
                at(2026, 8, 1, 19, 45).minusNanos(1),
                at(2026, 8, 2, 5, 0))
                .roomChargePerRoom());
    }

    @Test
    void dailyPriceCapAppliesOnlyToRoomCharge() {
        LocalDateTime checkIn = at(2026, 8, 1, 8, 0);
        PricingBreakdown result = engine.calculate(
                new PricingRequest(checkIn, checkIn.plusHours(14), 1, 2),
                standard(),
                policy);

        assertEquals(StayPackage.DAILY, result.appliedPackage());
        assertEquals(PricingTransitionReason.PRICE_CAP, result.transitionReason());
        assertMoney("300000", result.roomCharge());
        assertEquals(1, result.extraGuestCount());
        assertMoney("50000", result.extraGuestCharge());
        assertMoney("350000", result.lineTotalBeforeServices());
    }

    @Test
    void dailyCyclesHaveBoundaryGraceAndNoPhantomRemainder() {
        LocalDateTime checkIn = at(2026, 8, 1, 12, 0);

        assertMoney("300000", calculate(
                standard(), checkIn, checkIn.plusHours(24))
                .roomChargePerRoom());
        assertMoney("300000", calculate(
                standard(), checkIn, checkIn.plusHours(24).plusMinutes(15))
                .roomChargePerRoom());
        assertMoney("370000", calculate(
                standard(), checkIn, checkIn.plusHours(24).plusMinutes(16))
                .roomChargePerRoom());
        assertMoney("600000", calculate(
                standard(), checkIn, checkIn.plusHours(48))
                .roomChargePerRoom());
    }

    @Test
    void chargeableDailyRemainderStartsAtTheExactDailyBoundary() {
        LocalDateTime checkIn = at(2026, 8, 1, 12, 0);

        PricingBreakdown firstRemainderBlock = calculate(
                standard(), checkIn, checkIn.plusHours(24).plusMinutes(16));
        PricingBreakdown extraRemainderHour = calculate(
                standard(), checkIn, checkIn.plusHours(26).plusMinutes(16));

        assertEquals(checkIn.plusHours(24),
                firstRemainderBlock.cycles().get(1).billableStart());
        assertEquals(checkIn.plusHours(26),
                firstRemainderBlock.cycles().get(1).packageIncludedCheckout());
        assertMoney("370000", firstRemainderBlock.roomChargePerRoom());
        assertMoney("390000", extraRemainderHour.roomChargePerRoom());
    }

    @Test
    void fiftySixHoursUsesTwoDailyCyclesAndAnOvernightRemainder() {
        LocalDateTime checkIn = at(2026, 8, 1, 20, 0);
        PricingBreakdown result =
                calculate(standard(), checkIn, checkIn.plusHours(56));

        assertEquals(2, result.fullDays());
        assertEquals(480, result.remainderMinutes());
        assertEquals(3, result.packageCycles());
        assertEquals(List.of(
                        StayPackage.DAILY,
                        StayPackage.DAILY,
                        StayPackage.OVERNIGHT),
                result.cycles().stream()
                        .map(PricingCycleBreakdown::appliedPackage)
                        .toList());
        assertMoney("770000", result.roomChargePerRoom());
    }

    @Test
    void sameFiftySixHourDurationPricesTheRemainderByItsActualTimeWindow() {
        LocalDateTime daytimeStart = at(2026, 8, 1, 12, 0);
        LocalDateTime overnightStart = at(2026, 8, 1, 20, 0);

        PricingBreakdown daytimeRemainder = calculate(
                standard(), daytimeStart, daytimeStart.plusHours(56));
        PricingBreakdown overnightRemainder = calculate(
                standard(), overnightStart, overnightStart.plusHours(56));

        assertEquals(StayPackage.HOURLY,
                daytimeRemainder.cycles().get(2).appliedPackage());
        assertEquals(StayPackage.OVERNIGHT,
                overnightRemainder.cycles().get(2).appliedPackage());
        assertMoney("790000", daytimeRemainder.roomChargePerRoom());
        assertMoney("770000", overnightRemainder.roomChargePerRoom());
    }

    @Test
    void approvedRateMatrixProducesExactAmountsForEveryRoomType() {
        LocalDateTime daytimeStart = at(2026, 8, 1, 8, 0);
        LocalDateTime overnightStart = at(2026, 8, 1, 20, 0);

        for (RateExpectation expectation : rateExpectations()) {
            RoomRateDefinition rate = expectation.rate();

            assertMoney(expectation.firstBlock(), calculate(
                    rate, daytimeStart, daytimeStart.plusHours(2))
                    .roomChargePerRoom());
            assertMoney(expectation.threeHours(), calculate(
                    rate, daytimeStart, daytimeStart.plusHours(3))
                    .roomChargePerRoom());
            assertMoney(expectation.tenHours(), calculate(
                    rate, daytimeStart, daytimeStart.plusHours(10))
                    .roomChargePerRoom());
            assertMoney(expectation.overnight(), calculate(
                    rate, overnightStart, overnightStart.plusHours(12))
                    .roomChargePerRoom());
            assertMoney(expectation.overnightPlusOneUnit(), calculate(
                    rate,
                    overnightStart,
                    overnightStart.plusHours(12).plusMinutes(16))
                    .roomChargePerRoom());
            assertMoney(expectation.daily(), calculate(
                    rate, daytimeStart, daytimeStart.plusHours(20))
                    .roomChargePerRoom());
            assertMoney(expectation.twoDays(), calculate(
                    rate, daytimeStart, daytimeStart.plusHours(48))
                    .roomChargePerRoom());

            PricingBreakdown fiftySixHours = engine.calculate(
                    new PricingRequest(
                            overnightStart,
                            overnightStart.plusHours(56),
                            1,
                            rate.maxGuests()),
                    rate,
                    policy);
            assertMoney(
                    expectation.fiftySixHours(),
                    fiftySixHours.roomChargePerRoom());
            assertEquals(3, fiftySixHours.packageCycles());
            assertMoney(
                    String.valueOf(
                            (rate.maxGuests() - rate.includedGuests())
                                    * 50_000L
                                    * 3L),
                    fiftySixHours.extraGuestCharge());
        }
    }

    @Test
    void subMinutePastGraceStartsTheNextChargeUnit() {
        LocalDateTime checkIn = at(2026, 8, 1, 8, 0);

        assertMoney("70000", calculate(
                standard(),
                checkIn,
                checkIn.plusHours(2).plusMinutes(15))
                .roomChargePerRoom());
        assertMoney("90000", calculate(
                standard(),
                checkIn,
                checkIn.plusHours(2).plusMinutes(15).plusNanos(1))
                .roomChargePerRoom());
    }

    @Test
    void dailyThresholdAndOvernightWindowBoundariesAreExplicit() {
        LocalDateTime daytimeStart = at(2026, 8, 1, 8, 0);
        PricingBreakdown threshold = calculate(
                standard(), daytimeStart, daytimeStart.plusHours(20));
        assertEquals(StayPackage.DAILY, threshold.appliedPackage());
        assertEquals(
                PricingTransitionReason.DAILY_DURATION,
                threshold.transitionReason());

        PricingBreakdown beforeEight = calculate(
                standard(),
                at(2026, 8, 2, 7, 59),
                at(2026, 8, 2, 9, 59));
        PricingBreakdown atEight = calculate(
                standard(),
                at(2026, 8, 2, 8, 0),
                at(2026, 8, 2, 10, 0));
        assertEquals(StayPackage.OVERNIGHT, beforeEight.appliedPackage());
        assertEquals(StayPackage.HOURLY, atEight.appliedPackage());
        assertMoney("170000", beforeEight.roomChargePerRoom());
        assertMoney("70000", atEight.roomChargePerRoom());
    }

    @Test
    void shortEarlyMorningStayRemainsHourlyUntilMinimumOvernightDuration() {
        LocalDateTime checkIn = at(2026, 8, 2, 7, 59);

        PricingBreakdown elevenMinutes = calculate(
                standard(), checkIn, at(2026, 8, 2, 8, 10));
        PricingBreakdown oneHundredNineteenMinutes = calculate(
                standard(), checkIn, checkIn.plusMinutes(119));
        PricingBreakdown twoHours = calculate(
                standard(), checkIn, checkIn.plusMinutes(120));

        assertEquals(StayPackage.HOURLY, elevenMinutes.appliedPackage());
        assertEquals(StayPackage.HOURLY, oneHundredNineteenMinutes.appliedPackage());
        assertEquals(StayPackage.OVERNIGHT, twoHours.appliedPackage());
        assertMoney("70000", elevenMinutes.roomChargePerRoom());
        assertMoney("70000", oneHundredNineteenMinutes.roomChargePerRoom());
        assertMoney("170000", twoHours.roomChargePerRoom());
    }

    @Test
    void historicalPolicyFlagsReproduceThePreV21Boundaries() {
        StayPolicyDefinition historicalPolicy = new StayPolicyDefinition(
                15,
                LocalTime.of(20, 0),
                LocalTime.of(8, 0),
                0,
                LocalTime.of(23, 0),
                LocalTime.NOON,
                720,
                1200,
                1440,
                30,
                false,
                InventoryProtectionMode.PACKAGE_ENTITLEMENT);
        LocalDateTime dailyStart = at(2026, 8, 1, 12, 0);

        PricingBreakdown historicalRemainder = engine.calculate(
                new PricingRequest(
                        dailyStart,
                        dailyStart.plusHours(24).plusMinutes(16),
                        1,
                        1),
                standard(),
                historicalPolicy);
        PricingBreakdown historicalEarlyMorning = engine.calculate(
                new PricingRequest(
                        at(2026, 8, 2, 7, 59),
                        at(2026, 8, 2, 8, 10),
                        1,
                        1),
                standard(),
                historicalPolicy);

        assertEquals(
                dailyStart.plusHours(24).plusMinutes(15),
                historicalRemainder.cycles().get(1).billableStart());
        assertEquals(
                StayPackage.OVERNIGHT,
                historicalEarlyMorning.appliedPackage());
    }

    @Test
    void roomQuantityAndGuestCapacityAreCalculatedPerLine() {
        LocalDateTime checkIn = at(2026, 8, 1, 8, 0);
        PricingBreakdown result = engine.calculate(
                new PricingRequest(checkIn, checkIn.plusHours(2), 2, 3),
                standard(),
                policy);

        assertMoney("140000", result.roomCharge());
        assertEquals(1, result.extraGuestCount());
        assertMoney("50000", result.extraGuestCharge());
        assertThrows(
                IllegalArgumentException.class,
                () -> engine.calculate(
                        new PricingRequest(checkIn, checkIn.plusHours(2), 2, 5),
                        standard(),
                        policy));
    }

    @Test
    void allSeedRatesRemainMonotonicForFixedBoundaryCheckIns() {
        List<RoomRateDefinition> rates = seedRates();
        List<LocalDateTime> fixedCheckIns = List.of(
                at(2026, 8, 1, 5, 59),
                at(2026, 8, 1, 6, 0),
                at(2026, 8, 1, 7, 59),
                at(2026, 8, 1, 8, 0),
                at(2026, 8, 1, 19, 59),
                at(2026, 8, 1, 20, 0),
                at(2026, 8, 1, 23, 59),
                at(2026, 8, 2, 0, 0));

        for (RoomRateDefinition rate : rates) {
            for (LocalDateTime checkIn : fixedCheckIns) {
                BigDecimal previous = BigDecimal.ZERO;
                for (int durationMinutes = 1;
                     durationMinutes <= 14 * 24 * 60;
                     durationMinutes++) {
                    BigDecimal current = calculate(
                            rate,
                            checkIn,
                            checkIn.plusMinutes(durationMinutes))
                            .lineTotalBeforeServices();
                    assertTrue(
                            current.compareTo(previous) >= 0,
                            "Price decreased for "
                                    + rate.roomTypeCode()
                                    + " at "
                                    + checkIn
                                    + " duration="
                                    + durationMinutes
                                    + " previous="
                                    + previous
                                    + " current="
                                    + current);
                    previous = current;
                }
            }
        }
    }

    @Test
    void invalidRateOrderingIsRejectedBeforeItCanBreakMonotonicity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> rate(
                        "INVALID",
                        1,
                        2,
                        "70000",
                        "20000",
                        "60000",
                        "300000"));
    }

    private PricingBreakdown calculate(
            RoomRateDefinition rate,
            LocalDateTime checkIn,
            LocalDateTime checkOut) {
        return engine.calculate(
                new PricingRequest(checkIn, checkOut, 1, 1),
                rate,
                policy);
    }

    private StayPolicyDefinition defaultPolicy() {
        return new StayPolicyDefinition(
                15,
                LocalTime.of(20, 0),
                LocalTime.of(8, 0),
                120,
                LocalTime.of(23, 0),
                LocalTime.NOON,
                720,
                1200,
                1440,
                30,
                true,
                InventoryProtectionMode.PACKAGE_ENTITLEMENT);
    }

    private RoomRateDefinition standard() {
        return rate(
                "STANDARD",
                1,
                2,
                "70000",
                "20000",
                "170000",
                "300000");
    }

    private List<RoomRateDefinition> seedRates() {
        return rateExpectations().stream()
                .map(RateExpectation::rate)
                .toList();
    }

    private List<RateExpectation> rateExpectations() {
        return List.of(
                new RateExpectation(
                        standard(),
                        "70000", "90000", "230000", "170000",
                        "190000", "300000", "600000", "770000"),
                new RateExpectation(
                        rate("DELUXE", 2, 3, "100000", "25000", "220000", "400000"),
                        "100000", "125000", "300000", "220000",
                        "245000", "400000", "800000", "1020000"),
                new RateExpectation(
                        rate("EXECUTIVE", 2, 3, "120000", "30000", "270000", "480000"),
                        "120000", "150000", "360000", "270000",
                        "300000", "480000", "960000", "1230000"),
                new RateExpectation(
                        rate("SUITE", 2, 4, "150000", "35000", "350000", "600000"),
                        "150000", "185000", "430000", "350000",
                        "385000", "600000", "1200000", "1550000"),
                new RateExpectation(
                        rate("FAMILY", 4, 6, "130000", "30000", "330000", "550000"),
                        "130000", "160000", "370000", "330000",
                        "360000", "550000", "1100000", "1430000"),
                new RateExpectation(
                        rate("PRESIDENTIAL", 4, 6, "200000", "50000", "450000", "850000"),
                        "200000", "250000", "600000", "450000",
                        "500000", "850000", "1700000", "2150000"));
    }

    private record RateExpectation(
            RoomRateDefinition rate,
            String firstBlock,
            String threeHours,
            String tenHours,
            String overnight,
            String overnightPlusOneUnit,
            String daily,
            String twoDays,
            String fiftySixHours) {
    }

    private RoomRateDefinition rate(
            String code,
            int includedGuests,
            int maxGuests,
            String firstBlock,
            String extraUnit,
            String overnight,
            String daily) {
        return new RoomRateDefinition(
                code,
                includedGuests,
                maxGuests,
                120,
                new BigDecimal(firstBlock),
                60,
                new BigDecimal(extraUnit),
                new BigDecimal(overnight),
                new BigDecimal(daily),
                new BigDecimal("50000"),
                ExtraGuestBillingMode.PER_PACKAGE_CYCLE);
    }

    private LocalDateTime at(
            int year,
            int month,
            int day,
            int hour,
            int minute) {
        return LocalDateTime.of(year, month, day, hour, minute);
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
