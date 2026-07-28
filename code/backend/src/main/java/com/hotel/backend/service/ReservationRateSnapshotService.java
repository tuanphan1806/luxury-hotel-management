package com.hotel.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.constant.PricingAlgorithmVersion;
import com.hotel.backend.constant.RateSnapshotStage;
import com.hotel.backend.constant.StayPackage;
import com.hotel.backend.entity.*;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.pricing.PricingBreakdown;
import com.hotel.backend.pricing.PricingCycleBreakdown;
import com.hotel.backend.repository.ReservationRateSnapshotRepository;
import com.hotel.backend.util.CanonicalJsonHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Appends line-level financial evidence. Invoice code can later consume the
 * latest applicable snapshot without recalculating against mutable catalogue
 * state.
 */
@Service
@RequiredArgsConstructor
public class ReservationRateSnapshotService {

    private final ReservationRateSnapshotRepository snapshotRepository;
    private final CanonicalJsonHasher jsonHasher;

    @Transactional(propagation = Propagation.MANDATORY)
    public void createCommitmentSnapshots(
            Reservation reservation,
            Map<Long, ReservationRoomType> reservationLines,
            PricingQuoteCommitmentService.Commitment commitment) {
        List<SnapshotLine> lines = commitment.lines().stream()
                .map(line -> new SnapshotLine(
                        line.roomType(),
                        line.rateProfile(),
                        line.quantity(),
                        line.lineGuestCount(),
                        line.breakdown()))
                .toList();
        createSnapshots(
                reservation,
                reservationLines,
                lines,
                commitment.serviceCharge());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void createWalkInSnapshots(
            Reservation reservation,
            Map<Long, ReservationRoomType> reservationLines,
            WalkInPricingService.Calculation calculation,
            BigDecimal serviceCharge) {
        List<SnapshotLine> lines = calculation.lines().stream()
                .map(line -> new SnapshotLine(
                        line.roomType(),
                        line.rateProfile(),
                        line.quantity(),
                        line.lineGuestCount(),
                        line.breakdown()))
                .toList();
        createSnapshots(
                reservation,
                reservationLines,
                lines,
                serviceCharge);
    }

    private void createSnapshots(
            Reservation reservation,
            Map<Long, ReservationRoomType> reservationLines,
            List<SnapshotLine> lines,
            BigDecimal serviceCharge) {
        List<BigDecimal> serviceAllocations = allocateServiceCharge(
                lines, serviceCharge);
        Instant createdAt = Instant.now();

        for (int index = 0; index < lines.size(); index++) {
            SnapshotLine line = lines.get(index);
            ReservationRoomType reservationLine =
                    reservationLines.get(line.roomType().getId());
            if (reservationLine == null) {
                throw new AppException(
                        ErrorCode.PRICING_QUOTE_MISMATCH,
                        "Không thể liên kết snapshot với hạng phòng đã đặt");
            }

            RoomRateProfile rate = line.rateProfile();
            StayPolicyVersion policy = rate.getStayPolicyVersion();
            PricingBreakdown breakdown = line.breakdown();
            BigDecimal allocatedServiceCharge = serviceAllocations.get(index);
            JsonNode breakdownJson = jsonHasher.canonicalTree(breakdown);
            String snapshotHash = jsonHasher.hash(snapshotEvidence(
                    reservation,
                    reservationLine,
                    rate,
                    policy,
                    breakdown,
                    allocatedServiceCharge,
                    breakdownJson));

            snapshotRepository.save(ReservationRateSnapshot.builder()
                    .reservationRoomType(reservationLine)
                    .snapshotSequence(1)
                    .snapshotStage(RateSnapshotStage.COMMITMENT)
                    .stayPolicyVersion(policy)
                    .rateProfile(rate)
                    .pricingAlgorithmVersion(PricingAlgorithmVersion.MOTEL_PACKAGE_V2)
                    .committedCheckIn(reservation.getCheckIn())
                    .committedCheckOut(reservation.getCheckOut())
                    .stayClassification(breakdown.stayClassification())
                    .initialPackage(breakdown.cycles().get(0).appliedPackage())
                    .appliedPackage(breakdown.appliedPackage())
                    .maxPackageReached(breakdown.appliedPackage())
                    .transitionReason(breakdown.transitionReason())
                    .includedGuests(rate.getIncludedGuests())
                    .maxGuestsSnapshot(safeMaxGuests(line.roomType()))
                    .lineGuestCount(line.lineGuestCount())
                    .roomQuantity(line.quantity())
                    .extraGuestCount(breakdown.extraGuestCount())
                    .firstBlockMinutes(rate.getFirstBlockMinutes())
                    .firstBlockPrice(rate.getFirstBlockPrice())
                    .extraUnitMinutes(rate.getExtraUnitMinutes())
                    .extraUnitPrice(rate.getExtraUnitPrice())
                    .graceMinutes(policy.getGraceMinutes())
                    .overnightPrice(rate.getOvernightPrice())
                    .overnightIncludedCheckout(
                            overnightIncludedCheckout(breakdown))
                    .dailyPrice(rate.getDailyPrice())
                    .dailyDurationMinutes(policy.getDailyDurationMinutes())
                    .fullDays(breakdown.fullDays())
                    .remainderMinutes(breakdown.remainderMinutes())
                    .chargedExtraUnits(breakdown.chargedExtraUnits())
                    .minimumCommittedRoomCharge(breakdown.roomCharge())
                    .finalRoomCharge(breakdown.roomCharge())
                    .extraGuestCharge(breakdown.extraGuestCharge())
                    .allocatedServiceCharge(allocatedServiceCharge)
                    .adjustmentAmount(BigDecimal.ZERO.setScale(2))
                    .breakdownJson(breakdownJson)
                    .snapshotHash(snapshotHash)
                    .createdAtUtc(createdAt)
                    .build());
        }
    }

    private List<BigDecimal> allocateServiceCharge(
            List<SnapshotLine> lines,
            BigDecimal serviceCharge) {
        if (lines.isEmpty()) {
            return List.of();
        }
        BigDecimal safeServiceCharge = money(serviceCharge);
        if (safeServiceCharge.signum() == 0) {
            return Collections.nCopies(lines.size(), BigDecimal.ZERO.setScale(2));
        }
        BigDecimal allocationBase = lines.stream()
                .map(line -> line.breakdown().lineTotalBeforeServices())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (allocationBase.signum() <= 0) {
            throw new AppException(
                    ErrorCode.PRICING_QUOTE_MISMATCH,
                    "Không thể phân bổ phí dịch vụ vào các dòng phòng");
        }

        List<BigDecimal> allocations = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO.setScale(2);
        for (int index = 0; index < lines.size(); index++) {
            BigDecimal amount;
            if (index == lines.size() - 1) {
                amount = safeServiceCharge.subtract(allocated);
            } else {
                amount = safeServiceCharge
                        .multiply(lines.get(index).breakdown()
                                .lineTotalBeforeServices())
                        .divide(allocationBase, 2, RoundingMode.DOWN);
                allocated = allocated.add(amount);
            }
            allocations.add(amount.setScale(2));
        }
        return List.copyOf(allocations);
    }

    private LocalDateTime overnightIncludedCheckout(
            PricingBreakdown breakdown) {
        return breakdown.cycles().stream()
                .filter(cycle -> cycle.appliedPackage() == StayPackage.OVERNIGHT)
                .map(PricingCycleBreakdown::packageIncludedCheckout)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    private Map<String, Object> snapshotEvidence(
            Reservation reservation,
            ReservationRoomType reservationLine,
            RoomRateProfile rate,
            StayPolicyVersion policy,
            PricingBreakdown breakdown,
            BigDecimal allocatedServiceCharge,
            JsonNode breakdownJson) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("reservationId", reservation.getId());
        evidence.put("reservationRoomTypeId", reservationLine.getId());
        evidence.put("snapshotSequence", 1);
        evidence.put("snapshotStage", RateSnapshotStage.COMMITMENT);
        evidence.put("pricingAlgorithmVersion", PricingAlgorithmVersion.MOTEL_PACKAGE_V2);
        evidence.put("stayPolicyVersionId", policy.getId());
        evidence.put("rateProfileId", rate.getId());
        evidence.put("committedCheckIn", reservation.getCheckIn());
        evidence.put("committedCheckOut", reservation.getCheckOut());
        evidence.put("includedGuests", rate.getIncludedGuests());
        evidence.put("maxGuests", safeMaxGuests(reservationLine.getRoomType()));
        evidence.put("lineGuestCount", reservationLine.getLineGuestCount());
        evidence.put("roomQuantity", reservationLine.getQuantity());
        evidence.put("roomCharge", breakdown.roomCharge());
        evidence.put("extraGuestCharge", breakdown.extraGuestCharge());
        evidence.put("allocatedServiceCharge", allocatedServiceCharge);
        evidence.put("breakdown", breakdownJson);
        return evidence;
    }

    private int safeMaxGuests(RoomType roomType) {
        return roomType.getMaxGuests() != null
                ? Math.max(1, roomType.getMaxGuests())
                : 2;
    }

    private BigDecimal money(BigDecimal value) {
        return value != null
                ? value.setScale(2)
                : BigDecimal.ZERO.setScale(2);
    }

    private record SnapshotLine(
            RoomType roomType,
            RoomRateProfile rateProfile,
            int quantity,
            int lineGuestCount,
            PricingBreakdown breakdown) {
    }
}
