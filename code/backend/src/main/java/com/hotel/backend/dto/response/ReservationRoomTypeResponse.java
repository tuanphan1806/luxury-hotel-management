package com.hotel.backend.dto.response;

import com.hotel.backend.constant.StayPackage;
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
    private Integer maxGuestsPerRoom;
    private BigDecimal roomPrice;
    private BigDecimal subtotal;
    private BigDecimal plannedRoomCharge;
    private BigDecimal actualRoomCharge;
    private BigDecimal projectedRoomCharge;
    private BigDecimal plannedExtraGuestCharge;
    private BigDecimal extraGuestCharge;
    private BigDecimal projectedExtraGuestCharge;
    private BigDecimal plannedSubtotal;
    private BigDecimal actualSubtotal;
    private BigDecimal projectedSubtotal;
    private Integer lineGuestCount;
    private BigDecimal minimumCommittedRoomCharge;
    private StayPackage appliedPackage;
    private StayPackage projectedPackage;
    private StayPackage maxPackageReached;
    private String pricingSnapshotHash;
    private RoomHoldResponse roomHold;
 
    public static ReservationRoomTypeResponse from(ReservationRoomType rrt) {
        return ReservationRoomTypeResponse.builder()
                .id(rrt.getId())
                .roomTypeId(rrt.getRoomType().getId())
                .roomTypeName(rrt.getRoomType().getTypeName())
                .roomTypeNameEn(rrt.getRoomType().getTypeNameEn())
                .quantity(rrt.getQuantity())
                .maxGuestsPerRoom(rrt.getRoomType().getMaxGuests() != null
                        ? Math.max(1, rrt.getRoomType().getMaxGuests())
                        : 2)
                .roomPrice(rrt.getRoomPrice())
                .subtotal(rrt.getSubtotal())
                .actualSubtotal(rrt.getSubtotal())
                .lineGuestCount(rrt.getLineGuestCount())
                .minimumCommittedRoomCharge(
                        rrt.getMinimumCommittedRoomCharge())
                .maxPackageReached(rrt.getMaxPackageReached())
                .build();
    }
}
