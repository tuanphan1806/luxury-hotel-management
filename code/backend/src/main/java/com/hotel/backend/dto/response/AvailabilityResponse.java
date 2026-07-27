package com.hotel.backend.dto.response;
 
import lombok.*;
import com.hotel.backend.constant.StayPackage;
 
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
 
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailabilityResponse {
 
    private Long roomTypeId;
    private String roomTypeName;
    private String roomTypeNameEn;
    private String description;
    private String descriptionEn;
    /**
     * Legacy compatibility field. New clients should use firstBlockPrice and
     * firstBlockMinutes because Pricing V2 starts with a two-hour block.
     */
    private BigDecimal pricePerHour;
    private Integer firstBlockMinutes;
    private BigDecimal firstBlockPrice;
    private BigDecimal estimatedPricePerRoom;
    private StayPackage estimatedPackage;
    private int maxGuestsPerRoom;
    private String imageUrl;
    private List<String> imageUrls;
    private LocalDateTime checkIn;
    private LocalDateTime checkOut;
    private long totalHours;           // tổng số giờ thuê
    private int totalRooms;
    private int bookedRooms;
    private int heldRooms;
    private int availableRooms;
}
