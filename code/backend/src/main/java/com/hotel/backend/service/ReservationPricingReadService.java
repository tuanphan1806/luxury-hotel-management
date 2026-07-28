package com.hotel.backend.service;

import com.hotel.backend.constant.PricingAlgorithmVersion;
import com.hotel.backend.constant.RateSnapshotStage;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.dto.response.ReservationRoomTypeResponse;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.ReservationRateSnapshot;
import com.hotel.backend.repository.ReservationRateSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the financial read model of a reservation without mutating pricing,
 * lifecycle state, payment, refund or ledger data.
 *
 * <p>Pricing V2 always reads the immutable commitment and latest line
 * snapshots. It never reconstructs a planned amount by subtracting selected
 * fees from the current total because that loses extra-guest and service
 * repricing deltas after an extension.</p>
 */
@Service
@RequiredArgsConstructor
public class ReservationPricingReadService {

    private final ReservationRateSnapshotRepository snapshotRepository;
    private final PricingV2LifecycleService pricingV2LifecycleService;

    @Transactional(readOnly = true)
    public ReservationResponse enrich(
            Reservation reservation,
            ReservationResponse response) {
        response.setActualTotalAmount(money(reservation.getTotalAmount()));
        if (reservation.getPricingVersion()
                != PricingAlgorithmVersion.MOTEL_PACKAGE_V2) {
            enrichLegacy(reservation, response);
            return response;
        }

        List<ReservationRateSnapshot> snapshots = snapshotRepository
                .findByReservationIdOrderByLineAndSequence(
                        reservation.getId());
        Map<Long, SnapshotPair> byLine = groupSnapshots(snapshots);
        if (byLine.isEmpty()) {
            throw new IllegalStateException(
                    "Reservation Pricing V2 thiếu snapshot giá");
        }

        BigDecimal plannedRoom = zero();
        BigDecimal actualRoom = zero();
        BigDecimal plannedExtraGuest = zero();
        BigDecimal actualExtraGuest = zero();
        BigDecimal plannedServices = zero();
        for (SnapshotPair pair : byLine.values()) {
            plannedRoom = plannedRoom.add(
                    money(pair.commitment().getFinalRoomCharge()));
            actualRoom = actualRoom.add(
                    money(pair.latest().getFinalRoomCharge()));
            plannedExtraGuest = plannedExtraGuest.add(
                    money(pair.commitment().getExtraGuestCharge()));
            actualExtraGuest = actualExtraGuest.add(
                    money(pair.latest().getExtraGuestCharge()));
            plannedServices = plannedServices.add(
                    money(pair.commitment().getAllocatedServiceCharge()));
        }

        BigDecimal plannedTotal = plannedRoom
                .add(plannedExtraGuest)
                .add(plannedServices)
                .subtract(money(reservation.getDiscountAmount()))
                .add(money(reservation.getTaxAmount()));
        response.setPlannedTotalAmount(plannedTotal);
        response.setPlannedRoomCharge(plannedRoom);
        response.setActualRoomCharge(actualRoom);
        response.setPlannedExtraGuestCharge(plannedExtraGuest);
        response.setExtraGuestCharge(actualExtraGuest);
        response.setPostCommitmentRoomIncrease(
                actualRoom.subtract(plannedRoom).max(zero()));
        response.setPlannedAddOnServiceAmount(plannedServices);
        enrichRoomLines(response.getRoomTypes(), byLine);
        enrichCurrentProjection(reservation, response);
        return response;
    }

    /**
     * Shows an up-to-date operational estimate without mutating the
     * reservation, snapshots, ledger or reconciliation state. The checkout
     * transaction still replays the engine and remains the only final source.
     */
    private void enrichCurrentProjection(
            Reservation reservation,
            ReservationResponse response) {
        if (reservation.getStatus() != ReservationStatus.CHECKED_IN) {
            return;
        }
        LocalDateTime projectedAt = LocalDateTime.now();
        PricingV2LifecycleService.Projection projection =
                pricingV2LifecycleService.project(
                        reservation, projectedAt);
        response.setProjectedTotalAmount(
                projection.projectedTotalAmount());
        response.setProjectedRoomCharge(
                projection.actualRoomCharge());
        response.setProjectedExtraGuestCharge(
                projection.extraGuestCharge());
        response.setPricingProjectedAt(projectedAt);

        if (response.getRoomTypes() == null) {
            return;
        }
        Map<Long, PricingV2LifecycleService.LineProjection>
                projectionByLine = new LinkedHashMap<>();
        for (PricingV2LifecycleService.LineProjection line
                : projection.lines()) {
            projectionByLine.put(
                    line.reservationLine().getId(), line);
        }
        for (ReservationRoomTypeResponse line : response.getRoomTypes()) {
            PricingV2LifecycleService.LineProjection projected =
                    projectionByLine.get(line.getId());
            if (projected == null) {
                throw new IllegalStateException(
                        "Thiếu projection giá cho dòng phòng "
                                + line.getId());
            }
            BigDecimal projectedRoom =
                    money(projected.finalRoomCharge());
            BigDecimal projectedExtra =
                    money(projected.finalExtraGuestCharge());
            line.setProjectedRoomCharge(projectedRoom);
            line.setProjectedExtraGuestCharge(projectedExtra);
            line.setProjectedSubtotal(
                    projectedRoom.add(projectedExtra));
            line.setProjectedPackage(
                    projected.breakdown().appliedPackage());
        }
    }

