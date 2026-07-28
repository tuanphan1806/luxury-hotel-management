package com.hotel.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.constant.*;
import com.hotel.backend.entity.*;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.pricing.PricingBreakdown;
import com.hotel.backend.pricing.PricingCycleBreakdown;
import com.hotel.backend.repository.ReservationRateSnapshotRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationRateSnapshotServiceTest {

    @Mock
    private ReservationRateSnapshotRepository snapshotRepository;

    private ReservationRateSnapshotService service;
    private StayPolicyVersion policy;
    private Reservation reservation;

    @BeforeEach
    void setUp() {
        service = new ReservationRateSnapshotService(
                snapshotRepository,
                new CanonicalJsonHasher(
                        new ObjectMapper().findAndRegisterModules()));

        policy = StayPolicyVersion.builder()
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

        reservation = Reservation.builder()
                .reservationCode("RES-SNAPSHOT")
                .checkIn(LocalDateTime.of(2026, 7, 27, 10, 0))
                .checkOut(LocalDateTime.of(2026, 7, 27, 12, 0))
                .totalAmount(new BigDecimal("210100.01"))
                .guestCount(3)
                .pricingVersion(PricingAlgorithmVersion.MOTEL_PACKAGE_V2)
                .status(ReservationStatus.PAYMENT_PENDING)
                .build();
        reservation.setId(101L);
    }

    @Test
    void allocatesEveryServiceCentDeterministicallyAcrossRoomLines() {
        RoomType firstType = roomType(1L, "STANDARD", 2);
        RoomType secondType = roomType(2L, "DELUXE", 2);
        RoomRateProfile firstRate = rate(21L, firstType);
        RoomRateProfile secondRate = rate(22L, secondType);
        PricingBreakdown firstBreakdown =
                breakdown(new BigDecimal("70000.00"), 1);
        PricingBreakdown secondBreakdown =
                breakdown(new BigDecimal("140000.00"), 2);

        ReservationRoomType firstReservationLine =
                reservationLine(201L, firstType, 1, 1);
        ReservationRoomType secondReservationLine =
                reservationLine(202L, secondType, 2, 2);
        Map<Long, ReservationRoomType> reservationLines =
                new LinkedHashMap<>();
        reservationLines.put(firstType.getId(), firstReservationLine);
        reservationLines.put(secondType.getId(), secondReservationLine);

        PricingQuoteCommitmentService.Commitment commitment =
                new PricingQuoteCommitmentService.Commitment(
                        null,
                        List.of(
                                new PricingQuoteCommitmentService.CommittedLine(
                                        firstType,
                                        firstRate,
                                        1,
                                        1,
                                        firstBreakdown),
                                new PricingQuoteCommitmentService.CommittedLine(
                                        secondType,
                                        secondRate,
                                        2,
                                        2,
                                        secondBreakdown)),
                        new ReservationAddOnService.BookingQuote(
                                List.of(), new BigDecimal("100.01")),
                        new BigDecimal("210000.00"),
                        BigDecimal.ZERO.setScale(2),
                        new BigDecimal("100.01"),
                        new BigDecimal("210100.01"),
                        reservation.getCheckOut().plusMinutes(30),
                        StayPackage.HOURLY);

        service.createCommitmentSnapshots(
                reservation, reservationLines, commitment);

        ArgumentCaptor<ReservationRateSnapshot> captor =
                ArgumentCaptor.forClass(ReservationRateSnapshot.class);
        verify(snapshotRepository, times(2)).save(captor.capture());
        List<ReservationRateSnapshot> snapshots = captor.getAllValues();
        assertEquals(
                new BigDecimal("33.33"),
                snapshots.get(0).getAllocatedServiceCharge());
        assertEquals(
                new BigDecimal("66.68"),
                snapshots.get(1).getAllocatedServiceCharge());
        assertEquals(
                new BigDecimal("100.01"),
                snapshots.stream()
                        .map(ReservationRateSnapshot::getAllocatedServiceCharge)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertTrue(snapshots.stream().allMatch(snapshot ->
                snapshot.getSnapshotStage() == RateSnapshotStage.COMMITMENT
                        && snapshot.getSnapshotSequence() == 1
                        && snapshot.getSnapshotHash().matches("[0-9a-f]{64}")));
    }

    @Test
    void rejectsCommitmentWhenReservationLineCannotBeLinked() {
        RoomType roomType = roomType(1L, "STANDARD", 2);
        PricingBreakdown breakdown =
                breakdown(new BigDecimal("70000.00"), 1);
        PricingQuoteCommitmentService.Commitment commitment =
                new PricingQuoteCommitmentService.Commitment(
                        null,
                        List.of(
                                new PricingQuoteCommitmentService.CommittedLine(
                                        roomType,
                                        rate(21L, roomType),
                                        1,
                                        1,
                                        breakdown)),
                        new ReservationAddOnService.BookingQuote(
                                List.of(), BigDecimal.ZERO.setScale(2)),
                        new BigDecimal("70000.00"),
                        BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2),
                        new BigDecimal("70000.00"),
                        reservation.getCheckOut().plusMinutes(30),
                        StayPackage.HOURLY);

        assertThrows(
                AppException.class,
                () -> service.createCommitmentSnapshots(
                        reservation, Map.of(), commitment));
        verifyNoInteractions(snapshotRepository);
    }

    private RoomType roomType(Long id, String code, int maxGuests) {
        RoomType roomType = RoomType.builder()
                .code(code)
                .typeName(code)
                .maxGuests(maxGuests)
                .build();
        roomType.setId(id);
        return roomType;
    }

    private RoomRateProfile rate(Long id, RoomType roomType) {
        return RoomRateProfile.builder()
                .id(id)
                .roomType(roomType)
                .stayPolicyVersion(policy)
                .profileVersion(1)
                .includedGuests(1)
                .firstBlockMinutes(120)
                .firstBlockPrice(new BigDecimal("70000.00"))
                .extraUnitMinutes(60)
                .extraUnitPrice(new BigDecimal("20000.00"))
                .overnightPrice(new BigDecimal("170000.00"))
                .dailyPrice(new BigDecimal("300000.00"))
                .extraGuestPrice(new BigDecimal("50000.00"))
                .extraGuestBillingMode(
                        ExtraGuestBillingMode.PER_PACKAGE_CYCLE)
                .effectiveFromUtc(Instant.parse("2026-01-01T00:00:00Z"))
                .active(true)
                .createdAtUtc(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    private ReservationRoomType reservationLine(
            Long id, RoomType roomType, int quantity, int guests) {
        ReservationRoomType line = ReservationRoomType.builder()
                .reservation(reservation)
                .roomType(roomType)
                .quantity(quantity)
                .lineGuestCount(guests)
                .roomPrice(new BigDecimal("70000.00"))
                .subtotal(new BigDecimal("70000.00")
                        .multiply(BigDecimal.valueOf(quantity)))
                .build();
        line.setId(id);
        return line;
    }

    private PricingBreakdown breakdown(
            BigDecimal roomCharge, int quantity) {
        BigDecimal perRoom = roomCharge.divide(
                BigDecimal.valueOf(quantity));
        LocalDateTime checkIn = reservation.getCheckIn();
        LocalDateTime checkOut = reservation.getCheckOut();
        PricingCycleBreakdown cycle = new PricingCycleBreakdown(
                1,
                StayPackage.HOURLY,
                PricingTransitionReason.HOURLY_WINDOW,
                checkIn,
                checkOut,
                checkOut,
                120,
                0,
                perRoom);
        return new PricingBreakdown(
                120,
                StayClassification.DAY_STAY,
                StayPackage.HOURLY,
                PricingTransitionReason.HOURLY_WINDOW,
                0,
                120,
                1,
                0,
                checkOut,
                perRoom,
                roomCharge,
                0,
                BigDecimal.ZERO.setScale(2),
                roomCharge,
                List.of(cycle));
    }
}
