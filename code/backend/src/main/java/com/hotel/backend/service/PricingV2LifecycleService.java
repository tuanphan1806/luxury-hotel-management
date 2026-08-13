package com.hotel.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.constant.*;
import com.hotel.backend.entity.*;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.pricing.*;
import com.hotel.backend.repository.ReservationRateSnapshotRepository;
import com.hotel.backend.repository.ReservationRoomTypeRepository;
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
 * Replays Pricing V2 from immutable committed rate versions.
 *
 * <p>It never consults the currently-open catalogue rate. Preview is read-only;
 * mutations append a new evidence snapshot while the caller holds the
 * reservation lock. An actual early checkout may lower hourly/daily usage and
 * an overnight stay before its versioned refund-lock boundary.</p>
 */
@Service
@RequiredArgsConstructor
public class PricingV2LifecycleService {

    private final PricingEngine pricingEngine;
    private final PricingDefinitionFactory definitionFactory;
    private final PricingQuoteAggregates aggregates;
    private final ReservationRoomTypeRepository reservationRoomTypeRepository;
    private final ReservationRateSnapshotRepository snapshotRepository;
    private final CanonicalJsonHasher jsonHasher;

    public boolean supports(Reservation reservation) {
        return reservation != null
                && reservation.getPricingVersion()
                == PricingAlgorithmVersion.MOTEL_PACKAGE_V2;
    }

    @Transactional(readOnly = true)
    public Projection project(
            Reservation reservation,
            LocalDateTime requestedCheckout) {
        return project(
                reservation, requestedCheckout, Map.of(), null, null);
    }

    /**
     * Read-model variant that reuses room lines and immutable snapshots loaded
     * for a reservation page. Mutation paths continue to use the locking
     * repository-backed variant above.
     */
    @Transactional(readOnly = true)
    public Projection project(
            Reservation reservation,
            LocalDateTime requestedCheckout,
            List<ReservationRoomType> preloadedRoomTypes,
            List<ReservationRateSnapshot> preloadedSnapshots) {
        return project(reservation, requestedCheckout, Map.of(),
                preloadedRoomTypes, preloadedSnapshots);
    }

    /**
     * Exposes the room engine's authoritative package-cycle count to adjacent
     * financial modules such as add-on pricing. No second stay classifier is
     * allowed to infer the count independently.
     */
    @Transactional(readOnly = true)
    public int packageCycles(
            Reservation reservation,
            LocalDateTime requestedCheckout) {
        return project(reservation, requestedCheckout).packageCycles();
    }

    private Projection project(
            Reservation reservation,
            LocalDateTime requestedCheckout,
            Map<Long, Integer> lineGuestCountOverrides) {
        return project(reservation, requestedCheckout,
                lineGuestCountOverrides, null, null, false);
    }

    private Projection project(
            Reservation reservation,
            LocalDateTime requestedCheckout,
            Map<Long, Integer> lineGuestCountOverrides,
            List<ReservationRoomType> preloadedRoomTypes,
            List<ReservationRateSnapshot> preloadedSnapshots) {
        return project(
                reservation,
                requestedCheckout,
                lineGuestCountOverrides,
                preloadedRoomTypes,
                preloadedSnapshots,
                false);
    }

