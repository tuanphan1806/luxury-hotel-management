package com.hotel.backend.service;

import com.hotel.backend.config.PricingV2Properties;
import com.hotel.backend.dto.request.RoomTypeRequest;
import com.hotel.backend.dto.request.RoomTypeStatusRequest;
import com.hotel.backend.dto.response.RoomTypeResponse;
import com.hotel.backend.entity.RoomRateProfile;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.repository.FacilityRepository;
import com.hotel.backend.repository.ReviewRepository;
import com.hotel.backend.repository.RoomRateProfileRepository;
import com.hotel.backend.repository.PricingQuoteLineRepository;
import com.hotel.backend.repository.ReservationRoomTypeRepository;
import com.hotel.backend.repository.RoomRepository;
import com.hotel.backend.repository.RoomTypeRepository;
import com.hotel.backend.service.Impl.RoomTypeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomTypeServiceImplTest {

    @Mock RoomTypeRepository roomTypeRepository;
    @Mock FacilityRepository facilityRepository;
    @Mock ReviewRepository reviewRepository;
    @Mock MediaAssetService mediaAssetService;
    @Mock ReservationAuditService reservationAuditService;
    @Mock RoomRateProfileRepository roomRateProfileRepository;
    @Mock PricingV2Properties pricingV2Properties;
    @Mock RoomRateProfileManagementService roomRateProfileManagementService;
    @Mock RoomRepository roomRepository;
    @Mock ReservationRoomTypeRepository reservationRoomTypeRepository;
    @Mock PricingQuoteLineRepository pricingQuoteLineRepository;

    private RoomTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RoomTypeServiceImpl(
                roomTypeRepository,
                facilityRepository,
                reviewRepository,
                mediaAssetService,
                reservationAuditService,
                roomRateProfileRepository,
                pricingV2Properties,
                roomRateProfileManagementService,
                roomRepository,
                reservationRoomTypeRepository,
                pricingQuoteLineRepository);
    }

    /**
     * Danh sách loại phòng phải lấy toàn bộ thống kê review bằng đúng một
     * aggregate query, không chạy AVG/COUNT riêng cho từng loại phòng.
     */
    @Test
    void getAllLoadsReviewStatisticsInOneAggregateQuery() {
        RoomType standard = roomType(11L, "Standard");
        RoomType deluxe = roomType(12L, "Deluxe");
        when(roomTypeRepository.findAllActiveWithFacilities()).thenReturn(List.of(standard, deluxe));
        when(roomRateProfileRepository.findEffectiveByRoomTypeIds(
                eq(List.of(11L, 12L)), any(Instant.class)))
                .thenReturn(List.of(
                        rate(standard, "70000", "20000", "170000", "300000"),
                        rate(deluxe, "100000", "25000", "220000", "400000")));
        when(reviewRepository.summarizeByRoomTypeIds(List.of(11L, 12L)))
                .thenReturn(List.of(summary(11L, 4.26, 7L)));

        List<RoomTypeResponse> result = service.getAll();

        assertEquals(2, result.size());
        assertEquals(4.3, result.get(0).getAverageRating());
        assertEquals(7L, result.get(0).getTotalReviews());
        assertEquals(0.0, result.get(1).getAverageRating());
        assertEquals(0L, result.get(1).getTotalReviews());
        verify(reviewRepository, times(1)).summarizeByRoomTypeIds(List.of(11L, 12L));
    }

    @Test
    void getAllPublishesTheEffectivePackageRateInOneBatchQuery() {
        RoomType standard = roomType(11L, "STANDARD");
        RoomType deluxe = roomType(12L, "DELUXE");
        when(roomTypeRepository.findAllActiveWithFacilities())
                .thenReturn(List.of(standard, deluxe));
        when(reviewRepository.summarizeByRoomTypeIds(List.of(11L, 12L)))
                .thenReturn(List.of());
        when(pricingV2Properties.supportsRoomType("STANDARD"))
                .thenReturn(true);
        when(pricingV2Properties.supportsRoomType("DELUXE"))
                .thenReturn(true);
        when(pricingV2Properties.isEngineV2Enabled()).thenReturn(true);
        when(roomRateProfileRepository.findEffectiveByRoomTypeIds(
                eq(List.of(11L, 12L)), any(Instant.class)))
                .thenReturn(List.of(
                        rate(standard, "70000", "20000", "170000", "300000"),
                        rate(deluxe, "100000", "25000", "220000", "400000")));

        List<RoomTypeResponse> result = service.getAll();

        assertTrue(result.get(0).getPackagePricingEnabled());
        assertTrue(result.get(0).getPricingAvailable());
        assertEquals(new BigDecimal("70000"), result.get(0).getFirstBlockPrice());
        assertEquals(new BigDecimal("170000"), result.get(0).getOvernightPrice());
        assertEquals(new BigDecimal("400000"), result.get(1).getDailyPrice());
        verify(roomRateProfileRepository, times(1))
                .findEffectiveByRoomTypeIds(
                        eq(List.of(11L, 12L)), any(Instant.class));
    }

    @Test
    void createPersistsTheCompleteVersionedRatePlan() {
        RoomTypeRequest request = rateRequest();
        when(roomTypeRepository.saveAndFlush(any(RoomType.class)))
                .thenAnswer(invocation -> {
                    RoomType saved = invocation.getArgument(0);
                    saved.setId(21L);
                    saved.setCode("CUSTOM_NEW");
                    return saved;
                });
        when(mediaAssetService.replaceReferences(
                any(), any(), any(), any(), eq(21L), eq(3)))
                .thenReturn(List.of());
        when(roomRateProfileManagementService.applyImmediate(
                any(RoomType.class), eq(request)))
                .thenAnswer(invocation -> rate(
                        invocation.getArgument(0),
                        "70000", "20000", "170000", "300000"));

        RoomTypeResponse response = service.create(request);

        assertEquals(new BigDecimal("70000"), response.getFirstBlockPrice());
        assertEquals(new BigDecimal("170000"), response.getOvernightPrice());
        verify(roomRateProfileManagementService).validate(request);
        verify(roomRateProfileManagementService).applyImmediate(
                any(RoomType.class), eq(request));
    }

    @Test
    void updateRejectsCapacityReductionThatWouldBreakActiveReservations() {
        RoomType roomType = roomType(11L, "STANDARD");
        roomType.setMaxGuests(3);
        RoomTypeRequest request = rateRequest();
        request.setMaxGuests(2);
        request.setIncludedGuests(2);
        when(roomTypeRepository.findByIdForUpdate(11L))
                .thenReturn(java.util.Optional.of(roomType));
        when(roomRateProfileRepository.findEffectiveByRoomTypeIds(
                eq(List.of(11L)), any(Instant.class)))
                .thenReturn(List.of(rate(
                        roomType, "70000", "20000", "170000", "300000")));
        when(reservationRoomTypeRepository
                .findActiveReservationCodesExceedingCapacity(11L, 2))
                .thenReturn(List.of("RES-ACTIVE-001", "RES-ACTIVE-002"));

        AppException exception = assertThrows(
                AppException.class,
                () -> service.update(11L, request));

        assertTrue(exception.getMessage().contains("RES-ACTIVE-001"));
        assertTrue(exception.getMessage().contains("RES-ACTIVE-002"));
        verify(roomRateProfileManagementService, never())
                .applyImmediate(any(RoomType.class), any(RoomTypeRequest.class));
        verify(roomTypeRepository, never()).saveAndFlush(any(RoomType.class));
    }

    @Test
    void priceRangeUsesThePublishedOvernightRateWhenPricingV2IsEnabled() {
        RoomType standard = roomType(11L, "STANDARD");
        RoomType deluxe = roomType(12L, "DELUXE");
        when(pricingV2Properties.isEngineV2Enabled()).thenReturn(true);
        when(pricingV2Properties.supportsRoomType("DELUXE")).thenReturn(true);
        when(roomTypeRepository.findAllActiveWithFacilities())
                .thenReturn(List.of(standard, deluxe));
        when(roomRateProfileRepository.findEffectiveByRoomTypeIds(
                eq(List.of(11L, 12L)), any(Instant.class)))
                .thenReturn(List.of(
                        rate(standard, "70000", "20000", "170000", "300000"),
                        rate(deluxe, "100000", "25000", "220000", "400000")));
        when(reviewRepository.summarizeByRoomTypeIds(List.of(12L)))
                .thenReturn(List.of());

        List<RoomTypeResponse> result = service.getByPriceRange(
                new BigDecimal("180000"), new BigDecimal("250000"));

        assertEquals(1, result.size());
        assertEquals(12L, result.get(0).getId());
        assertEquals(new BigDecimal("220000"), result.get(0).getOvernightPrice());
    }

    @Test
    void deactivatePreservesRoomTypeAndRequiresAReason() {
        RoomType roomType = roomType(11L, "STANDARD");
        RoomTypeStatusRequest request = new RoomTypeStatusRequest();
        request.setActive(false);
        request.setReason("Ngừng kinh doanh hạng phòng");
        when(roomTypeRepository.findByIdForUpdate(11L)).thenReturn(java.util.Optional.of(roomType));
        when(roomTypeRepository.saveAndFlush(roomType)).thenReturn(roomType);
        when(reviewRepository.summarizeByRoomTypeIds(List.of(11L))).thenReturn(List.of());

        RoomTypeResponse response = service.setActive(11L, request);

        assertFalse(response.getActive());
        verify(roomTypeRepository).saveAndFlush(roomType);
        verify(roomTypeRepository, never()).delete(any());
    }

    @Test
    void deleteRejectsActiveOrHistoricallyUsedRoomType() {
        RoomType active = roomType(11L, "STANDARD");
        when(roomTypeRepository.findByIdForUpdate(11L)).thenReturn(java.util.Optional.of(active));
        assertThrows(AppException.class, () -> service.delete(11L));

        RoomType inactive = roomType(12L, "DELUXE");
        inactive.setActive(false);
        when(roomTypeRepository.findByIdForUpdate(12L)).thenReturn(java.util.Optional.of(inactive));
        when(reservationRoomTypeRepository.countByRoomTypeId(12L)).thenReturn(1L);
        assertThrows(AppException.class, () -> service.delete(12L));

        verify(roomTypeRepository, never()).delete(any());
    }

    @Test
    void deletePermanentlyRemovesOnlyUnusedInactiveRoomType() {
        RoomType inactive = roomType(12L, "DELUXE");
        inactive.setActive(false);
        when(roomTypeRepository.findByIdForUpdate(12L)).thenReturn(java.util.Optional.of(inactive));
        when(roomRateProfileRepository.findEffectiveByRoomTypeIds(
                eq(List.of(12L)), any(Instant.class))).thenReturn(List.of());

        service.delete(12L);

        verify(roomTypeRepository).delete(inactive);
        verify(roomTypeRepository).flush();
        verify(mediaAssetService).releaseReferences(
                eq(List.of()), any(), eq(12L));
    }

    private RoomTypeRequest rateRequest() {
        return RoomTypeRequest.builder()
                .typeName("Phòng mới")
                .maxGuests(2)
                .includedGuests(1)
                .firstBlockPrice(new BigDecimal("70000"))
                .extraUnitPrice(new BigDecimal("20000"))
                .overnightPrice(new BigDecimal("170000"))
                .dailyPrice(new BigDecimal("300000"))
                .extraGuestPrice(new BigDecimal("50000"))
                .build();
    }

    private RoomType roomType(Long id, String name) {
        RoomType roomType = RoomType.builder()
                .code(name.toUpperCase())
                .typeName(name)
                .active(true)
                .maxGuests(2)
                .build();
        roomType.setId(id);
        return roomType;
    }

    private RoomRateProfile rate(
            RoomType roomType,
            String firstBlockPrice,
            String extraUnitPrice,
            String overnightPrice,
            String dailyPrice) {
        return RoomRateProfile.builder()
                .roomType(roomType)
                .includedGuests(1)
                .firstBlockMinutes(120)
                .firstBlockPrice(new BigDecimal(firstBlockPrice))
                .extraUnitMinutes(60)
                .extraUnitPrice(new BigDecimal(extraUnitPrice))
                .overnightPrice(new BigDecimal(overnightPrice))
                .dailyPrice(new BigDecimal(dailyPrice))
                .extraGuestPrice(new BigDecimal("50000"))
                .build();
    }

    private ReviewRepository.RoomTypeRatingSummary summary(
            Long roomTypeId,
            Double averageRating,
            Long totalReviews) {
        return new ReviewRepository.RoomTypeRatingSummary() {
            @Override
            public Long getRoomTypeId() {
                return roomTypeId;
            }

            @Override
            public Double getAverageRating() {
                return averageRating;
            }

            @Override
            public Long getTotalReviews() {
                return totalReviews;
            }
        };
    }
}
