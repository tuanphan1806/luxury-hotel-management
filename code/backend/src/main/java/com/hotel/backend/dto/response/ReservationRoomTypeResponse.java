package com.hotel.backend.dto.response;
 
import com.hotel.backend.entity.ReservationRoomType;
import lombok.*;
 
import java.math.BigDecimal;
 
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationRoomTypeResponse {
 
    private Long id;
    private Long roomTypeId;
    private String roomTypeName;
    private String roomTypeNameEn;
    private Integer quantity;
    private BigDecimal roomPrice;
    private BigDecimal subtotal;
    private RoomHoldResponse roomHold;
 
    public static ReservationRoomTypeResponse from(ReservationRoomType rrt) {
        return ReservationRoomTypeResponse.builder()
                .id(rrt.getId())
                .roomTypeId(rrt.getRoomType().getId())
                .roomTypeName(rrt.getRoomType().getTypeName())
                .roomTypeNameEn(rrt.getRoomType().getTypeNameEn())
                .quantity(rrt.getQuantity())
                .roomPrice(rrt.getRoomPrice())
                .subtotal(rrt.getSubtotal())
                .build();
    }
}