    private Projection project(
            Reservation reservation,
            LocalDateTime requestedCheckout,
            Map<Long, Integer> lineGuestCountOverrides,
            List<ReservationRoomType> preloadedRoomTypes,
            List<ReservationRateSnapshot> preloadedSnapshots,
            boolean allowExtraGuestCorrection) {
        requireV2(reservation);
        if (requestedCheckout == null) {
            throw new AppException(
                    ErrorCode.RESERVATION_INVALID_DATE);
        }

        boolean earlyCheckout = reservation.getStatus()
                == ReservationStatus.CHECKED_IN
                && reservation.getActualCheckIn() != null
                && requestedCheckout.isBefore(reservation.getCheckOut());
        LocalDateTime pricingCheckout;
        if (earlyCheckout) {
            // A reconciliation may be opened in the same clock tick as
            // check-in. Keep the engine interval valid without inventing a
            // billable minute; the engine still applies its normal minimum.
            pricingCheckout = requestedCheckout.isAfter(
                    reservation.getActualCheckIn())
                    ? requestedCheckout
                    : reservation.getActualCheckIn().plusNanos(1);
        } else {
            pricingCheckout = requestedCheckout.isAfter(
                    reservation.getCheckOut())
                    ? requestedCheckout
                    : reservation.getCheckOut();
        }
        List<ReservationRoomType> reservationLines = preloadedRoomTypes != null
                ? preloadedRoomTypes
                : reservationRoomTypeRepository.findDetailsByReservationId(
                        reservation.getId());
        if (reservationLines.isEmpty()) {
            throw missingSnapshot();
        }

        Map<Long, List<ReservationRateSnapshot>> snapshotsByLine = null;
        if (preloadedSnapshots != null) {
            snapshotsByLine = new LinkedHashMap<>();
            for (ReservationRateSnapshot snapshot : preloadedSnapshots) {
                snapshotsByLine.computeIfAbsent(
                                snapshot.getReservationRoomType().getId(),
                                ignored -> new ArrayList<>())
                        .add(snapshot);
            }
        }

        List<LineProjection> projectedLines = new ArrayList<>();
        BigDecimal currentBase = money(BigDecimal.ZERO);
        BigDecimal projectedBase = money(BigDecimal.ZERO);
        BigDecimal committedBase = money(BigDecimal.ZERO);
        BigDecimal plannedRoomCharge = money(BigDecimal.ZERO);
        BigDecimal reductionReferenceRoomCharge = money(BigDecimal.ZERO);
        BigDecimal projectedRoomCharge = money(BigDecimal.ZERO);
        BigDecimal projectedExtraGuestCharge = money(BigDecimal.ZERO);
        StayPolicyVersion commonPolicy = null;
        Set<Long> matchedGuestOverrideLines = new HashSet<>();

        for (ReservationRoomType reservationLine : reservationLines) {
            List<ReservationRateSnapshot> snapshots = snapshotsByLine != null
                    ? snapshotsByLine.getOrDefault(
                            reservationLine.getId(), List.of())
                    : snapshotRepository
                    .findByReservationRoomTypeIdOrderBySnapshotSequenceAsc(
                            reservationLine.getId());
            if (snapshots.isEmpty()) {
                throw missingSnapshot();
            }
            ReservationRateSnapshot commitment = snapshots.get(0);
            ReservationRateSnapshot latest =
                    snapshots.get(snapshots.size() - 1);
            if (commitment.getSnapshotStage()
                    != RateSnapshotStage.COMMITMENT) {
                throw missingSnapshot();
            }

            RoomRateProfile rate = latest.getRateProfile();
            StayPolicyVersion policy = latest.getStayPolicyVersion();
            if (commonPolicy == null) {
                commonPolicy = policy;
            } else if (!Objects.equals(
                    commonPolicy.getId(), policy.getId())) {
                throw new AppException(
                        ErrorCode.PRICING_QUOTE_MISMATCH,
                        "Các dòng phòng không dùng cùng chính sách lưu trú");
            }

            PricingBreakdown recalculated;
            Integer overriddenGuestCount =
                    lineGuestCountOverrides.get(reservationLine.getId());
            int effectiveLineGuestCount = overriddenGuestCount != null
                    ? overriddenGuestCount
                    : latest.getLineGuestCount();
            int maximumLineGuests = Math.multiplyExact(
                    latest.getMaxGuestsSnapshot(),
                    latest.getRoomQuantity());
            if (effectiveLineGuestCount < latest.getRoomQuantity()
                    || effectiveLineGuestCount > maximumLineGuests) {
                throw new AppException(
                        ErrorCode.INVALID_REQUEST,
                        "Mỗi phòng phải có ít nhất một khách và số khách thực tế "
                                + "không được vượt sức chứa của hạng phòng");
            }
            if (overriddenGuestCount != null) {
                matchedGuestOverrideLines.add(
                        reservationLine.getId());
            }
            LocalDateTime pricingStart =
                    reservation.getActualCheckIn() != null
                            ? reservation.getActualCheckIn()
                            : commitment.getCommittedCheckIn();
            if (!pricingCheckout.isAfter(pricingStart)) {
                throw new AppException(
                        ErrorCode.RESERVATION_INVALID_DATE);
            }
            try {
                recalculated = pricingEngine.calculate(
                        new PricingRequest(
                                pricingStart,
                                pricingCheckout,
                                latest.getRoomQuantity(),
                                effectiveLineGuestCount),
                        definitionFactory.roomRate(rate),
                        definitionFactory.stayPolicy(policy));
            } catch (IllegalArgumentException exception) {
                throw new AppException(
                        ErrorCode.INVALID_REQUEST, exception.getMessage());
            }

            boolean overnightFloorApplies = earlyCheckout
                    && commitment.getInitialPackage() == StayPackage.OVERNIGHT
                    && overnightRefundFloorApplies(
                            commitment, policy, pricingCheckout);
            BigDecimal finalRoomCharge;
            BigDecimal finalExtraGuestCharge;
            if (earlyCheckout && !overnightFloorApplies) {
                // Hourly, rolling-day and pre-23:00 overnight departures are
                // authoritative actual-usage reprices and may create a refund.
                finalRoomCharge = money(recalculated.roomCharge());
                finalExtraGuestCharge = money(
                        recalculated.extraGuestCharge());
            } else if (overnightFloorApplies) {
                // Once the operational night reaches the lock boundary, only
                // the overnight package itself is non-refundable. Unused
                // future extension units are not retained as a second floor.
                finalRoomCharge = maxMoney(
                        commitment.getMinimumCommittedRoomCharge(),
                        recalculated.roomCharge());
                finalExtraGuestCharge = maxMoney(
                        commitment.getExtraGuestCharge(),
                        recalculated.extraGuestCharge());
            } else {
                // Check-in and non-early/late transitions must never lower the
                // currently committed obligation.
                finalRoomCharge = maxMoney(
                        latest.getFinalRoomCharge(),
                        commitment.getMinimumCommittedRoomCharge(),
                        recalculated.roomCharge());
                finalExtraGuestCharge = allowExtraGuestCorrection
                        ? money(recalculated.extraGuestCharge())
                        : maxMoney(
                                latest.getExtraGuestCharge(),
                                recalculated.extraGuestCharge());
            }
            BigDecimal latestBase = money(latest.getFinalRoomCharge())
                    .add(money(latest.getExtraGuestCharge()));
            BigDecimal nextBase = finalRoomCharge
                    .add(finalExtraGuestCharge);
            BigDecimal initialBase =
                    money(commitment.getFinalRoomCharge())
                            .add(money(
                                    commitment.getExtraGuestCharge()));

            currentBase = currentBase.add(latestBase);
            projectedBase = projectedBase.add(nextBase);
            committedBase = committedBase.add(initialBase);
            plannedRoomCharge = plannedRoomCharge.add(
                    money(commitment.getFinalRoomCharge()));
            BigDecimal lineReductionReference = snapshots.stream()
                    .map(ReservationRateSnapshot::getFinalRoomCharge)
                    .map(this::money)
                    .max(BigDecimal::compareTo)
                    .orElseGet(() -> money(
                            commitment.getFinalRoomCharge()));
            reductionReferenceRoomCharge =
                    reductionReferenceRoomCharge.add(
                            lineReductionReference);
            projectedRoomCharge = projectedRoomCharge.add(
                    finalRoomCharge);
            projectedExtraGuestCharge =
                    projectedExtraGuestCharge.add(
                            finalExtraGuestCharge);
            projectedLines.add(new LineProjection(
                    reservationLine,
                    commitment,
                    latest,
                    effectiveLineGuestCount,
                    recalculated,
                    finalRoomCharge,
                    finalExtraGuestCharge,
                    nextBase.subtract(latestBase)));
        }

        if (matchedGuestOverrideLines.size()
                != lineGuestCountOverrides.size()) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "Danh sách phân bổ khách chứa dòng phòng không thuộc reservation");
        }

        StayPolicyVersion policy = Objects.requireNonNull(commonPolicy);
        List<PricingBreakdown> breakdowns = projectedLines.stream()
                .map(LineProjection::breakdown)
                .toList();
        LocalDateTime protectedUntil = aggregates.inventoryProtectedUntil(
                pricingCheckout, breakdowns, policy);
        if (reservation.getInventoryProtectedUntil() != null
                && reservation.getInventoryProtectedUntil()
                        .isAfter(protectedUntil)) {
            protectedUntil = reservation.getInventoryProtectedUntil();
        }

        BigDecimal delta = projectedBase.subtract(currentBase);
        BigDecimal projectedTotal =
                money(reservation.getTotalAmount()).add(delta);
        // The legacy field lateCheckoutFee remains a room-price adjustment.
        // Extra-guest obligations are snapshotted and invoiced separately so
        // they are never hidden inside a misleading "late checkout" label.
        BigDecimal cumulativeIncrease =
                projectedRoomCharge.subtract(plannedRoomCharge).max(
                        money(BigDecimal.ZERO));
        BigDecimal earlyCheckoutAdjustment = earlyCheckout
                ? reductionReferenceRoomCharge
                        .subtract(projectedRoomCharge)
                        .max(money(BigDecimal.ZERO))
                : money(BigDecimal.ZERO);
        return new Projection(
                pricingCheckout,
                protectedUntil,
                projectedTotal,
                plannedRoomCharge,
                projectedRoomCharge,
                projectedExtraGuestCharge,
                earlyCheckoutAdjustment,
                cumulativeIncrease,
                delta,
                aggregates.displayPackage(breakdowns),
                List.copyOf(projectedLines));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Projection apply(
            Reservation reservation,
            LocalDateTime requestedCheckout,
            RateSnapshotStage stage,
            PricingTransitionReason reason) {
        if (stage == RateSnapshotStage.COMMITMENT
                || reason == null) {
            throw new IllegalArgumentException(
                    "Lifecycle snapshot stage/reason is invalid");
        }
        Projection projection = project(
                reservation, requestedCheckout);
        return applyProjection(
                reservation,
                requestedCheckout,
                stage,
                reason,
                projection);
    }

    /**
     * Applies an automatic lifecycle reprice without inventing a manual
     * adjustment reason. The reason is derived from the engine output stored
     * in the same immutable evidence snapshot.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Projection applyAutomatic(
            Reservation reservation,
            LocalDateTime requestedCheckout,
            RateSnapshotStage stage) {
        return applyAutomatic(
                reservation,
                requestedCheckout,
                stage,
                Map.of());
    }

    /**
     * Check-in variant that prices the real guest distribution per room type.
     * The reservation workflow remains responsible for validating total guests
     * and physical room capacity.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Projection applyAutomatic(
            Reservation reservation,
            LocalDateTime requestedCheckout,
            RateSnapshotStage stage,
            Map<Long, Integer> lineGuestCountOverrides) {
        if (stage == RateSnapshotStage.COMMITMENT
                || stage == RateSnapshotStage.EXTENSION
                || stage == RateSnapshotStage.CHECKOUT) {
            throw new IllegalArgumentException(
                    "Automatic lifecycle stage is invalid");
        }
        Projection projection = project(
                reservation,
                requestedCheckout,
                lineGuestCountOverrides == null
                        ? Map.of()
                        : Map.copyOf(lineGuestCountOverrides));
        return applyProjection(
                reservation,
                requestedCheckout,
                stage,
                automaticReason(projection),
                projection);
    }

    /**
     * Reprices the real distribution after a staff correction moves a guest
     * between physical rooms. Room-price floors remain untouched; only an
     * extra-guest charge that no longer corresponds to the persisted guest
     * distribution may decrease. The caller must hold the reservation lock,
     * validate both rooms and write an audit reason.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Projection applyGuestDistributionCorrection(
            Reservation reservation,
            LocalDateTime requestedCheckout,
            Map<Long, Integer> lineGuestCountOverrides) {
        Projection projection = project(
                reservation,
                requestedCheckout,
                lineGuestCountOverrides == null
                        ? Map.of()
                        : Map.copyOf(lineGuestCountOverrides),
                null,
                null,
                true);
        return applyProjection(
                reservation,
                requestedCheckout,
                RateSnapshotStage.ADJUSTMENT,
                PricingTransitionReason.ADMIN_APPROVED_ADJUSTMENT,
                projection);
    }

    private Projection applyProjection(
            Reservation reservation,
            LocalDateTime requestedCheckout,
            RateSnapshotStage stage,
            PricingTransitionReason reason,
            Projection projection) {
        boolean finalCheckout = stage == RateSnapshotStage.CHECKOUT;
        boolean guestDistributionChanged = projection.lines().stream()
                .anyMatch(line -> line.lineGuestCount()
                        != line.latest().getLineGuestCount());
        boolean appendEvidence = finalCheckout
                || stage == RateSnapshotStage.CHECK_IN
                || stage == RateSnapshotStage.EXTENSION
                || guestDistributionChanged
                || projection.deltaAmount().signum() != 0;

        if (projection.deltaAmount().signum() != 0) {
            reservation.setTotalAmount(
                    projection.projectedTotalAmount());
        }
        reservation.setLateCheckoutFee(
                projection.cumulativePricingIncrease());
        reservation.setEarlyCheckoutAdjustment(
                projection.earlyCheckoutAdjustment());
        reservation.setDisplayPackageSummary(
                projection.displayPackage());
        reservation.setInventoryProtectedUntil(
                projection.inventoryProtectedUntil());

        if (appendEvidence) {
            Instant createdAt = Instant.now();
            for (LineProjection line : projection.lines()) {
                appendSnapshot(
                        reservation,
                        line,
                        stage,
                        reason,
                        finalCheckout ? requestedCheckout : null,
                        createdAt);
                line.reservationLine().setRoomPrice(
                        line.finalRoomCharge().divide(
                                BigDecimal.valueOf(
                                        line.latest()
                                                .getRoomQuantity()),
                                2,
                                RoundingMode.HALF_UP));
                line.reservationLine().setSubtotal(
                        line.finalRoomCharge().add(
                                line.finalExtraGuestCharge()));
                line.reservationLine().setMaxPackageReached(
                        highestPackage(
                                line.latest()
                                        .getMaxPackageReached(),
                                line.breakdown()
                                        .appliedPackage()));
                line.reservationLine().setLineGuestCount(
                        line.lineGuestCount());
                reservationRoomTypeRepository.save(
                        line.reservationLine());
            }
        }
        return projection;
    }

    private PricingTransitionReason automaticReason(
            Projection projection) {
        if (projection.lines().stream()
                .anyMatch(line -> line.breakdown().transitionReason()
                        == PricingTransitionReason.PRICE_CAP)) {
            return PricingTransitionReason.PRICE_CAP;
        }
        return switch (projection.displayPackage()) {
            case HOURLY -> PricingTransitionReason.HOURLY_WINDOW;
            case OVERNIGHT -> PricingTransitionReason.OVERNIGHT_WINDOW;
            case DAILY -> PricingTransitionReason.DAILY_DURATION;
        };
    }

    private void appendSnapshot(
            Reservation reservation,
            LineProjection line,
            RateSnapshotStage stage,
            PricingTransitionReason reason,
            LocalDateTime actualCheckout,
            Instant createdAt) {
        ReservationRateSnapshot previous = line.latest();
        RoomRateProfile rate = previous.getRateProfile();
        StayPolicyVersion policy = previous.getStayPolicyVersion();
        PricingBreakdown breakdown = line.breakdown();
        int sequence = previous.getSnapshotSequence() + 1;
        StayPackage maxPackage = highestPackage(
                previous.getMaxPackageReached(),
                breakdown.appliedPackage());
        JsonNode breakdownJson =
                jsonHasher.canonicalTree(breakdown);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("reservationId", reservation.getId());
        evidence.put(
                "reservationRoomTypeId",
                line.reservationLine().getId());
        evidence.put("snapshotSequence", sequence);
        evidence.put("snapshotStage", stage);
        evidence.put("transitionReason", reason);
        evidence.put(
                "committedCheckIn",
                line.commitment().getCommittedCheckIn());
        evidence.put(
                "committedCheckOut",
                reservation.getCheckOut());
        evidence.put("actualCheckIn", reservation.getActualCheckIn());
        evidence.put("actualCheckOut", actualCheckout);
        evidence.put(
                "finalRoomCharge", line.finalRoomCharge());
        evidence.put(
                "extraGuestCharge",
                line.finalExtraGuestCharge());
        evidence.put("lineGuestCount", line.lineGuestCount());
        evidence.put(
                "adjustmentAmount", line.deltaAmount());
        evidence.put("breakdown", breakdownJson);

        snapshotRepository.save(
                ReservationRateSnapshot.builder()
                        .reservationRoomType(
                                line.reservationLine())
                        .snapshotSequence(sequence)
                        .snapshotStage(stage)
                        .stayPolicyVersion(policy)
                        .rateProfile(rate)
                        .pricingAlgorithmVersion(
                                PricingAlgorithmVersion.MOTEL_PACKAGE_V2)
                        .committedCheckIn(
                                line.commitment()
                                        .getCommittedCheckIn())
                        .committedCheckOut(
                                reservation.getCheckOut())
                        .actualCheckIn(reservation.getActualCheckIn())
                        .actualCheckOut(actualCheckout)
                        .stayClassification(
                                breakdown.stayClassification())
                        .initialPackage(
                                line.commitment()
                                        .getInitialPackage())
                        .appliedPackage(
                                breakdown.appliedPackage())
                        .maxPackageReached(maxPackage)
                        .transitionReason(reason)
                        .includedGuests(
                                previous.getIncludedGuests())
                        .maxGuestsSnapshot(
                                previous.getMaxGuestsSnapshot())
                        .lineGuestCount(
                                line.lineGuestCount())
                        .roomQuantity(
                                previous.getRoomQuantity())
                        .extraGuestCount(
                                breakdown.extraGuestCount())
                        .firstBlockMinutes(
                                previous.getFirstBlockMinutes())
                        .firstBlockPrice(
                                previous.getFirstBlockPrice())
                        .extraUnitMinutes(
                                previous.getExtraUnitMinutes())
                        .extraUnitPrice(
                                previous.getExtraUnitPrice())
                        .graceMinutes(
                                previous.getGraceMinutes())
                        .overnightPrice(
                                previous.getOvernightPrice())
                        .overnightIncludedCheckout(
                                overnightIncludedCheckout(
                                        breakdown))
                        .dailyPrice(previous.getDailyPrice())
                        .dailyDurationMinutes(
                                previous.getDailyDurationMinutes())
                        .fullDays(breakdown.fullDays())
                        .remainderMinutes(
                                breakdown.remainderMinutes())
                        .chargedExtraUnits(
                                breakdown.chargedExtraUnits())
                        .minimumCommittedRoomCharge(
                                line.commitment()
                                        .getMinimumCommittedRoomCharge())
                        .finalRoomCharge(
                                line.finalRoomCharge())
                        .extraGuestCharge(
                                line.finalExtraGuestCharge())
                        .allocatedServiceCharge(
                                previous.getAllocatedServiceCharge())
                        .adjustmentAmount(line.deltaAmount())
                        .breakdownJson(breakdownJson)
                        .snapshotHash(jsonHasher.hash(evidence))
                        .createdAtUtc(createdAt)
                        .build());
    }

    private LocalDateTime overnightIncludedCheckout(
            PricingBreakdown breakdown) {
        return breakdown.cycles().stream()
                .filter(cycle -> cycle.appliedPackage()
                        == StayPackage.OVERNIGHT)
                .map(PricingCycleBreakdown::packageIncludedCheckout)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    private boolean overnightRefundFloorApplies(
            ReservationRateSnapshot commitment,
            StayPolicyVersion policy,
            LocalDateTime actualCheckout) {
        LocalDateTime committedCheckIn =
                commitment.getCommittedCheckIn();
        if (committedCheckIn == null) {
            throw missingSnapshot();
        }
        LocalDateTime refundLock = committedCheckIn
                .toLocalDate()
                .minusDays(committedCheckIn.toLocalTime().isBefore(
                        policy.getOvernightEarlyMorningEnd()) ? 1L : 0L)
                .atTime(policy.getOvernightRefundLockTime());
        return !actualCheckout.isBefore(refundLock);
    }

    private StayPackage highestPackage(
            StayPackage left, StayPackage right) {
        if (left == null) {
            return Objects.requireNonNull(right);
        }
        if (right == null) {
            return left;
        }
        return packageRank(right) > packageRank(left)
                ? right
                : left;
    }

    private int packageRank(StayPackage value) {
        return switch (value) {
            case HOURLY -> 0;
            case OVERNIGHT -> 1;
            case DAILY -> 2;
        };
    }

    private BigDecimal maxMoney(BigDecimal... values) {
        return Arrays.stream(values)
                .filter(Objects::nonNull)
                .map(this::money)
                .max(BigDecimal::compareTo)
                .orElseGet(() -> money(BigDecimal.ZERO));
    }

    private BigDecimal money(BigDecimal value) {
        return value != null
                ? value.setScale(2)
                : BigDecimal.ZERO.setScale(2);
    }

    private void requireV2(Reservation reservation) {
        if (!supports(reservation)) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "Reservation không sử dụng Pricing V2");
        }
    }

    private AppException missingSnapshot() {
        return new AppException(
                ErrorCode.PRICING_QUOTE_MISMATCH,
                "Thiếu snapshot giá của reservation");
    }

    public record LineProjection(
            ReservationRoomType reservationLine,
            ReservationRateSnapshot commitment,
            ReservationRateSnapshot latest,
            int lineGuestCount,
            PricingBreakdown breakdown,
            BigDecimal finalRoomCharge,
            BigDecimal finalExtraGuestCharge,
            BigDecimal deltaAmount) {
    }

    public record Projection(
            LocalDateTime pricingCheckout,
            LocalDateTime inventoryProtectedUntil,
            BigDecimal projectedTotalAmount,
            BigDecimal plannedRoomCharge,
            BigDecimal actualRoomCharge,
            BigDecimal extraGuestCharge,
            BigDecimal earlyCheckoutAdjustment,
            BigDecimal cumulativePricingIncrease,
            BigDecimal deltaAmount,
            StayPackage displayPackage,
            List<LineProjection> lines) {

        public int packageCycles() {
            int packageCycles = lines.stream()
                    .findFirst()
                    .map(line -> line.breakdown().packageCycles())
                    .orElseThrow(() -> new IllegalStateException(
                            "Pricing projection must contain at least one line"));
            boolean inconsistent = lines.stream().anyMatch(line ->
                    line.breakdown().packageCycles() != packageCycles);
            if (inconsistent) {
                throw new IllegalStateException(
                        "Pricing projection lines have inconsistent package cycles");
            }
            return packageCycles;
        }
    }
}
