package com.hotel.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotel.backend.config.PricingV2Properties;
import com.hotel.backend.constant.ExtraGuestBillingMode;
import com.hotel.backend.constant.InventoryProtectionMode;
import com.hotel.backend.constant.PricingAlgorithmVersion;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PricingQuoteCommitmentServiceTest {

    @Mock
    private PricingQuoteRepository quoteRepository;
    @Mock
    private PricingQuoteLineRepository quoteLineRepository;
    @Mock
    private PricingQuoteCommitmentRepository commitmentRepository;
    @Mock
    private RoomRateProfileRepository rateProfileRepository;
    @Mock
    private ReservationAddOnService reservationAddOnService;

    private PricingQuoteCommitmentService service;
    private PricingV2Properties properties;
    private CanonicalJsonHasher hasher;
    private PricingQuoteRequestNormalizer normalizer;
    private MotelPackagePricingEngine engine;
    private ObjectMapper objectMapper;
    private RoomType roomType;
    private RoomRateProfile rateProfile;
    private StayPolicyVersion policy;
    private PricingBreakdown breakdown;
    private CreateReservationRequest request;
    private PricingQuote quote;
    private PricingQuoteLine quoteLine;

    @BeforeEach
    void setUp() {
        properties = new PricingV2Properties();
        properties.setEngineV2Enabled(true);
        engine = new MotelPackagePricingEngine();
        objectMapper = new ObjectMapper().findAndRegisterModules();
        hasher = new CanonicalJsonHasher(objectMapper);
        normalizer = new PricingQuoteRequestNormalizer();

        service = new PricingQuoteCommitmentService(
                properties,
                engine,
                new PricingDefinitionFactory(),
                new PricingQuoteAggregates(),
                normalizer,
                hasher,
                quoteRepository,
                quoteLineRepository,
                commitmentRepository,
                rateProfileRepository,
                reservationAddOnService);

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
                .inventoryProtectionMode(InventoryProtectionMode.PACKAGE_ENTITLEMENT)
                .effectiveFromUtc(Instant.parse("2026-01-01T00:00:00Z"))
                .active(true)
                .createdAtUtc(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        roomType = RoomType.builder()
                .code("STANDARD")
                .typeName("Phòng tiêu chuẩn")
                .maxGuests(2)
                .build();
        roomType.setId(1L);
        rateProfile = RoomRateProfile.builder()
                .id(21L)
                .roomType(roomType)
                .stayPolicyVersion(policy)
                .profileVersion(1)
                .includedGuests(1)
                .firstBlockMinutes(120)
                .firstBlockPrice(new BigDecimal("70000"))
                .extraUnitMinutes(60)
                .extraUnitPrice(new BigDecimal("20000"))
                .overnightPrice(new BigDecimal("170000"))
                .dailyPrice(new BigDecimal("300000"))
                .extraGuestPrice(new BigDecimal("50000"))
                .extraGuestBillingMode(ExtraGuestBillingMode.PER_PACKAGE_CYCLE)
                .effectiveFromUtc(Instant.parse("2026-01-01T00:00:00Z"))
                .active(true)
                .createdAtUtc(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        request = request();
        breakdown = engine.calculate(
                new PricingRequest(
                        request.getCheckIn(), request.getCheckOut(), 1, 1),
                new PricingDefinitionFactory().roomRate(rateProfile),
                new PricingDefinitionFactory().stayPolicy(policy));
        quote = quote(Instant.now().plusSeconds(600));
        quoteLine = PricingQuoteLine.builder()
                .pricingQuote(quote)
                .roomType(roomType)
                .rateProfile(rateProfile)
                .roomTypeCodeSnapshot("STANDARD")
                .rateProfileVersion(1)
                .roomQuantity(1)
                .lineGuestCount(1)
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

    @Test
    void revalidatesAndReturnsAuthoritativeCommitment() {
        stubValidQuote();

        PricingQuoteCommitmentService.Commitment commitment =
                service.validateForReservation(
                        request, Map.of(roomType.getId(), roomType));

        assertEquals(quote.getId(), commitment.quote().getId());
        assertEquals(1, commitment.lines().size());
        assertMoney("170000", commitment.roomCharge());
        assertMoney("170000", commitment.totalAmount());
        assertEquals(
                LocalDateTime.of(2026, 8, 2, 12, 30),
                commitment.inventoryProtectedUntil());
        verify(quoteRepository).findByIdForUpdate(quote.getId());
        verify(rateProfileRepository).findByIdForUpdate(rateProfile.getId());
        verify(reservationAddOnService)
                .quoteBookingTimeForPackageCycles(
                        anyList(), eq(1), eq(1));
    }

    @Test
    void rejectsExpiredQuoteBeforeAnyRateOrServiceMutation() {
        quote = quote(Instant.now().minusSeconds(1));
        when(quoteRepository.findByIdForUpdate(quote.getId()))
                .thenReturn(Optional.of(quote));
        when(commitmentRepository.existsByPricingQuoteId(quote.getId()))
                .thenReturn(false);

        AppException exception = assertThrows(
                AppException.class,
                () -> service.validateForReservation(
                        request, Map.of(roomType.getId(), roomType)));

        assertEquals(ErrorCode.PRICING_QUOTE_EXPIRED, exception.getErrorCode());
        verifyNoInteractions(
                rateProfileRepository,
                reservationAddOnService,
                quoteLineRepository);
    }

    @Test
    void rejectsReusingQuoteForSecondReservation() {
        when(quoteRepository.findByIdForUpdate(quote.getId()))
                .thenReturn(Optional.of(quote));
        when(commitmentRepository.existsByPricingQuoteId(quote.getId()))
                .thenReturn(true);

        AppException exception = assertThrows(
                AppException.class,
                () -> service.validateForReservation(
                        request, Map.of(roomType.getId(), roomType)));

        assertEquals(ErrorCode.PRICING_QUOTE_MISMATCH, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("đã được dùng"));
    }

    @Test
    void explicitFullCutoverRejectsClientsThatOmitQuote() {
        properties.setEngineV2RequireQuote(true);

        AppException exception = assertThrows(
                AppException.class,
                service::validateLegacyReservationAllowed);

        assertEquals(
                ErrorCode.PRICING_QUOTE_MISMATCH,
                exception.getErrorCode());
        assertTrue(exception.getMessage().contains("bắt buộc"));
    }

    @Test
    void rejectsReservationPayloadChangedAfterQuote() {
        when(quoteRepository.findByIdForUpdate(quote.getId()))
                .thenReturn(Optional.of(quote));
        when(commitmentRepository.existsByPricingQuoteId(quote.getId()))
                .thenReturn(false);
        request.setCheckOut(request.getCheckOut().plusMinutes(1));

        AppException exception = assertThrows(
                AppException.class,
                () -> service.validateForReservation(
                        request, Map.of(roomType.getId(), roomType)));

        assertEquals(ErrorCode.PRICING_QUOTE_MISMATCH, exception.getErrorCode());
        verifyNoInteractions(rateProfileRepository, reservationAddOnService);
    }

    @Test
    void returnsPriceChangedWhenQuotedRateWasClosed() {
        stubQuoteBeforeRate();
        rateProfile.setActive(false);
        when(rateProfileRepository.findByIdForUpdate(rateProfile.getId()))
                .thenReturn(Optional.of(rateProfile));

        AppException exception = assertThrows(
                AppException.class,
                () -> service.validateForReservation(
                        request, Map.of(roomType.getId(), roomType)));

        assertEquals(ErrorCode.PRICE_CHANGED, exception.getErrorCode());
        verifyNoInteractions(reservationAddOnService);
    }

    @Test
    void recordsOneTimeQuoteConsumptionAfterReservationExists() {
        Reservation reservation = Reservation.builder()
                .reservationCode("RES-QUOTE-TEST")
                .customerProfile(CustomerProfile.builder()
                        .fullName("Quote guest")
                        .build())
                .checkIn(request.getCheckIn())
                .checkOut(request.getCheckOut())
                .totalAmount(new BigDecimal("170000"))
                .guestCount(1)
                .build();
        PricingQuoteCommitmentService.Commitment commitment =
                new PricingQuoteCommitmentService.Commitment(
                        quote,
                        List.of(),
                        new ReservationAddOnService.BookingQuote(
                                List.of(), BigDecimal.ZERO),
                        new BigDecimal("170000"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("170000"),
                        request.getCheckOut(),
                        breakdown.appliedPackage());

        service.recordCommitment(commitment, reservation);

        verify(commitmentRepository).save(argThat(saved ->
                saved.getPricingQuote() == quote
                        && saved.getReservation() == reservation
                        && saved.getCommittedAtUtc() != null));
    }

    private void stubValidQuote() {
        stubQuoteBeforeRate();
        when(rateProfileRepository.findByIdForUpdate(rateProfile.getId()))
                .thenReturn(Optional.of(rateProfile));
        when(reservationAddOnService
                .quoteBookingTimeForPackageCycles(
                        anyList(), eq(1), eq(1)))
                .thenReturn(new ReservationAddOnService.BookingQuote(
                        List.of(), BigDecimal.ZERO));
    }

    private void stubQuoteBeforeRate() {
        when(quoteRepository.findByIdForUpdate(quote.getId()))
                .thenReturn(Optional.of(quote));
        when(commitmentRepository.existsByPricingQuoteId(quote.getId()))
                .thenReturn(false);
        when(quoteLineRepository.findByPricingQuoteIdOrderByIdAsc(quote.getId()))
                .thenReturn(List.of(quoteLine));
    }

    private PricingQuote quote(Instant expiresAt) {
        PricingQuoteRequest quoteRequest = PricingQuoteRequest.builder()
                .checkIn(request.getCheckIn())
                .checkOut(request.getCheckOut())
                .guestCount(1)
                .rooms(List.of(PricingQuoteRoomRequest.builder()
                        .roomTypeId(1L)
                        .quantity(1)
                        .lineGuestCount(1)
                        .build()))
                .services(List.of())
                .build();
        ObjectNode responseJson = objectMapper.createObjectNode();
        responseJson.putArray("services");
        LocalDateTime protectedUntil = new PricingQuoteAggregates()
                .inventoryProtectedUntil(
                        request.getCheckOut(), List.of(breakdown), policy);
        return PricingQuote.builder()
                .id(request.getQuoteId())
                .stayPolicyVersion(policy)
                .pricingAlgorithmVersion(PricingAlgorithmVersion.MOTEL_PACKAGE_V2)
                .checkIn(request.getCheckIn())
                .checkOut(request.getCheckOut())
                .guestCount(1)
                .roomCharge(breakdown.roomCharge())
                .extraGuestCharge(BigDecimal.ZERO)
                .serviceCharge(BigDecimal.ZERO)
                .totalAmount(breakdown.roomCharge())
                .inventoryProtectedUntil(protectedUntil)
                .requestHash(hasher.hash(normalizer.normalize(quoteRequest)))
                .quoteHash(request.getQuoteHash())
                .requestJson(hasher.canonicalTree(normalizer.normalize(quoteRequest)))
                .responseJson(responseJson)
                .createdAtUtc(Instant.now())
                .expiresAtUtc(expiresAt)
                .build();
    }

    private CreateReservationRequest request() {
        return CreateReservationRequest.builder()
                .checkIn(LocalDateTime.of(2026, 8, 1, 22, 0))
                .checkOut(LocalDateTime.of(2026, 8, 2, 5, 0))
                .guestCount(1)
                .roomTypes(List.of(RoomTypeItemRequest.builder()
                        .roomTypeId(1L)
                        .quantity(1)
                        .lineGuestCount(1)
                        .build()))
                .services(List.of())
                .quoteId(UUID.randomUUID())
                .quoteHash("a".repeat(64))
                .build();
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
