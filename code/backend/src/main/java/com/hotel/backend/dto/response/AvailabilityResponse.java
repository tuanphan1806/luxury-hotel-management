package com.hotel.backend.dto.response;
 
import lombok.*;
 
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
    private BigDecimal pricePerHour;   // giá giờ đầu tiên
    private BigDecimal estimatedPricePerRoom;
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
