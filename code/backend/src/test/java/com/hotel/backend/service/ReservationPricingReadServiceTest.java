package com.hotel.backend.service;

import com.hotel.backend.constant.PricingAlgorithmVersion;
import com.hotel.backend.constant.RateSnapshotStage;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.StayPackage;
import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.dto.response.ReservationRoomTypeResponse;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.ReservationRateSnapshot;
import com.hotel.backend.entity.ReservationRoomType;
import com.hotel.backend.repository.ReservationRateSnapshotRepository;
import com.hotel.backend.pricing.PricingBreakdown;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationPricingReadServiceTest {

    @Mock
    private ReservationRateSnapshotRepository snapshotRepository;
    @Mock
    private PricingV2LifecycleService pricingV2LifecycleService;

    private ReservationPricingReadService service;

    @BeforeEach
    void setUp() {
        service = new ReservationPricingReadService(
                snapshotRepository, pricingV2LifecycleService);
    }

    @Test
    void v2UsesCommitmentAndLatestSnapshotsInsteadOfReverseEngineeringTotal() {
        Reservation reservation = Reservation.builder()
                .pricingVersion(
                        PricingAlgorithmVersion.MOTEL_PACKAGE_V2)
                .totalAmount(money("360000"))
                .discountAmount(money("0"))
                .taxAmount(money("0"))
                .checkoutAdditionalFee(money("30000"))
                .build();
        reservation.setId(41L);
        ReservationRoomType reservationLine =
                ReservationRoomType.builder().build();
        reservationLine.setId(51L);

        ReservationRateSnapshot commitment =
                ReservationRateSnapshot.builder()
                        .reservationRoomType(reservationLine)
                        .snapshotSequence(1)
                        .snapshotStage(RateSnapshotStage.COMMITMENT)
                        .finalRoomCharge(money("140000"))
                        .extraGuestCharge(money("50000"))
                        .allocatedServiceCharge(money("20000"))
                        .maxGuestsSnapshot(3)
                        .appliedPackage(StayPackage.HOURLY)
                        .snapshotHash("commitment")
                        .build();
        ReservationRateSnapshot latest =
                ReservationRateSnapshot.builder()
                        .reservationRoomType(reservationLine)
                        .snapshotSequence(2)
                        .snapshotStage(RateSnapshotStage.EXTENSION)
                        .finalRoomCharge(money("180000"))
                        .extraGuestCharge(money("100000"))
                        .allocatedServiceCharge(money("20000"))
                        .appliedPackage(StayPackage.DAILY)
                        .snapshotHash("latest")
                        .build();
        when(snapshotRepository
                .findByReservationIdOrderByLineAndSequence(41L))
                .thenReturn(List.of(commitment, latest));

        ReservationRoomTypeResponse lineResponse =
                ReservationRoomTypeResponse.builder()
                        .id(51L)
                        .quantity(2)
                        .maxGuestsPerRoom(2)
                        .build();
        ReservationResponse response = ReservationResponse.builder()
                .addOnServiceAmount(money("50000"))
                .checkoutAdditionalFee(money("30000"))
                .roomTypes(List.of(lineResponse))
                .build();

        ReservationResponse result =
                service.enrich(reservation, response);

        assertThat(result.getPlannedTotalAmount())
                .isEqualByComparingTo("210000.00");
        assertThat(result.getActualTotalAmount())
                .isEqualByComparingTo("360000.00");
        assertThat(result.getPlannedRoomCharge())
                .isEqualByComparingTo("140000.00");
        assertThat(result.getActualRoomCharge())
                .isEqualByComparingTo("180000.00");
        assertThat(result.getPlannedExtraGuestCharge())
                .isEqualByComparingTo("50000.00");
        assertThat(result.getExtraGuestCharge())
                .isEqualByComparingTo("100000.00");
        assertThat(result.getPostCommitmentRoomIncrease())
                .isEqualByComparingTo("40000.00");
        assertThat(result.getPlannedAddOnServiceAmount())
                .isEqualByComparingTo("20000.00");
        assertThat(result.getActualRoomCharge()
                .add(result.getExtraGuestCharge())
                .add(result.getAddOnServiceAmount())
                .add(result.getCheckoutAdditionalFee()))
                .isEqualByComparingTo(result.getActualTotalAmount());

        assertThat(lineResponse.getPlannedSubtotal())
                .isEqualByComparingTo("190000.00");
        assertThat(lineResponse.getActualSubtotal())
                .isEqualByComparingTo("280000.00");
        assertThat(lineResponse.getAppliedPackage())
                .isEqualTo(StayPackage.DAILY);
        assertThat(lineResponse.getPricingSnapshotHash())
                .isEqualTo("latest");
        assertThat(lineResponse.getMaxGuestsPerRoom()).isEqualTo(3);
    }

    @Test
    void legacyKeepsExistingEarlyLateAndAdditionalFeeSemantics() {
        Reservation reservation = Reservation.builder()
                .pricingVersion(PricingAlgorithmVersion.LEGACY_V1)
                .totalAmount(money("100000"))
                .lateCheckoutFee(money("20000"))
                .earlyCheckoutAdjustment(money("0"))
                .checkoutAdditionalFee(money("10000"))
                .build();
        reservation.setId(42L);
        ReservationResponse response = ReservationResponse.builder()
                .addOnServiceAmount(money("15000"))
                .build();

        ReservationResponse result =
                service.enrich(reservation, response);

        assertThat(result.getPlannedTotalAmount())
                .isEqualByComparingTo("70000.00");
        assertThat(result.getActualTotalAmount())
                .isEqualByComparingTo("100000.00");
        assertThat(result.getPlannedRoomCharge())
                .isEqualByComparingTo("55000.00");
        assertThat(result.getActualRoomCharge())
                .isEqualByComparingTo("55000.00");
        assertThat(result.getPostCommitmentRoomIncrease())
                .isEqualByComparingTo("20000.00");
    }

    @Test
    void checkedInV2AddsReadOnlyCurrentProjectionPerReservationAndLine() {
        Reservation reservation = Reservation.builder()
                .pricingVersion(
                        PricingAlgorithmVersion.MOTEL_PACKAGE_V2)
                .status(ReservationStatus.CHECKED_IN)
                .totalAmount(money("70000"))
                .discountAmount(money("0"))
                .taxAmount(money("0"))
                .build();
        reservation.setId(43L);
        ReservationRoomType reservationLine =
                ReservationRoomType.builder().build();
        reservationLine.setId(53L);
        ReservationRateSnapshot commitment =
                ReservationRateSnapshot.builder()
                        .reservationRoomType(reservationLine)
                        .snapshotSequence(1)
                        .snapshotStage(RateSnapshotStage.COMMITMENT)
                        .finalRoomCharge(money("70000"))
                        .extraGuestCharge(money("0"))
                        .allocatedServiceCharge(money("0"))
                        .appliedPackage(StayPackage.HOURLY)
                        .snapshotHash("commitment")
                        .build();
        when(snapshotRepository
                .findByReservationIdOrderByLineAndSequence(43L))
                .thenReturn(List.of(commitment));

        PricingBreakdown breakdown =
                mock(PricingBreakdown.class);
        when(breakdown.appliedPackage())
                .thenReturn(StayPackage.OVERNIGHT);
        PricingV2LifecycleService.LineProjection lineProjection =
                new PricingV2LifecycleService.LineProjection(
                        reservationLine,
                        commitment,
                        commitment,
                        1,
                        breakdown,
                        money("90000"),
                        money("50000"),
                        money("70000"));
        when(pricingV2LifecycleService.project(
                eq(reservation), any(LocalDateTime.class)))
                .thenReturn(new PricingV2LifecycleService.Projection(
                        LocalDateTime.now().plusHours(1),
                        LocalDateTime.now().plusHours(2),
                        money("140000"),
                        money("70000"),
                        money("90000"),
                        money("50000"),
                        money("0"),
                        money("20000"),
                        money("70000"),
                        StayPackage.OVERNIGHT,
                        List.of(lineProjection)));

        ReservationRoomTypeResponse responseLine =
                ReservationRoomTypeResponse.builder()
                        .id(53L)
                        .build();
        ReservationResponse result = service.enrich(
                reservation,
                ReservationResponse.builder()
                        .roomTypes(List.of(responseLine))
                        .addOnServiceAmount(money("0"))
                        .build());

        assertThat(result.getActualTotalAmount())
                .isEqualByComparingTo("70000.00");
        assertThat(result.getProjectedTotalAmount())
                .isEqualByComparingTo("140000.00");
        assertThat(result.getProjectedRoomCharge())
                .isEqualByComparingTo("90000.00");
        assertThat(result.getProjectedExtraGuestCharge())
                .isEqualByComparingTo("50000.00");
        assertThat(result.getPricingProjectedAt()).isNotNull();
        assertThat(responseLine.getProjectedSubtotal())
                .isEqualByComparingTo("140000.00");
        assertThat(responseLine.getProjectedPackage())
                .isEqualTo(StayPackage.OVERNIGHT);
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
