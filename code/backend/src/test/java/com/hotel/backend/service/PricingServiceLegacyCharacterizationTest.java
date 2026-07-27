package com.hotel.backend.service;

import com.hotel.backend.entity.RoomType;
import com.hotel.backend.exception.AppException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Freezes the legacy pricing contract before MOTEL_PACKAGE_V2 is introduced.
 *
 * <p>These tests intentionally describe the existing behavior. Legacy
 * reservations must continue to use it and must not be recalculated with the
 * package-based engine.</p>
 */
class PricingServiceLegacyCharacterizationTest {

    private final PricingService pricingService =
            new PricingService(new BigDecimal("10000"), new BigDecimal("10000"));

    @Test
    void billableHoursRoundsAnyPartialHourUpAndKeepsOneHourMinimum() {
        LocalDateTime checkIn = LocalDateTime.of(2026, 8, 1, 10, 0);

        assertEquals(1L, pricingService.billableHours(checkIn, checkIn.plusMinutes(1)));
        assertEquals(1L, pricingService.billableHours(checkIn, checkIn.plusMinutes(60)));
        assertEquals(2L, pricingService.billableHours(checkIn, checkIn.plusMinutes(61)));
    }

    @Test
    void stayPriceUsesRoomTypePriceForFirstHourAndGlobalLegacyExtraHourFee() {
        RoomType roomType = RoomType.builder()
                .price(new BigDecimal("70000"))
                .build();
        LocalDateTime checkIn = LocalDateTime.of(2026, 8, 1, 10, 0);

        assertEquals(new BigDecimal("70000"),
                pricingService.calculateStayPricePerRoom(
                        roomType, checkIn, checkIn.plusMinutes(60)));
        assertEquals(new BigDecimal("90000"),
                pricingService.calculateStayPricePerRoom(
                        roomType, checkIn, checkIn.plusMinutes(121)));
    }

    @Test
    void recoverFirstHourPriceReversesTheLegacyHourlySnapshotFormula() {
        assertEquals(new BigDecimal("70000"),
                pricingService.recoverFirstHourPrice(new BigDecimal("100000"), 4L));
        assertEquals(BigDecimal.ZERO,
                pricingService.recoverFirstHourPrice(new BigDecimal("10000"), 4L));
    }

    @Test
    void lateCheckoutFeeUsesRoundedHoursProvidedByCallerAndRoomCount() {
        assertEquals(new BigDecimal("60000"),
                pricingService.calculateLateCheckoutFee(2L, 3));
    }

    @Test
    void invalidIntervalsRemainRejected() {
        LocalDateTime checkIn = LocalDateTime.of(2026, 8, 1, 10, 0);

        assertThrows(AppException.class,
                () -> pricingService.billableHours(checkIn, checkIn));
        assertThrows(AppException.class,
                () -> pricingService.billableHours(checkIn, checkIn.minusMinutes(1)));
    }
}
