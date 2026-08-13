package com.hotel.backend.service;

import com.hotel.backend.config.PricingV2Properties;
import com.hotel.backend.constant.StayPackage;
import com.hotel.backend.entity.RoomRateProfile;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.pricing.PricingBreakdown;
import com.hotel.backend.pricing.PricingDefinitionFactory;
import com.hotel.backend.pricing.PricingEngine;
import com.hotel.backend.pricing.PricingRequest;
import com.hotel.backend.repository.RoomRateProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Read-only room-price preview for availability results.
 *
 * <p>The final reservation amount is still produced by the signed pricing
 * quote. This adapter prevents the availability page from showing a legacy
 * estimate while the checkout page uses Pricing V2.</p>
 */
@Service
@RequiredArgsConstructor
public class AvailabilityPricingService {

    private final PricingV2Properties properties;
    private final PricingEngine pricingEngine;
    private final PricingDefinitionFactory definitionFactory;
    private final RoomRateProfileRepository rateProfileRepository;

    @Transactional(readOnly = true)
    public Optional<Estimate> estimate(
            RoomType roomType,
            LocalDateTime checkIn,
            LocalDateTime checkOut) {
        if (!properties.supportsRoomType(roomType.getCode())) {
            return Optional.empty();
        }
        RoomRateProfile rateProfile = requireSingleEffectiveRate(
                roomType,
                rateProfileRepository.findEffectiveByRoomTypeCode(
                        roomType.getCode(), Instant.now()));
        if (!Objects.equals(
                roomType.getId(), rateProfile.getRoomType().getId())) {
            throw new AppException(ErrorCode.PRICE_CHANGED);
        }

        return Optional.of(calculateEstimate(
                rateProfile, checkIn, checkOut));
    }

    /**
     * Calculates every supported room type with one effective-rate query.
     * The same validation and pricing engine as {@link #estimate} are used,
     * so this only changes database access shape, not pricing behaviour.
     */
    @Transactional(readOnly = true)
    public Map<Long, Estimate> estimateAll(
            Collection<RoomType> roomTypes,
            LocalDateTime checkIn,
            LocalDateTime checkOut) {
        if (roomTypes == null || roomTypes.isEmpty()) {
            return Map.of();
        }

        List<RoomType> supportedRoomTypes = roomTypes.stream()
                .filter(roomType -> properties.supportsRoomType(
                        roomType.getCode()))
                .toList();
        if (supportedRoomTypes.isEmpty()) {
            return Map.of();
        }

        List<Long> roomTypeIds = supportedRoomTypes.stream()
                .map(RoomType::getId)
                .toList();
        Map<Long, List<RoomRateProfile>> ratesByRoomType =
                rateProfileRepository.findEffectiveByRoomTypeIds(
                                roomTypeIds, Instant.now())
                        .stream()
                        .collect(Collectors.groupingBy(
                                rate -> rate.getRoomType().getId()));

        Map<Long, Estimate> estimates = new LinkedHashMap<>();
        for (RoomType roomType : supportedRoomTypes) {
            RoomRateProfile rateProfile = requireSingleEffectiveRate(
                    roomType,
                    ratesByRoomType.getOrDefault(
                            roomType.getId(), List.of()));
            if (!Objects.equals(
                    roomType.getId(),
                    rateProfile.getRoomType().getId())) {
                throw new AppException(ErrorCode.PRICE_CHANGED);
            }
            estimates.put(
                    roomType.getId(),
                    calculateEstimate(rateProfile, checkIn, checkOut));
        }
        return estimates;
    }

    private Estimate calculateEstimate(
            RoomRateProfile rateProfile,
            LocalDateTime checkIn,
            LocalDateTime checkOut) {
        PricingBreakdown breakdown;
        try {
            breakdown = pricingEngine.calculate(
                    new PricingRequest(checkIn, checkOut, 1, 1),
                    definitionFactory.roomRate(rateProfile),
                    definitionFactory.stayPolicy(
                            rateProfile.getStayPolicyVersion()));
        } catch (IllegalArgumentException exception) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST, exception.getMessage());
        }
        return new Estimate(
                rateProfile.getIncludedGuests(),
                rateProfile.getFirstBlockMinutes(),
                rateProfile.getFirstBlockPrice(),
                rateProfile.getExtraGuestPrice(),
                breakdown.roomChargePerRoom(),
                breakdown.appliedPackage());
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

    public record Estimate(
            int includedGuests,
            int firstBlockMinutes,
            BigDecimal firstBlockPrice,
            BigDecimal extraGuestPrice,
            BigDecimal estimatedPricePerRoom,
            StayPackage estimatedPackage) {
    }
}
