package com.hotel.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.config.PricingV2Properties;
import com.hotel.backend.constant.PricingAlgorithmVersion;
import com.hotel.backend.constant.StayPackage;
import com.hotel.backend.dto.request.CreateReservationRequest;
import com.hotel.backend.dto.request.PricingQuoteRequest;
import com.hotel.backend.dto.request.PricingQuoteRoomRequest;
import com.hotel.backend.dto.request.RoomTypeItemRequest;
import com.hotel.backend.entity.*;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.pricing.*;
import com.hotel.backend.repository.*;
import com.hotel.backend.util.CanonicalJsonHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Transactional quote-to-reservation boundary.
 *
 * <p>The public quote is never trusted as a price input. This service locks the
 * quote and referenced rate rows, hashes the reservation request again, and
 * recalculates every line plus booking-time service before allowing a
 * reservation to persist.</p>
 */
@Service
@RequiredArgsConstructor
public class PricingQuoteCommitmentService {

    private final PricingV2Properties properties;
    private final PricingEngine pricingEngine;
    private final PricingDefinitionFactory definitionFactory;
    private final PricingQuoteAggregates aggregates;
    private final PricingQuoteRequestNormalizer requestNormalizer;
    private final CanonicalJsonHasher jsonHasher;
    private final PricingQuoteRepository quoteRepository;
    private final PricingQuoteLineRepository quoteLineRepository;
    private final PricingQuoteCommitmentRepository commitmentRepository;
    private final RoomRateProfileRepository rateProfileRepository;
    private final ReservationAddOnService reservationAddOnService;

    public boolean requestsV2(CreateReservationRequest request) {
        if (request == null) {
            return false;
        }
        return request.getQuoteId() != null
                || hasText(request.getQuoteHash())
                || Optional.ofNullable(request.getRoomTypes())
                        .orElse(List.of())
                        .stream()
                        .filter(Objects::nonNull)
                .anyMatch(line -> line.getLineGuestCount() != null);
    }

