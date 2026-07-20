package com.hotel.backend.service;

import com.hotel.backend.dto.response.RoomTypeResponse;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.repository.FacilityRepository;
import com.hotel.backend.repository.ReviewRepository;
import com.hotel.backend.repository.RoomTypeRepository;
import com.hotel.backend.service.Impl.RoomTypeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private RoomTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RoomTypeServiceImpl(
                roomTypeRepository,
                facilityRepository,
                reviewRepository,
                mediaAssetService,
                reservationAuditService);
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

    private RoomType roomType(Long id, String name) {
        RoomType roomType = RoomType.builder()
                .typeName(name)
                .price(BigDecimal.valueOf(100_000L))
                .maxGuests(2)
                .build();
        roomType.setId(id);
        return roomType;
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
