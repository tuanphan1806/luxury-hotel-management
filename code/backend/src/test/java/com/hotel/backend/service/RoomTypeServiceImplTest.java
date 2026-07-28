package com.hotel.backend.service;

import com.hotel.backend.config.PricingV2Properties;
import com.hotel.backend.dto.response.RoomTypeResponse;
import com.hotel.backend.entity.RoomRateProfile;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.repository.FacilityRepository;
import com.hotel.backend.repository.ReviewRepository;
import com.hotel.backend.repository.RoomRateProfileRepository;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
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
                pricingV2Properties);
    }

    /**
     * Danh sách loại phòng phải lấy toàn bộ thống kê review bằng đúng một
     * aggregate query, không chạy AVG/COUNT riêng cho từng loại phòng.
     */
    @Test
    void getAllLoadsReviewStatisticsInOneAggregateQuery() {
        RoomType standard = roomType(11L, "Standard");
        RoomType deluxe = roomType(12L, "Deluxe");
        when(roomTypeRepository.findAllWithFacilities()).thenReturn(List.of(standard, deluxe));
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
        when(roomTypeRepository.findAllWithFacilities())
                .thenReturn(List.of(standard, deluxe));
        when(reviewRepository.summarizeByRoomTypeIds(List.of(11L, 12L)))
                .thenReturn(List.of());
        when(pricingV2Properties.supportsRoomType("STANDARD"))
                .thenReturn(true);
        when(pricingV2Properties.supportsRoomType("DELUXE"))
                .thenReturn(true);
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

    private RoomType roomType(Long id, String name) {
        RoomType roomType = RoomType.builder()
                .code(name.toUpperCase())
                .typeName(name)
                .price(BigDecimal.valueOf(100_000L))
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