    /**
     * Compatibility clients may omit quotes during canary rollout. Once every
     * online booking client has migrated, this explicit cutover gate prevents
     * callers from bypassing V2 by intentionally omitting quote fields.
     */
    public void validateLegacyReservationAllowed() {
        if (properties.isEngineV2Enabled()
                && properties.isEngineV2RequireQuote()) {
            throw new AppException(
                    ErrorCode.PRICING_QUOTE_MISMATCH,
                    "Đặt phòng online bắt buộc phải có báo giá còn hiệu lực");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Commitment validateForReservation(
            CreateReservationRequest request,
            Map<Long, RoomType> lockedRoomTypes) {
        PricingQuoteRequest quoteRequest = toQuoteRequest(request);
        validateV2Fields(request, quoteRequest);
        if (!properties.isEngineV2Enabled()) {
            throw new AppException(ErrorCode.PRICING_ENGINE_DISABLED);
        }

        PricingQuote quote = quoteRepository.findByIdForUpdate(request.getQuoteId())
                .orElseThrow(() -> new AppException(ErrorCode.PRICING_QUOTE_EXPIRED));
        if (commitmentRepository.existsByPricingQuoteId(quote.getId())) {
            throw new AppException(
                    ErrorCode.PRICING_QUOTE_MISMATCH,
                    "Báo giá đã được dùng để tạo một đơn đặt phòng");
        }

        Instant now = Instant.now();
        if (!quote.getExpiresAtUtc().isAfter(now)) {
            throw new AppException(ErrorCode.PRICING_QUOTE_EXPIRED);
        }
        if (quote.getPricingAlgorithmVersion()
                != PricingAlgorithmVersion.MOTEL_PACKAGE_V2) {
            throw new AppException(ErrorCode.PRICING_QUOTE_MISMATCH);
        }
        if (!constantTimeEquals(quote.getQuoteHash(), request.getQuoteHash())) {
            throw new AppException(ErrorCode.PRICING_QUOTE_MISMATCH);
        }
        String requestHash = jsonHasher.hash(requestNormalizer.normalize(quoteRequest));
        if (!constantTimeEquals(quote.getRequestHash(), requestHash)) {
            throw new AppException(ErrorCode.PRICING_QUOTE_MISMATCH);
        }

        Map<Long, PricingQuoteLine> quotedLines = quoteLineRepository
                .findByPricingQuoteIdOrderByIdAsc(quote.getId())
                .stream()
                .collect(Collectors.toMap(
                        line -> line.getRoomType().getId(),
                        Function.identity()));
        if (quotedLines.size() != quoteRequest.getRooms().size()) {
            throw new AppException(ErrorCode.PRICING_QUOTE_MISMATCH);
        }

        List<CommittedLine> committedLines = new ArrayList<>();
        for (PricingQuoteRoomRequest requestedLine : quoteRequest.getRooms().stream()
                .sorted(Comparator.comparing(PricingQuoteRoomRequest::getRoomTypeId))
                .toList()) {
            RoomType roomType = lockedRoomTypes.get(requestedLine.getRoomTypeId());
            PricingQuoteLine quotedLine = quotedLines.get(requestedLine.getRoomTypeId());
            if (roomType == null
                    || quotedLine == null
                    || !properties.supportsRoomType(roomType.getCode())
                    || !Objects.equals(
                            quotedLine.getRoomTypeCodeSnapshot(), roomType.getCode())) {
                throw new AppException(ErrorCode.PRICING_QUOTE_MISMATCH);
            }

            RoomRateProfile rateProfile = rateProfileRepository
                    .findByIdForUpdate(quotedLine.getRateProfile().getId())
                    .orElseThrow(this::priceChanged);
            if (!rateStillCurrent(rateProfile, roomType, quote, now)) {
                throw priceChanged();
            }

            PricingBreakdown currentBreakdown;
            try {
                currentBreakdown = pricingEngine.calculate(
                        new PricingRequest(
                                quoteRequest.getCheckIn(),
                                quoteRequest.getCheckOut(),
                                requestedLine.getQuantity(),
                                requestedLine.getLineGuestCount()),
                        definitionFactory.roomRate(rateProfile),
                        definitionFactory.stayPolicy(
                                rateProfile.getStayPolicyVersion()));
            } catch (IllegalArgumentException exception) {
                throw new AppException(
                        ErrorCode.PRICING_QUOTE_MISMATCH,
                        exception.getMessage());
            }
            if (!breakdownMatches(quotedLine, currentBreakdown)) {
                throw priceChanged();
            }
            committedLines.add(new CommittedLine(
                    roomType,
                    rateProfile,
                    requestedLine.getQuantity(),
                    requestedLine.getLineGuestCount(),
                    currentBreakdown));
        }

        List<PricingBreakdown> breakdowns = committedLines.stream()
                .map(CommittedLine::breakdown)
                .toList();
        ReservationAddOnService.BookingQuote bookingServices;
        try {
            bookingServices = reservationAddOnService
                    .quoteBookingTimeForPackageCycles(
                            quoteRequest.getServices(),
                            quoteRequest.getGuestCount(),
                            aggregates.commonPackageCycles(breakdowns));
        } catch (AppException exception) {
            throw priceChanged();
        }
        if (!servicesMatch(quote.getResponseJson().path("services"), bookingServices)) {
            throw priceChanged();
        }

        BigDecimal roomCharge = sum(
                committedLines, line -> line.breakdown().roomCharge());
        BigDecimal extraGuestCharge = sum(
                committedLines, line -> line.breakdown().extraGuestCharge());
        BigDecimal serviceCharge = bookingServices.totalAmount();
        BigDecimal totalAmount = roomCharge.add(extraGuestCharge).add(serviceCharge);
        StayPolicyVersion policy = quote.getStayPolicyVersion();
        LocalDateTime inventoryProtectedUntil =
                aggregates.inventoryProtectedUntil(
                        quoteRequest.getCheckOut(), breakdowns, policy);
        StayPackage displayPackage = aggregates.displayPackage(breakdowns);

        if (!moneyEquals(quote.getRoomCharge(), roomCharge)
                || !moneyEquals(quote.getExtraGuestCharge(), extraGuestCharge)
                || !moneyEquals(quote.getServiceCharge(), serviceCharge)
                || !moneyEquals(quote.getTotalAmount(), totalAmount)
                || !Objects.equals(
                        quote.getInventoryProtectedUntil(),
                        inventoryProtectedUntil)) {
            throw priceChanged();
        }

        return new Commitment(
                quote,
                List.copyOf(committedLines),
                bookingServices,
                roomCharge,
                extraGuestCharge,
                serviceCharge,
                totalAmount,
                inventoryProtectedUntil,
                displayPackage);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCommitment(Commitment commitment, Reservation reservation) {
        commitmentRepository.save(PricingQuoteCommitment.builder()
                .pricingQuote(commitment.quote())
                .reservation(reservation)
                .committedAtUtc(Instant.now())
                .build());
    }

    private PricingQuoteRequest toQuoteRequest(CreateReservationRequest request) {
        List<PricingQuoteRoomRequest> rooms = Optional.ofNullable(request.getRoomTypes())
                .orElse(List.of())
                .stream()
                .filter(Objects::nonNull)
                .map(line -> PricingQuoteRoomRequest.builder()
                        .roomTypeId(line.getRoomTypeId())
                        .quantity(line.getQuantity())
                        .lineGuestCount(line.getLineGuestCount())
                        .build())
                .toList();
        return PricingQuoteRequest.builder()
                .checkIn(request.getCheckIn())
                .checkOut(request.getCheckOut())
                .guestCount(request.getGuestCount())
                .rooms(rooms)
                .services(Optional.ofNullable(request.getServices()).orElse(List.of()))
                .build();
    }

    private void validateV2Fields(
            CreateReservationRequest request,
            PricingQuoteRequest quoteRequest) {
        if (request.getQuoteId() == null
                || !hasText(request.getQuoteHash())
                || request.getQuoteHash().trim().length() != 64
                || quoteRequest.getRooms().isEmpty()
                || quoteRequest.getRooms().stream()
                        .anyMatch(line -> line.getLineGuestCount() == null
                                || line.getLineGuestCount() < 1)
                || quoteRequest.getRooms().stream()
                        .mapToInt(PricingQuoteRoomRequest::getLineGuestCount)
                        .sum() != quoteRequest.getGuestCount()) {
            throw new AppException(
                    ErrorCode.PRICING_QUOTE_MISMATCH,
                    "Thiếu quote hoặc phân bổ số khách theo từng hạng phòng");
        }
    }

    private boolean rateStillCurrent(
            RoomRateProfile profile,
            RoomType roomType,
            PricingQuote quote,
            Instant now) {
        StayPolicyVersion policy = profile.getStayPolicyVersion();
        return Objects.equals(profile.getRoomType().getId(), roomType.getId())
                && Objects.equals(policy.getId(), quote.getStayPolicyVersion().getId())
                && Boolean.TRUE.equals(profile.getActive())
                && !profile.getEffectiveFromUtc().isAfter(now)
                && (profile.getEffectiveToUtc() == null
                        || profile.getEffectiveToUtc().isAfter(now))
                && Boolean.TRUE.equals(policy.getActive())
                && !policy.getEffectiveFromUtc().isAfter(now)
                && (policy.getEffectiveToUtc() == null
                        || policy.getEffectiveToUtc().isAfter(now));
    }

    private boolean breakdownMatches(
            PricingQuoteLine quotedLine,
            PricingBreakdown current) {
        return jsonHasher.canonicalTree(current)
                        .equals(jsonHasher.canonicalTree(
                                quotedLine.getBreakdownJson()))
                && moneyEquals(quotedLine.getRoomCharge(), current.roomCharge())
                && moneyEquals(
                        quotedLine.getExtraGuestCharge(),
                        current.extraGuestCharge())
                && moneyEquals(
                        quotedLine.getLineTotalBeforeServices(),
                        current.lineTotalBeforeServices());
    }

    private boolean servicesMatch(
            JsonNode storedServices,
            ReservationAddOnService.BookingQuote current) {
        if (!storedServices.isArray()
                || storedServices.size() != current.lines().size()) {
            return false;
        }
        for (int index = 0; index < current.lines().size(); index++) {
            JsonNode stored = storedServices.get(index);
            ReservationAddOnService.PricedService line = current.lines().get(index);
            AddOnService service = line.service();
            if (stored.path("serviceId").asLong(Long.MIN_VALUE) != service.getId()
                    || !Objects.equals(
                            stored.path("serviceCode").asText(null),
                            service.getCode())
                    || !Objects.equals(
                            stored.path("pricingUnit").asText(null),
                            service.getPricingUnit().name())
                    || !jsonMoneyEquals(stored.path("unitPrice"), service.getPrice())
                    || stored.path("quantity").asInt(Integer.MIN_VALUE)
                            != line.quantity()
                    || stored.path("multiplier").asInt(Integer.MIN_VALUE)
                            != line.multiplier()
                    || stored.path("billableQuantity").asInt(Integer.MIN_VALUE)
                            != line.billableQuantity()
                    || !jsonMoneyEquals(
                            stored.path("totalPrice"), line.totalPrice())) {
                return false;
            }
        }
        return true;
    }

    private boolean jsonMoneyEquals(JsonNode node, BigDecimal value) {
        return node != null
                && node.isNumber()
                && moneyEquals(node.decimalValue(), value);
    }

    private boolean moneyEquals(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private BigDecimal sum(
            List<CommittedLine> lines,
            Function<CommittedLine, BigDecimal> selector) {
        return lines.stream()
                .map(selector)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.trim().getBytes(StandardCharsets.UTF_8),
                right.trim().getBytes(StandardCharsets.UTF_8));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private AppException priceChanged() {
        return new AppException(ErrorCode.PRICE_CHANGED);
    }

    public record CommittedLine(
            RoomType roomType,
            RoomRateProfile rateProfile,
            int quantity,
            int lineGuestCount,
            PricingBreakdown breakdown) {
    }

    public record Commitment(
            PricingQuote quote,
            List<CommittedLine> lines,
            ReservationAddOnService.BookingQuote bookingServices,
            BigDecimal roomCharge,
            BigDecimal extraGuestCharge,
            BigDecimal serviceCharge,
            BigDecimal totalAmount,
            LocalDateTime inventoryProtectedUntil,
            StayPackage displayPackage) {
    }
}
