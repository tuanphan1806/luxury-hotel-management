package com.hotel.backend.service;

import com.hotel.backend.config.PricingV2Properties;
import com.hotel.backend.constant.StayPackage;
import com.hotel.backend.entity.RoomRateProfile;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.entity.StayPolicyVersion;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.pricing.PricingBreakdown;
import com.hotel.backend.pricing.PricingDefinitionFactory;
import com.hotel.backend.pricing.PricingEngine;
import com.hotel.backend.pricing.PricingQuoteAggregates;
import com.hotel.backend.pricing.PricingRequest;
import com.hotel.backend.repository.RoomRateProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Server-authoritative Pricing V2 adapter for staff-created walk-ins.
 *
 * <p>Walk-ins do not need a public, expiring quote because the price is
 * calculated and committed in the same database transaction. They still use
 * the exact same engine, effective rate versions and immutable snapshots as
 * online reservations.</p>
 */
@Service
@RequiredArgsConstructor
public class WalkInPricingService {

    private final PricingV2Properties properties;
    private final PricingEngine pricingEngine;
    private final PricingDefinitionFactory definitionFactory;
    private final PricingQuoteAggregates aggregates;
    private final RoomRateProfileRepository rateProfileRepository;

    /**
     * Returns empty only at a compatibility/canary boundary. Once all selected
     * room codes are enabled, a missing or invalid rate fails loudly instead
     * of silently falling back to a mutable legacy price.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Calculation> calculateIfEligible(
            LocalDateTime checkIn,
            LocalDateTime checkOut,
            int declaredGuestCount,
            List<LineInput> inputs) {
        if (!properties.isEngineV2Enabled()
                || inputs == null
                || inputs.isEmpty()
                || inputs.stream().anyMatch(input ->
                        input == null
                                || input.roomType() == null
                                || !properties.supportsRoomType(
                                        input.roomType().getCode()))) {
            return Optional.empty();
        }
        if (checkIn == null
                || checkOut == null
                || !checkOut.isAfter(checkIn)
                || declaredGuestCount < 1) {
            throw invalid("Thông tin tính giá walk-in không hợp lệ");
        }

        List<LineInput> sortedInputs = inputs.stream()
                .sorted(Comparator.comparing(input -> input.roomType().getId()))
                .toList();
        ensureUniqueRoomTypes(sortedInputs);

        Instant effectiveAtUtc = Instant.now();
        List<PreparedLine> preparedLines = new ArrayList<>();
        StayPolicyVersion commonPolicy = null;
        for (LineInput input : sortedInputs) {
            if (input.quantity() < 1 || input.minimumGuestCount() < 0) {
                throw invalid("Số lượng phòng hoặc số khách walk-in không hợp lệ");
            }
            RoomType roomType = input.roomType();
            RoomRateProfile rateProfile = requireSingleEffectiveRate(
                    roomType,
                    rateProfileRepository.findEffectiveByRoomTypeCodeForUpdate(
                            roomType.getCode(), effectiveAtUtc));
            if (!Objects.equals(
                    rateProfile.getRoomType().getId(), roomType.getId())) {
                throw new AppException(ErrorCode.PRICE_CHANGED);
            }
            if (commonPolicy == null) {
                commonPolicy = rateProfile.getStayPolicyVersion();
            } else if (!Objects.equals(
                    commonPolicy.getId(),
                    rateProfile.getStayPolicyVersion().getId())) {
                throw new AppException(
                        ErrorCode.PRICING_PROFILE_NOT_FOUND,
                        "Các hạng phòng walk-in đang dùng chính sách lưu trú khác nhau");
            }
            preparedLines.add(new PreparedLine(input, rateProfile));
        }

        Map<Long, Integer> guestCounts = allocateGuestCounts(
                declaredGuestCount, preparedLines);
        List<CalculatedLine> calculatedLines = new ArrayList<>();
        for (PreparedLine prepared : preparedLines) {
            LineInput input = prepared.input();
            int lineGuestCount = guestCounts.get(input.roomType().getId());
            PricingBreakdown breakdown;
            try {
                breakdown = pricingEngine.calculate(
                        new PricingRequest(
                                checkIn,
                                checkOut,
                                input.quantity(),
                                lineGuestCount),
                        definitionFactory.roomRate(prepared.rateProfile()),
                        definitionFactory.stayPolicy(
                                prepared.rateProfile().getStayPolicyVersion()));
            } catch (IllegalArgumentException exception) {
                throw invalid(exception.getMessage());
            }
            calculatedLines.add(new CalculatedLine(
                    input.roomType(),
                    prepared.rateProfile(),
                    input.quantity(),
                    lineGuestCount,
                    breakdown));
        }

        List<PricingBreakdown> breakdowns = calculatedLines.stream()
                .map(CalculatedLine::breakdown)
                .toList();
        StayPolicyVersion policy = Objects.requireNonNull(commonPolicy);
        BigDecimal roomCharge = sum(
                calculatedLines, line -> line.breakdown().roomCharge());
        BigDecimal extraGuestCharge = sum(
                calculatedLines, line -> line.breakdown().extraGuestCharge());
        return Optional.of(new Calculation(
                List.copyOf(calculatedLines),
                roomCharge,
                extraGuestCharge,
                roomCharge.add(extraGuestCharge),
                aggregates.inventoryProtectedUntil(
                        checkOut, breakdowns, policy),
                aggregates.displayPackage(breakdowns),
                aggregates.commonPackageCycles(breakdowns)));
    }

    private RoomRateProfile requireSingleEffectiveRate(
            RoomType roomType,
            List<RoomRateProfile> effectiveRates) {
        if (effectiveRates == null || effectiveRates.isEmpty()) {
            throw new AppException(
                    ErrorCode.PRICING_PROFILE_NOT_FOUND,
                    "Hạng phòng " + roomType.getTypeName()
                            + " chưa có bảng giá đang hiệu lực");
        }
        if (effectiveRates.size() != 1) {
            throw new AppException(
                    ErrorCode.PRICING_PROFILE_NOT_FOUND,
                    "Hạng phòng " + roomType.getTypeName()
                            + " có nhiều bảng giá chồng thời gian; cần đóng version lỗi trước khi bán");
        }
        return effectiveRates.get(0);
    }

    private Map<Long, Integer> allocateGuestCounts(
            int declaredGuestCount,
            List<PreparedLine> preparedLines) {
        Map<Long, Integer> allocated = new LinkedHashMap<>();
        Set<Long> fixedLines = preparedLines.stream()
                .filter(line -> line.input().requestedGuestCount() != null)
                .map(line -> line.input().roomType().getId())
                .collect(Collectors.toSet());

        int allocatedTotal = 0;
        for (PreparedLine prepared : preparedLines) {
            LineInput input = prepared.input();
            int minimum = Math.max(input.quantity(), input.minimumGuestCount());
            int count = input.requestedGuestCount() != null
                    ? input.requestedGuestCount()
                    : minimum;
            int capacity = maximumGuests(input);
            if (count < minimum || count > capacity) {
                throw invalid(
                        "Số khách phân bổ cho " + input.roomType().getTypeName()
                                + " không hợp lệ");
            }
            allocated.put(input.roomType().getId(), count);
            allocatedTotal += count;
        }

        int remaining = declaredGuestCount - allocatedTotal;
        if (remaining < 0) {
            throw invalid(
                    "Danh sách khách trong phòng vượt tổng số khách walk-in");
        }

        // Unknown guest details first consume included capacity, then physical
        // capacity. Explicit line allocations remain authoritative.
        remaining = allocateRemaining(
                remaining, preparedLines, allocated, fixedLines, true);
        remaining = allocateRemaining(
                remaining, preparedLines, allocated, fixedLines, false);
        if (remaining != 0) {
            throw invalid(
                    "Không thể phân bổ toàn bộ số khách vào các phòng đã chọn");
        }
        return allocated;
    }

    private int allocateRemaining(
            int remaining,
            List<PreparedLine> preparedLines,
            Map<Long, Integer> allocated,
            Set<Long> fixedLines,
            boolean includedCapacityOnly) {
        for (PreparedLine prepared : preparedLines) {
            if (remaining == 0) {
                return 0;
            }
            LineInput input = prepared.input();
            Long roomTypeId = input.roomType().getId();
            if (fixedLines.contains(roomTypeId)) {
                continue;
            }
            int ceiling = includedCapacityOnly
                    ? Math.min(
                            maximumGuests(input),
                            prepared.rateProfile().getIncludedGuests()
                                    * input.quantity())
                    : maximumGuests(input);
            int headroom = Math.max(ceiling - allocated.get(roomTypeId), 0);
            int addition = Math.min(headroom, remaining);
            if (addition > 0) {
                allocated.put(
                        roomTypeId, allocated.get(roomTypeId) + addition);
                remaining -= addition;
            }
        }
        return remaining;
    }

    private int maximumGuests(LineInput input) {
        int perRoom = input.roomType().getMaxGuests() != null
                ? Math.max(1, input.roomType().getMaxGuests())
                : 2;
        return Math.multiplyExact(perRoom, input.quantity());
    }

    private void ensureUniqueRoomTypes(List<LineInput> inputs) {
        Set<Long> ids = inputs.stream()
                .map(input -> input.roomType().getId())
                .collect(Collectors.toSet());
        if (ids.size() != inputs.size() || ids.contains(null)) {
            throw invalid("Danh sách hạng phòng walk-in bị trùng hoặc thiếu");
        }
    }

    private BigDecimal sum(
            List<CalculatedLine> lines,
            java.util.function.Function<CalculatedLine, BigDecimal> selector) {
        return lines.stream()
                .map(selector)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private AppException invalid(String message) {
        return new AppException(ErrorCode.INVALID_REQUEST, message);
    }

    public record LineInput(
            RoomType roomType,
            int quantity,
            Integer requestedGuestCount,
            int minimumGuestCount) {
    }

    public record CalculatedLine(
            RoomType roomType,
            RoomRateProfile rateProfile,
            int quantity,
            int lineGuestCount,
            PricingBreakdown breakdown) {
    }

    public record Calculation(
            List<CalculatedLine> lines,
            BigDecimal roomCharge,
            BigDecimal extraGuestCharge,
            BigDecimal totalBeforeServices,
            LocalDateTime inventoryProtectedUntil,
            StayPackage displayPackage,
            int packageCycles) {
    }

    private record PreparedLine(
            LineInput input,
            RoomRateProfile rateProfile) {
    }
}