    private void enrichLegacy(
            Reservation reservation,
            ReservationResponse response) {
        BigDecimal late = money(reservation.getLateCheckoutFee());
        BigDecimal early = money(reservation.getEarlyCheckoutAdjustment());
        BigDecimal additional =
                money(reservation.getCheckoutAdditionalFee());
        BigDecimal services = money(response.getAddOnServiceAmount());
        BigDecimal currentRoom = money(reservation.getTotalAmount())
                .subtract(late)
                .subtract(additional)
                .subtract(services)
                .max(zero());
        BigDecimal plannedRoom = currentRoom.add(early);

        response.setPlannedTotalAmount(
                money(reservation.getTotalAmount())
                        .add(early)
                        .subtract(late)
                        .subtract(additional));
        response.setPlannedRoomCharge(plannedRoom);
        response.setActualRoomCharge(currentRoom);
        response.setPlannedExtraGuestCharge(zero());
        response.setExtraGuestCharge(zero());
        response.setPostCommitmentRoomIncrease(late);
        response.setPlannedAddOnServiceAmount(services);

        if (response.getRoomTypes() == null) {
            return;
        }
        for (ReservationRoomTypeResponse line : response.getRoomTypes()) {
            BigDecimal actualLine = money(line.getSubtotal());
            line.setPlannedRoomCharge(actualLine);
            line.setActualRoomCharge(actualLine);
            line.setPlannedExtraGuestCharge(zero());
            line.setExtraGuestCharge(zero());
            line.setPlannedSubtotal(actualLine);
            line.setActualSubtotal(actualLine);
        }
    }

    private Map<Long, SnapshotPair> groupSnapshots(
            List<ReservationRateSnapshot> snapshots) {
        Map<Long, SnapshotPair> result = new LinkedHashMap<>();
        for (ReservationRateSnapshot snapshot : snapshots) {
            Long lineId = snapshot.getReservationRoomType().getId();
            SnapshotPair current = result.get(lineId);
            if (current == null) {
                if (snapshot.getSnapshotStage()
                        != RateSnapshotStage.COMMITMENT) {
                    throw new IllegalStateException(
                            "Snapshot đầu tiên không phải COMMITMENT cho dòng "
                                    + lineId);
                }
                result.put(lineId, new SnapshotPair(snapshot, snapshot));
            } else {
                result.put(lineId, new SnapshotPair(
                        current.commitment(), snapshot));
            }
        }
        return result;
    }

    private void enrichRoomLines(
            List<ReservationRoomTypeResponse> responseLines,
            Map<Long, SnapshotPair> snapshots) {
        if (responseLines == null) {
            return;
        }
        for (ReservationRoomTypeResponse line : responseLines) {
            SnapshotPair pair = snapshots.get(line.getId());
            if (pair == null) {
                throw new IllegalStateException(
                        "Thiếu snapshot giá cho dòng phòng " + line.getId());
            }
            BigDecimal plannedRoom =
                    money(pair.commitment().getFinalRoomCharge());
            BigDecimal actualRoom =
                    money(pair.latest().getFinalRoomCharge());
            BigDecimal plannedExtra =
                    money(pair.commitment().getExtraGuestCharge());
            BigDecimal actualExtra =
                    money(pair.latest().getExtraGuestCharge());
            line.setPlannedRoomCharge(plannedRoom);
            line.setActualRoomCharge(actualRoom);
            line.setPlannedExtraGuestCharge(plannedExtra);
            line.setExtraGuestCharge(actualExtra);
            line.setPlannedSubtotal(
                    plannedRoom.add(plannedExtra));
            line.setActualSubtotal(
                    actualRoom.add(actualExtra));
            line.setAppliedPackage(pair.latest().getAppliedPackage());
            line.setPricingSnapshotHash(
                    pair.latest().getSnapshotHash());
        }
    }

    private BigDecimal money(BigDecimal value) {
        return value != null ? value.setScale(2) : zero();
    }

    private BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private record SnapshotPair(
            ReservationRateSnapshot commitment,
            ReservationRateSnapshot latest) {
    }
}
