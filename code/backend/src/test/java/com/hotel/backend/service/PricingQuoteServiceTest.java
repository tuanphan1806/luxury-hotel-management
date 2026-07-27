package com.hotel.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.config.PricingV2Properties;
import com.hotel.backend.constant.ExtraGuestBillingMode;
import com.hotel.backend.constant.InventoryProtectionMode;
import com.hotel.backend.constant.StayPackage;
import com.hotel.backend.dto.request.PricingQuoteRequest;
import com.hotel.backend.dto.request.PricingQuoteRoomRequest;
import com.hotel.backend.dto.request.ServiceOrderRequest;
import com.hotel.backend.dto.response.PricingQuoteResponse;
import com.hotel.backend.entity.RoomRateProfile;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.entity.StayPolicyVersion;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.pricing.MotelPackagePricingEngine;
import com.hotel.backend.pricing.PricingDefinitionFactory;
import com.hotel.backend.pricing.PricingQuoteAggregates;
import com.hotel.backend.pricing.PricingQuoteRequestNormalizer;
import com.hotel.backend.repository.PricingQuoteLineRepository;
import com.hotel.backend.repository.PricingQuoteRepository;
import com.hotel.backend.repository.RoomRateProfileRepository;
import com.hotel.backend.repository.RoomTypeRepository;
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
import java.util.List;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PricingQuoteServiceTest {

    @Mock
    private RoomTypeRepository roomTypeRepository;
    @Mock
    private RoomRateProfileRepository rateProfileRepository;
    @Mock
    private ReservationAddOnService reservationAddOnService;
    @Mock
    private PricingQuoteRepository quoteRepository;
    @Mock
    private PricingQuoteLineRepository quoteLineRepository;

    private PricingV2Properties properties;
    private PricingQuoteService service;
    private RoomType roomType;
    private RoomRateProfile rateProfile;

    @BeforeEach
    void setUp() {
        properties = new PricingV2Properties();
        properties.setEngineV2Enabled(true);
        properties.setQuoteTtlMinutes(15);

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new PricingQuoteService(
                new MotelPackagePricingEngine(),
                new PricingDefinitionFactory(),
                properties,
                roomTypeRepository,
                rateProfileRepository,
                reservationAddOnService,
                quoteRepository,
                quoteLineRepository,
                new CanonicalJsonHasher(objectMapper),
                new PricingQuoteRequestNormalizer(),
                new PricingQuoteAggregates(),
                objectMapper,
                new StayWindowValidationService(properties));

        StayPolicyVersion policy = StayPolicyVersion.builder()
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
    }

    @Test
    void createsVersionedQuoteAndProtectsFullOvernightEntitlement() {
        when(roomTypeRepository.findAllById(List.of(1L)))
                .thenReturn(List.of(roomType));
        when(rateProfileRepository.findEffectiveByRoomTypeIds(
                eq(List.of(1L)), any(Instant.class)))
                .thenReturn(List.of(rateProfile));
        when(reservationAddOnService
                .previewBookingTimeForPackageCycles(
                        anyList(), eq(1), eq(1)))
                .thenReturn(new ReservationAddOnService.BookingQuote(
                        List.of(), BigDecimal.ZERO));
        when(quoteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PricingQuoteResponse response = service.createQuote(request(1));

        assertEquals(StayPackage.OVERNIGHT, response.getDisplayPackageSummary());
        assertEquals(LocalDateTime.of(2026, 8, 2, 12, 30),
                response.getInventoryProtectedUntil());
        assertMoney("170000", response.getRoomCharge());
        assertMoney("0", response.getExtraGuestCharge());
        assertMoney("170000", response.getTotalAmount());
        assertNotNull(response.getQuoteId());
        assertNotNull(response.getQuoteExpiresAtUtc());
        assertEquals(64, response.getQuoteHash().length());
        assertEquals(21L, response.getLines().get(0).getRateProfileId());

        ArgumentCaptor<com.hotel.backend.entity.PricingQuote> quoteCaptor =
                ArgumentCaptor.forClass(com.hotel.backend.entity.PricingQuote.class);
        verify(quoteRepository).save(quoteCaptor.capture());
        assertEquals(response.getQuoteHash(), quoteCaptor.getValue().getQuoteHash());
        verify(quoteLineRepository).saveAll(argThat(lines ->
                ((List<?>) lines).size() == 1));
    }

    @Test
    void rejectsAReservationGuestTotalThatDoesNotEqualLineAllocation() {
        AppException exception =
                assertThrows(AppException.class, () -> service.createQuote(request(2)));

        assertEquals(ErrorCode.INVALID_REQUEST, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("phân bổ"));
        verifyNoInteractions(roomTypeRepository, rateProfileRepository, quoteRepository);
    }

    @Test
    void rejectsNullRoomItemAsInvalidRequestInsteadOfThrowingNullPointer() {
        PricingQuoteRequest request = request(1);
        request.setRooms(Collections.singletonList(null));

        AppException exception =
                assertThrows(AppException.class, () -> service.createQuote(request));

        assertEquals(ErrorCode.INVALID_REQUEST, exception.getErrorCode());
        verifyNoInteractions(roomTypeRepository, rateProfileRepository, quoteRepository);
    }

    @Test
    void rejectsNullServiceItemAsInvalidRequestInsteadOfThrowingNullPointer() {
        PricingQuoteRequest request = request(1);
        request.setServices(Collections.singletonList((ServiceOrderRequest) null));

        AppException exception =
                assertThrows(AppException.class, () -> service.createQuote(request));

        assertEquals(ErrorCode.INVALID_REQUEST, exception.getErrorCode());
        verifyNoInteractions(roomTypeRepository, rateProfileRepository, quoteRepository);
    }

    @Test
    void rejectsQuoteWhileFeatureFlagIsOff() {
        properties.setEngineV2Enabled(false);

        AppException exception =
                assertThrows(AppException.class, () -> service.createQuote(request(1)));

        assertEquals(ErrorCode.PRICING_ENGINE_DISABLED, exception.getErrorCode());
        verifyNoInteractions(roomTypeRepository, rateProfileRepository, quoteRepository);
    }

    @Test
    void canaryListPreventsAnUnlistedRoomTypeFromUsingV2() {
        properties.setEngineV2RoomTypeCodes("DELUXE");
        when(roomTypeRepository.findAllById(List.of(1L)))
                .thenReturn(List.of(roomType));

        AppException exception =
                assertThrows(AppException.class, () -> service.createQuote(request(1)));

        assertEquals(ErrorCode.PRICING_ENGINE_DISABLED, exception.getErrorCode());
        verifyNoInteractions(rateProfileRepository, quoteRepository);
    }

    @Test
    void rejectsAmbiguousEffectiveRatesInsteadOfSilentlyChoosingOne() {
        RoomRateProfile overlapping = RoomRateProfile.builder()
                .id(22L)
                .roomType(roomType)
                .stayPolicyVersion(rateProfile.getStayPolicyVersion())
                .profileVersion(2)
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
        when(roomTypeRepository.findAllById(List.of(1L)))
                .thenReturn(List.of(roomType));
        when(rateProfileRepository.findEffectiveByRoomTypeIds(
                eq(List.of(1L)), any(Instant.class)))
                .thenReturn(List.of(overlapping, rateProfile));

        AppException exception = assertThrows(
                AppException.class,
                () -> service.createQuote(request(1)));

        assertEquals(ErrorCode.PRICING_PROFILE_NOT_FOUND, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("chồng thời gian"));
        verifyNoInteractions(quoteRepository, quoteLineRepository);
    }

    private PricingQuoteRequest request(int guestCount) {
        return PricingQuoteRequest.builder()
                .checkIn(LocalDateTime.of(2026, 8, 1, 22, 0))
                .checkOut(LocalDateTime.of(2026, 8, 2, 5, 0))
                .guestCount(guestCount)
                .rooms(List.of(PricingQuoteRoomRequest.builder()
                        .roomTypeId(1L)
                        .quantity(1)
                        .lineGuestCount(1)
                        .build()))
                .services(List.of())
                .build();
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
