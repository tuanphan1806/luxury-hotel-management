package com.hotel.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.config.PricingV2Properties;
import com.hotel.backend.constant.*;
import com.hotel.backend.dto.request.PricingQuoteRequest;
import com.hotel.backend.dto.request.PricingQuoteRoomRequest;
import com.hotel.backend.dto.request.ServiceOrderRequest;
import com.hotel.backend.dto.response.*;
import com.hotel.backend.entity.*;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.pricing.*;
import com.hotel.backend.repository.*;
import com.hotel.backend.util.CanonicalJsonHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PricingQuoteService {

    private final PricingEngine pricingEngine;
    private final PricingDefinitionFactory definitionFactory;
    private final PricingV2Properties properties;
    private final RoomTypeRepository roomTypeRepository;
    private final RoomRateProfileRepository rateProfileRepository;
    private final ReservationAddOnService reservationAddOnService;
    private final PricingQuoteRepository quoteRepository;
    private final PricingQuoteLineRepository quoteLineRepository;
    private final CanonicalJsonHasher jsonHasher;
    private final PricingQuoteRequestNormalizer requestNormalizer;
    private final PricingQuoteAggregates aggregates;
    private final ObjectMapper objectMapper;
    private final StayWindowValidationService stayWindowValidationService;

    @Transactional
    public PricingQuoteResponse createQuote(PricingQuoteRequest request) {
        validateRequest(request);
        if (!properties.isEngineV2Enabled()) {
            throw new AppException(ErrorCode.PRICING_ENGINE_DISABLED);
        }

        Instant now = Instant.now();
        List<PricingQuoteRoomRequest> sortedRooms = request.getRooms().stream()
                .sorted(Comparator.comparing(PricingQuoteRoomRequest::getRoomTypeId))
                .toList();
        List<Long> roomTypeIds = sortedRooms.stream()
                .map(PricingQuoteRoomRequest::getRoomTypeId)
                .toList();
        Map<Long, RoomType> roomTypesById = roomTypeRepository
                .findAllById(roomTypeIds)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        RoomType::getId,
                        roomType -> roomType));
        for (PricingQuoteRoomRequest roomRequest : sortedRooms) {
            RoomType roomType = roomTypesById.get(roomRequest.getRoomTypeId());
            if (roomType == null) {
                throw new AppException(ErrorCode.ROOM_TYPE_NOT_FOUND);
            }
            if (!Boolean.TRUE.equals(roomType.getActive())) {
                throw new AppException(
                        ErrorCode.ROOM_TYPE_INACTIVE,
                        "Hạng phòng " + roomType.getTypeName()
                                + " đang ngừng hoạt động");
            }
            if (!properties.supportsRoomType(roomType.getCode())) {
                throw new AppException(
                        ErrorCode.PRICING_ENGINE_DISABLED,
                        "Bảng giá mới chưa được mở cho hạng phòng "
                                + roomType.getTypeName());
            }
        }
        Map<Long, List<RoomRateProfile>> ratesByRoomType =
                rateProfileRepository.findEffectiveByRoomTypeIds(
                                roomTypeIds, now)
                        .stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                rate -> rate.getRoomType().getId()));

        List<QuoteLineCalculation> calculatedLines = new ArrayList<>();
        StayPolicyVersion commonPolicy = null;
        for (PricingQuoteRoomRequest roomRequest : sortedRooms) {
            RoomType roomType = roomTypesById.get(roomRequest.getRoomTypeId());

            RoomRateProfile rateProfile = requireSingleEffectiveRate(
                    roomType,
                    ratesByRoomType.getOrDefault(
                            roomType.getId(), List.of()));
            if (commonPolicy == null) {
                commonPolicy = rateProfile.getStayPolicyVersion();
            } else if (!Objects.equals(
                    commonPolicy.getId(), rateProfile.getStayPolicyVersion().getId())) {
                throw new AppException(
                        ErrorCode.PRICING_PROFILE_NOT_FOUND,
                        "Các hạng phòng đang dùng phiên bản chính sách lưu trú khác nhau");
            }

            PricingBreakdown breakdown;
            try {
                breakdown = pricingEngine.calculate(
                        new PricingRequest(
                                request.getCheckIn(),
                                request.getCheckOut(),
                                roomRequest.getQuantity(),
                                roomRequest.getLineGuestCount()),
                        definitionFactory.roomRate(rateProfile),
                        definitionFactory.stayPolicy(rateProfile.getStayPolicyVersion()));
            } catch (IllegalArgumentException exception) {
                throw new AppException(ErrorCode.INVALID_REQUEST, exception.getMessage());
            }

            PricingQuoteLineResponse lineResponse =
                    toLineResponse(roomType, rateProfile, roomRequest, breakdown);
            calculatedLines.add(new QuoteLineCalculation(
                    roomType, rateProfile, breakdown, lineResponse));
        }

        List<PricingBreakdown> breakdowns = calculatedLines.stream()
                .map(QuoteLineCalculation::breakdown)
                .toList();
        ReservationAddOnService.BookingQuote serviceQuote =
                reservationAddOnService
                        .previewBookingTimeForPackageCycles(
                                request.getServices(),
                                request.getGuestCount(),
                                aggregates.commonPackageCycles(breakdowns));
        List<PricingQuoteServiceLineResponse> serviceLines =
                serviceQuote.lines().stream()
                        .map(this::toServiceLineResponse)
                        .toList();

        BigDecimal roomCharge = calculatedLines.stream()
                .map(line -> line.breakdown().roomCharge())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal extraGuestCharge = calculatedLines.stream()
                .map(line -> line.breakdown().extraGuestCharge())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal serviceCharge = serviceQuote.totalAmount();
        BigDecimal totalAmount =
                roomCharge.add(extraGuestCharge).add(serviceCharge);

        StayPolicyVersion policy = Objects.requireNonNull(commonPolicy);
        LocalDateTime protectedCheckout = aggregates.inventoryProtectedUntil(
                request.getCheckOut(), breakdowns, policy);
        UUID quoteId = UUID.randomUUID();
        Instant expiresAt = now.plus(
                properties.safeQuoteTtlMinutes(), ChronoUnit.MINUTES);

        PricingQuoteResponse response = PricingQuoteResponse.builder()
                .quoteId(quoteId)
                .quoteExpiresAtUtc(expiresAt)
                .stayPolicyVersionId(policy.getId())
                .stayPolicyVersion(policy.getPolicyVersion())
                .pricingAlgorithmVersion(PricingAlgorithmVersion.MOTEL_PACKAGE_V2)
                .checkIn(request.getCheckIn())
                .checkOut(request.getCheckOut())
                .guestCount(request.getGuestCount())
                .displayClassification(aggregates.displayClassification(breakdowns))
                .displayPackageSummary(aggregates.displayPackage(breakdowns))
                .inventoryProtectedUntil(protectedCheckout)
                .roomCharge(roomCharge)
                .extraGuestCharge(extraGuestCharge)
                .serviceCharge(serviceCharge)
                .totalAmount(totalAmount)
                .lines(calculatedLines.stream()
                        .map(QuoteLineCalculation::response)
                        .toList())
                .services(serviceLines)
                .build();

        Map<String, Object> normalizedRequest = requestNormalizer.normalize(request);
        String requestHash = jsonHasher.hash(normalizedRequest);
        String quoteHash = jsonHasher.hash(response);
        response.setQuoteHash(quoteHash);

        PricingQuote quote = quoteRepository.save(PricingQuote.builder()
                .id(quoteId)
                .stayPolicyVersion(policy)
                .pricingAlgorithmVersion(PricingAlgorithmVersion.MOTEL_PACKAGE_V2)
                .checkIn(request.getCheckIn())
                .checkOut(request.getCheckOut())
                .guestCount(request.getGuestCount())
                .roomCharge(roomCharge)
                .extraGuestCharge(extraGuestCharge)
                .serviceCharge(serviceCharge)
                .totalAmount(totalAmount)
                .inventoryProtectedUntil(protectedCheckout)
                .requestHash(requestHash)
                .quoteHash(quoteHash)
                .requestJson(jsonHasher.canonicalTree(normalizedRequest))
                .responseJson(jsonHasher.canonicalTree(response))
                .createdAtUtc(now)
                .expiresAtUtc(expiresAt)
                .build());

        quoteLineRepository.saveAll(calculatedLines.stream()
                .map(line -> toQuoteLine(quote, line))
                .toList());
        return response;
    }

    private void validateRequest(PricingQuoteRequest request) {
        if (request == null
                || request.getCheckIn() == null
                || request.getCheckOut() == null
                || !request.getCheckOut().isAfter(request.getCheckIn())
                || request.getGuestCount() == null
                || request.getGuestCount() < 1
                || request.getRooms() == null
                || request.getRooms().isEmpty()) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST, "Thông tin báo giá không hợp lệ");
        }
        stayWindowValidationService.validate(
                request.getCheckIn(), request.getCheckOut());
        ensureUniqueRoomTypes(request.getRooms());
        validateServiceItems(request.getServices());
        int lineGuestTotal = request.getRooms().stream()
                .mapToInt(PricingQuoteRoomRequest::getLineGuestCount)
                .sum();
        if (lineGuestTotal != request.getGuestCount()) {
            throw new AppException(
                    ErrorCode.INVALID_REQUEST,
                    "Tổng số khách phải bằng tổng số khách đã phân bổ theo từng hạng phòng");
        }
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

    private void validateServiceItems(List<ServiceOrderRequest> services) {
        if (services == null || services.isEmpty()) {
            return;
        }
        Set<Long> uniqueIds = new HashSet<>();
        for (ServiceOrderRequest service : services) {
            if (service == null
                    || service.getServiceId() == null
                    || service.getQuantity() == null
                    || service.getQuantity() < 1
                    || !uniqueIds.add(service.getServiceId())) {
                throw new AppException(
                        ErrorCode.INVALID_REQUEST,
                        "Danh sách dịch vụ có mục trùng hoặc thiếu thông tin");
            }
        }
    }

    private void ensureUniqueRoomTypes(List<PricingQuoteRoomRequest> rooms) {
        Set<Long> uniqueIds = new HashSet<>();
        for (PricingQuoteRoomRequest room : rooms) {
            if (room == null
                    || room.getRoomTypeId() == null
                    || room.getQuantity() == null
                    || room.getQuantity() < 1
                    || room.getLineGuestCount() == null
                    || room.getLineGuestCount() < 1
                    || !uniqueIds.add(room.getRoomTypeId())) {
                throw new AppException(
                        ErrorCode.INVALID_REQUEST,
                        "Danh sách hạng phòng có mục trùng hoặc thiếu thông tin");
            }
        }
    }

    private PricingQuoteLineResponse toLineResponse(
            RoomType roomType,
            RoomRateProfile rateProfile,
            PricingQuoteRoomRequest request,
            PricingBreakdown breakdown) {
        return PricingQuoteLineResponse.builder()
                .roomTypeId(roomType.getId())
                .roomTypeCode(roomType.getCode())
                .roomTypeName(roomType.getTypeName())
                .quantity(request.getQuantity())
                .lineGuestCount(request.getLineGuestCount())
                .includedGuestsPerRoom(rateProfile.getIncludedGuests())
                .maxGuestsPerRoom(roomType.getMaxGuests())
                .extraGuestPrice(rateProfile.getExtraGuestPrice())
                .rateProfileId(rateProfile.getId())
                .rateProfileVersion(rateProfile.getProfileVersion())
                .stayClassification(breakdown.stayClassification())
                .appliedPackage(breakdown.appliedPackage())
                .transitionReason(breakdown.transitionReason())
                .packageIncludedCheckout(breakdown.packageIncludedCheckout())
                .roomCharge(breakdown.roomCharge())
                .extraGuestCount(breakdown.extraGuestCount())
                .extraGuestCharge(breakdown.extraGuestCharge())
                .lineTotalBeforeServices(breakdown.lineTotalBeforeServices())
                .cycles(breakdown.cycles().stream()
                        .map(cycle -> PricingQuoteCycleResponse.builder()
                                .sequence(cycle.sequence())
                                .appliedPackage(cycle.appliedPackage())
                                .transitionReason(cycle.transitionReason())
                                .billableStart(cycle.billableStart())
                                .plannedSegmentEnd(cycle.plannedSegmentEnd())
                                .packageIncludedCheckout(
                                        cycle.packageIncludedCheckout())
                                .billableMinutes(cycle.billableMinutes())
                                .chargedExtraUnits(cycle.chargedExtraUnits())
                                .roomChargePerRoom(cycle.roomChargePerRoom())
                                .build())
                        .toList())
                .build();
    }

    private PricingQuoteServiceLineResponse toServiceLineResponse(
            ReservationAddOnService.PricedService line) {
        AddOnService service = line.service();
        return PricingQuoteServiceLineResponse.builder()
                .serviceId(service.getId())
                .serviceCode(service.getCode())
                .serviceName(service.getName())
                .pricingUnit(service.getPricingUnit())
                .unitPrice(service.getPrice())
                .quantity(line.quantity())
                .multiplier(line.multiplier())
                .billableQuantity(line.billableQuantity())
                .totalPrice(line.totalPrice())
                .build();
    }

    private PricingQuoteLine toQuoteLine(
            PricingQuote quote,
            QuoteLineCalculation line) {
        PricingBreakdown breakdown = line.breakdown();
        PricingQuoteLineResponse response = line.response();
        return PricingQuoteLine.builder()
                .pricingQuote(quote)
                .roomType(line.roomType())
                .rateProfile(line.rateProfile())
                .roomTypeCodeSnapshot(line.roomType().getCode())
                .rateProfileVersion(line.rateProfile().getProfileVersion())
                .roomQuantity(response.getQuantity())
                .lineGuestCount(response.getLineGuestCount())
                .stayClassification(breakdown.stayClassification())
                .appliedPackage(breakdown.appliedPackage())
                .transitionReason(breakdown.transitionReason())
                .packageIncludedCheckout(breakdown.packageIncludedCheckout())
                .roomCharge(breakdown.roomCharge())
                .extraGuestCharge(breakdown.extraGuestCharge())
                .lineTotalBeforeServices(breakdown.lineTotalBeforeServices())
                .breakdownJson(objectMapper.valueToTree(breakdown))
                .build();
    }

    private record QuoteLineCalculation(
            RoomType roomType,
            RoomRateProfile rateProfile,
            PricingBreakdown breakdown,
            PricingQuoteLineResponse response) {
    }
}
