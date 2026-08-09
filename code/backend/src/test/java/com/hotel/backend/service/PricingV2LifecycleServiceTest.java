package com.hotel.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.constant.*;
import com.hotel.backend.entity.*;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.pricing.MotelPackagePricingEngine;
import com.hotel.backend.pricing.PricingDefinitionFactory;
import com.hotel.backend.pricing.PricingQuoteAggregates;
import com.hotel.backend.repository.ReservationRateSnapshotRepository;
import com.hotel.backend.repository.ReservationRoomTypeRepository;
import com.hotel.backend.util.CanonicalJsonHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PricingV2LifecycleServiceTest {

    @Mock
    private ReservationRoomTypeRepository reservationRoomTypeRepository;

    @Mock
    private ReservationRateSnapshotRepository snapshotRepository;

    private PricingV2LifecycleService service;
    private Reservation reservation;
    private ReservationRoomType reservationLine;
    private ReservationRateSnapshot commitment;

    @BeforeEach
    void setUp() {
        service = new PricingV2LifecycleService(
                new MotelPackagePricingEngine(),
                new PricingDefinitionFactory(),
                new PricingQuoteAggregates(),
                reservationRoomTypeRepository,
                snapshotRepository,
                new CanonicalJsonHasher(
                        new ObjectMapper().findAndRegisterModules()));

        LocalDateTime checkIn =
                LocalDateTime.of(2026, 8, 1, 22, 0);
        LocalDateTime checkOut =
                LocalDateTime.of(2026, 8, 2, 10, 0);
        reservation = Reservation.builder()
                .reservationCode("RES-V2-LIFECYCLE")
                .checkIn(checkIn)
                .checkOut(checkOut)
                .actualCheckIn(checkIn)
                .totalAmount(money("170000"))
                .lateCheckoutFee(money("0"))
                .guestCount(1)
                .pricingVersion(PricingAlgorithmVersion.MOTEL_PACKAGE_V2)
                .displayPackageSummary(StayPackage.OVERNIGHT)
                .inventoryProtectedUntil(
                        LocalDateTime.of(2026, 8, 2, 12, 30))
                .status(ReservationStatus.CHECKED_IN)
                .build();
        reservation.setId(101L);

        RoomType roomType = RoomType.builder()
                .code("STANDARD")
                .typeName("Phòng tiêu chuẩn")
                .maxGuests(2)
                .build();
        roomType.setId(11L);
        StayPolicyVersion policy = policy();
        RoomRateProfile rate = rate(roomType, policy);

        reservationLine = ReservationRoomType.builder()
                .reservation(reservation)
                .roomType(roomType)
                .quantity(1)
                .lineGuestCount(1)
                .roomPrice(money("170000"))
                .subtotal(money("170000"))
                .minimumCommittedRoomCharge(money("170000"))
                .maxPackageReached(StayPackage.OVERNIGHT)
                .build();
        reservationLine.setId(201L);

        commitment = snapshot(
                1,
                RateSnapshotStage.COMMITMENT,
                PricingTransitionReason.INITIAL_QUOTE,
                rate,
                policy,
                checkOut,
                money("170000"),
                money("0"),
                money("0"));

        when(reservationRoomTypeRepository
                .findDetailsByReservationId(reservation.getId()))
                .thenReturn(List.of(reservationLine));
        when(snapshotRepository
                .findByReservationRoomTypeIdOrderBySnapshotSequenceAsc(
                        reservationLine.getId()))
                .thenReturn(List.of(commitment));
    }

    @Test
    void overnightCheckoutAfterRefundLockKeepsCommittedPackageFloor() {
        PricingV2LifecycleService.Projection projection =
                service.project(
                        reservation,
                        LocalDateTime.of(2026, 8, 2, 5, 0));

        assertEquals(money("170000"), projection.projectedTotalAmount());
        assertEquals(money("0"), projection.deltaAmount());
        assertEquals(money("0"), projection.cumulativePricingIncrease());
        assertEquals(StayPackage.OVERNIGHT, projection.displayPackage());
        assertEquals(
                reservation.getInventoryProtectedUntil(),
                projection.inventoryProtectedUntil());
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void overnightCheckoutBeforeRefundLockRepricesActualUsageAndPersistsAdjustment() {
        LocalDateTime actualCheckout =
                LocalDateTime.of(2026, 8, 1, 22, 30);

        PricingV2LifecycleService.Projection projection = service.apply(
                reservation,
                actualCheckout,
                RateSnapshotStage.CHECKOUT,
                PricingTransitionReason.ACTUAL_CHECKOUT);

        assertEquals(money("70000"), projection.projectedTotalAmount());
        assertEquals(money("-100000"), projection.deltaAmount());
        assertEquals(money("100000"),
                projection.earlyCheckoutAdjustment());
        assertEquals(money("100000"),
                reservation.getEarlyCheckoutAdjustment());
        assertEquals(money("70000"), reservationLine.getRoomPrice());

        ArgumentCaptor<ReservationRateSnapshot> captor =
                ArgumentCaptor.forClass(ReservationRateSnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        assertEquals(StayPackage.HOURLY,
                captor.getValue().getAppliedPackage());
        assertEquals(money("-100000"),
                captor.getValue().getAdjustmentAmount());
    }

    @Test
    void overnightCheckoutAtRefundLockKeepsCommittedPackageFloor() {
        PricingV2LifecycleService.Projection projection = service.project(
                reservation,
                LocalDateTime.of(2026, 8, 1, 23, 0));

        assertEquals(money("170000"), projection.projectedTotalAmount());
        assertEquals(money("0"), projection.deltaAmount());
        assertEquals(money("0"), projection.earlyCheckoutAdjustment());
    }

    @Test
    void hourlyEarlyCheckoutRepricesByActualDuration() {
        configureCommitment(
                LocalDateTime.of(2026, 8, 1, 10, 0),
                LocalDateTime.of(2026, 8, 1, 14, 0),
                StayPackage.HOURLY,
                "110000");

        PricingV2LifecycleService.Projection projection = service.project(
                reservation,
                LocalDateTime.of(2026, 8, 1, 11, 0));

        assertEquals(money("70000"), projection.projectedTotalAmount());
        assertEquals(money("-40000"), projection.deltaAmount());
        assertEquals(money("40000"),
                projection.earlyCheckoutAdjustment());
        assertEquals(StayPackage.HOURLY, projection.displayPackage());
    }

    @Test
    void dailyEarlyCheckoutRepricesFullUsageInsteadOfKeepingDailyFloor() {
        configureCommitment(
                LocalDateTime.of(2026, 8, 1, 12, 0),
                LocalDateTime.of(2026, 8, 2, 12, 0),
                StayPackage.DAILY,
                "300000");

        PricingV2LifecycleService.Projection projection = service.project(
                reservation,
                LocalDateTime.of(2026, 8, 1, 18, 0));

        assertEquals(money("150000"), projection.projectedTotalAmount());
        assertEquals(money("-150000"), projection.deltaAmount());
        assertEquals(money("150000"),
                projection.earlyCheckoutAdjustment());
        assertEquals(StayPackage.HOURLY, projection.displayPackage());
    }

    @Test
    void multiDayEarlyCheckoutKeepsUsedFullDaysAndRepricesOnlyTheRemainder() {
        configureCommitment(
                LocalDateTime.of(2026, 8, 1, 12, 0),
                LocalDateTime.of(2026, 8, 3, 20, 0),
                StayPackage.DAILY,
                "790000");

        PricingV2LifecycleService.Projection projection = service.project(
                reservation,
                LocalDateTime.of(2026, 8, 2, 18, 0));

        // 24 giờ đã dùng = 300k; 6 giờ dư = 70k + 4 x 20k.
        assertEquals(money("450000"), projection.projectedTotalAmount());
        assertEquals(money("-340000"), projection.deltaAmount());
        assertEquals(money("340000"),
                projection.earlyCheckoutAdjustment());
        assertEquals(StayPackage.DAILY, projection.displayPackage());
        assertEquals(1,
                projection.lines().get(0).breakdown().fullDays());
        assertEquals(360,
                projection.lines().get(0).breakdown().remainderMinutes());
    }

    @Test
    void lateStayAddsOnlyTheSnapshotDerivedDeltaAndAppendsEvidence() {
        LocalDateTime extendedCheckout =
                LocalDateTime.of(2026, 8, 2, 10, 16);

        PricingV2LifecycleService.Projection projection = service.apply(
                reservation,
                extendedCheckout,
                RateSnapshotStage.EXTENSION,
                PricingTransitionReason.EXTENSION);

        assertEquals(money("190000"), projection.projectedTotalAmount());
        assertEquals(money("20000"), projection.deltaAmount());
        assertEquals(money("20000"), projection.cumulativePricingIncrease());
        assertEquals(money("190000"), reservation.getTotalAmount());
        assertEquals(money("20000"), reservation.getLateCheckoutFee());
        assertEquals(money("190000"), reservationLine.getRoomPrice());
        assertEquals(money("190000"), reservationLine.getSubtotal());

        ArgumentCaptor<ReservationRateSnapshot> captor =
                ArgumentCaptor.forClass(ReservationRateSnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        ReservationRateSnapshot evidence = captor.getValue();
        assertEquals(2, evidence.getSnapshotSequence());
        assertEquals(RateSnapshotStage.EXTENSION, evidence.getSnapshotStage());
        assertEquals(
                PricingTransitionReason.EXTENSION,
                evidence.getTransitionReason());
        assertEquals(money("190000"), evidence.getFinalRoomCharge());
        assertEquals(money("20000"), evidence.getAdjustmentAmount());
        assertNotNull(evidence.getBreakdownJson());
        assertTrue(evidence.getSnapshotHash().matches("[0-9a-f]{64}"));
        verify(reservationRoomTypeRepository).save(reservationLine);
    }

    @Test
    void checkoutAppendsFinalEvidenceEvenWhenPriceDoesNotChange() {
        service.apply(
                reservation,
                reservation.getCheckOut(),
                RateSnapshotStage.CHECKOUT,
                PricingTransitionReason.ACTUAL_CHECKOUT);

        ArgumentCaptor<ReservationRateSnapshot> captor =
                ArgumentCaptor.forClass(ReservationRateSnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        assertEquals(
                RateSnapshotStage.CHECKOUT,
                captor.getValue().getSnapshotStage());
        assertEquals(
                reservation.getCheckOut(),
                captor.getValue().getActualCheckOut());
        assertEquals(money("0"), captor.getValue().getAdjustmentAmount());
    }

    @Test
    void checkInAppendsEvidenceWithoutChangingTheCommittedAmount() {
        service.applyAutomatic(
                reservation,
                reservation.getCheckOut(),
                RateSnapshotStage.CHECK_IN);

        ArgumentCaptor<ReservationRateSnapshot> captor =
                ArgumentCaptor.forClass(ReservationRateSnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        assertEquals(
                RateSnapshotStage.CHECK_IN,
                captor.getValue().getSnapshotStage());
        assertEquals(money("170000"), reservation.getTotalAmount());
        assertEquals(money("0"), captor.getValue().getAdjustmentAmount());
    }

    @Test
    void checkInChargesActualGuestRedistributionWithoutReducingCommitment() {
        reservation.setGuestCount(2);

        service.applyAutomatic(
                reservation,
                reservation.getCheckOut(),
                RateSnapshotStage.CHECK_IN,
                Map.of(reservationLine.getId(), 2));

        ArgumentCaptor<ReservationRateSnapshot> captor =
                ArgumentCaptor.forClass(ReservationRateSnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        ReservationRateSnapshot evidence = captor.getValue();
        assertEquals(2, evidence.getLineGuestCount());
        assertEquals(money("50000"), evidence.getExtraGuestCharge());
        assertEquals(money("220000"), reservation.getTotalAmount());
        assertEquals(2, reservationLine.getLineGuestCount());
        assertEquals(money("220000"), reservationLine.getSubtotal());
    }

    @Test
    void checkInRejectsGuestDistributionThatLeavesABookedRoomEmpty() {
        reservationLine.setQuantity(2);
        commitment = snapshot(
                1,
                RateSnapshotStage.COMMITMENT,
                PricingTransitionReason.INITIAL_QUOTE,
                commitment.getRateProfile(),
                commitment.getStayPolicyVersion(),
                reservation.getCheckOut(),
                money("340000"),
                money("0"),
                money("0"),
                2,
                2);
        when(snapshotRepository
                .findByReservationRoomTypeIdOrderBySnapshotSequenceAsc(
                        reservationLine.getId()))
                .thenReturn(List.of(commitment));
        reservation.setGuestCount(1);

        AppException exception = assertThrows(
                AppException.class,
                () -> service.applyAutomatic(
                        reservation,
                        reservation.getCheckOut(),
                        RateSnapshotStage.CHECK_IN,
                        Map.of(reservationLine.getId(), 1)));

        assertTrue(exception.getMessage().contains(
                "Mỗi phòng phải có ít nhất một khách"));
        verify(snapshotRepository, never()).save(any());
        verify(reservationRoomTypeRepository, never()).save(any());
    }

    @Test
    void lateArrivalReceivesTwelveHoursWithoutDroppingCommitmentOrOvercharging() {
        reservation.setActualCheckIn(
                LocalDateTime.of(2026, 8, 1, 23, 0));

        PricingV2LifecycleService.Projection projection =
                service.project(
                        reservation,
                        LocalDateTime.of(2026, 8, 2, 11, 0));

        assertEquals(money("170000"), projection.projectedTotalAmount());
        assertEquals(money("0"), projection.deltaAmount());
        assertEquals(StayPackage.OVERNIGHT, projection.displayPackage());
        assertEquals(
                LocalDateTime.of(2026, 8, 2, 12, 30),
                projection.inventoryProtectedUntil());
        assertEquals(
                LocalDateTime.of(2026, 8, 1, 23, 0),
                projection.lines().get(0).breakdown().cycles()
                        .get(0).billableStart());
        assertEquals(
                LocalDateTime.of(2026, 8, 2, 11, 0),
                projection.lines().get(0).breakdown()
                        .packageIncludedCheckout());
    }

    @Test
    void earlyArrivalUsesActualStartAndChargesBothEarlyAndLateUnits() {
        reservation.setActualCheckIn(
                LocalDateTime.of(2026, 8, 1, 19, 30));

        PricingV2LifecycleService.Projection projection =
                service.project(
                        reservation,
                        reservation.getCheckOut());

        assertEquals(money("230000"), projection.projectedTotalAmount());
        assertEquals(money("60000"), projection.deltaAmount());
        assertEquals(money("230000"), projection.actualRoomCharge());
        assertEquals(3, projection.lines().get(0).breakdown()
                .chargedExtraUnits());
        assertEquals(
                LocalDateTime.of(2026, 8, 1, 19, 30),
                projection.lines().get(0).breakdown().cycles()
                        .get(0).billableStart());
    }

    @Test
    void overnightFloorDoesNotRetainAnUnusedFutureExtension() {
        ReservationRateSnapshot latest = snapshot(
                2,
                RateSnapshotStage.EXTENSION,
                PricingTransitionReason.EXTENSION,
                commitment.getRateProfile(),
                commitment.getStayPolicyVersion(),
                LocalDateTime.of(2026, 8, 2, 10, 16),
                money("190000"),
                money("0"),
                money("20000"));
        reservation.setTotalAmount(money("190000"));
        reservation.setLateCheckoutFee(money("20000"));
        reservation.setCheckOut(
                LocalDateTime.of(2026, 8, 2, 10, 16));
        when(snapshotRepository
                .findByReservationRoomTypeIdOrderBySnapshotSequenceAsc(
                        reservationLine.getId()))
                .thenReturn(List.of(commitment, latest));

        PricingV2LifecycleService.Projection projection =
                service.project(
                        reservation,
                        LocalDateTime.of(2026, 8, 2, 10, 0));

        assertEquals(money("170000"), projection.projectedTotalAmount());
        assertEquals(money("-20000"), projection.deltaAmount());
        assertEquals(money("20000"),
                projection.earlyCheckoutAdjustment());
        assertEquals(money("0"), projection.cumulativePricingIncrease());
    }

    private ReservationRateSnapshot snapshot(
            int sequence,
            RateSnapshotStage stage,
            PricingTransitionReason reason,
            RoomRateProfile rate,
            StayPolicyVersion policy,
            LocalDateTime committedCheckOut,
            BigDecimal finalRoomCharge,
            BigDecimal extraGuestCharge,
            BigDecimal adjustment) {
        return snapshot(
                sequence,
                stage,
                reason,
                rate,
                policy,
                committedCheckOut,
                finalRoomCharge,
                extraGuestCharge,
                adjustment,
                1,
                1);
    }

    private ReservationRateSnapshot snapshot(
            int sequence,
            RateSnapshotStage stage,
            PricingTransitionReason reason,
            RoomRateProfile rate,
            StayPolicyVersion policy,
            LocalDateTime committedCheckOut,
            BigDecimal finalRoomCharge,
            BigDecimal extraGuestCharge,
            BigDecimal adjustment,
            int roomQuantity,
            int lineGuestCount) {
        return snapshot(
                sequence,
                stage,
                reason,
                rate,
                policy,
                committedCheckOut,
                finalRoomCharge,
                extraGuestCharge,
                adjustment,
                roomQuantity,
                lineGuestCount,
                StayPackage.OVERNIGHT,
                money("170000"));
    }

    private ReservationRateSnapshot snapshot(
            int sequence,
            RateSnapshotStage stage,
            PricingTransitionReason reason,
            RoomRateProfile rate,
            StayPolicyVersion policy,
            LocalDateTime committedCheckOut,
            BigDecimal finalRoomCharge,
            BigDecimal extraGuestCharge,
            BigDecimal adjustment,
            int roomQuantity,
            int lineGuestCount,
            StayPackage stayPackage,
            BigDecimal minimumCommittedRoomCharge) {
        return ReservationRateSnapshot.builder()
                .id((long) sequence)
                .reservationRoomType(reservationLine)
                .snapshotSequence(sequence)
                .snapshotStage(stage)
                .stayPolicyVersion(policy)
                .rateProfile(rate)
                .pricingAlgorithmVersion(
                        PricingAlgorithmVersion.MOTEL_PACKAGE_V2)
                .committedCheckIn(reservation.getCheckIn())
                .committedCheckOut(committedCheckOut)
                .actualCheckIn(reservation.getActualCheckIn())
                .stayClassification(stayPackage == StayPackage.OVERNIGHT
                        ? StayClassification.NIGHT_STAY
                        : StayClassification.DAY_STAY)
                .initialPackage(stayPackage)
                .appliedPackage(stayPackage)
                .maxPackageReached(stayPackage)
                .transitionReason(reason)
                .includedGuests(1)
                .maxGuestsSnapshot(2)
                .lineGuestCount(lineGuestCount)
                .roomQuantity(roomQuantity)
                .extraGuestCount(0)
                .firstBlockMinutes(120)
                .firstBlockPrice(money("70000"))
                .extraUnitMinutes(60)
                .extraUnitPrice(money("20000"))
                .graceMinutes(15)
                .overnightPrice(money("170000"))
                .overnightIncludedCheckout(stayPackage == StayPackage.OVERNIGHT
                        ? LocalDateTime.of(2026, 8, 2, 10, 0)
                        : null)
                .dailyPrice(money("300000"))
                .dailyDurationMinutes(1440)
                .fullDays(0)
                .remainderMinutes(720)
                .chargedExtraUnits(0)
                .minimumCommittedRoomCharge(minimumCommittedRoomCharge)
                .finalRoomCharge(finalRoomCharge)
                .extraGuestCharge(extraGuestCharge)
                .allocatedServiceCharge(money("0"))
                .adjustmentAmount(adjustment)
                .createdAtUtc(Instant.parse("2026-08-01T15:00:00Z"))
                .build();
    }

    private StayPolicyVersion policy() {
        return StayPolicyVersion.builder()
                .id(31L)
                .policyCode("DEFAULT_MOTEL_POLICY")
                .policyVersion(1)
                .graceMinutes(15)
                .overnightStartTime(LocalTime.of(20, 0))
                .overnightEarlyMorningEnd(LocalTime.of(8, 0))
                .overnightRefundLockTime(LocalTime.of(23, 0))
                .overnightHardCheckoutTime(LocalTime.NOON)
                .overnightMaximumMinutes(720)
                .dailyThresholdMinutes(1200)
                .dailyDurationMinutes(1440)
                .turnoverBufferMinutes(30)
                .inventoryProtectionMode(
                        InventoryProtectionMode.PACKAGE_ENTITLEMENT)
                .effectiveFromUtc(
                        Instant.parse("2026-01-01T00:00:00Z"))
                .active(true)
                .createdAtUtc(
                        Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    private void configureCommitment(
            LocalDateTime checkIn,
            LocalDateTime checkOut,
            StayPackage stayPackage,
            String committedCharge) {
        BigDecimal charge = money(committedCharge);
        reservation.setCheckIn(checkIn);
        reservation.setCheckOut(checkOut);
        reservation.setActualCheckIn(checkIn);
        reservation.setTotalAmount(charge);
        reservation.setEarlyCheckoutAdjustment(money("0"));
        reservation.setLateCheckoutFee(money("0"));
        reservation.setDisplayPackageSummary(stayPackage);

        reservationLine.setRoomPrice(charge);
        reservationLine.setSubtotal(charge);
        reservationLine.setMinimumCommittedRoomCharge(charge);
        reservationLine.setMaxPackageReached(stayPackage);

        commitment = snapshot(
                1,
                RateSnapshotStage.COMMITMENT,
                PricingTransitionReason.INITIAL_QUOTE,
                commitment.getRateProfile(),
                commitment.getStayPolicyVersion(),
                checkOut,
                charge,
                money("0"),
                money("0"),
                1,
                1,
                stayPackage,
                charge);
        when(snapshotRepository
                .findByReservationRoomTypeIdOrderBySnapshotSequenceAsc(
                        reservationLine.getId()))
                .thenReturn(List.of(commitment));
    }

    private RoomRateProfile rate(
            RoomType roomType,
            StayPolicyVersion policy) {
        return RoomRateProfile.builder()
                .id(41L)
                .roomType(roomType)
                .stayPolicyVersion(policy)
                .profileVersion(1)
                .includedGuests(1)
                .firstBlockMinutes(120)
                .firstBlockPrice(money("70000"))
                .extraUnitMinutes(60)
                .extraUnitPrice(money("20000"))
                .overnightPrice(money("170000"))
                .dailyPrice(money("300000"))
                .extraGuestPrice(money("50000"))
                .extraGuestBillingMode(
                        ExtraGuestBillingMode.PER_PACKAGE_CYCLE)
                .effectiveFromUtc(
                        Instant.parse("2026-01-01T00:00:00Z"))
                .active(true)
                .createdAtUtc(
                        Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
