package com.hotel.backend.dto.response;

import com.hotel.backend.constant.PricingTransitionReason;
import com.hotel.backend.constant.StayPackage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutRoomChargeLineResponse {
    private Long reservationRoomTypeId;
    private Long roomTypeId;
    private String roomTypeName;
    private String roomTypeNameEn;
    private Integer roomQuantity;
    private Integer actualGuestCount;
    private StayPackage appliedPackage;
    private PricingTransitionReason transitionReason;
    private Long billableMinutes;
    private Integer firstBlockMinutes;
    private Long firstBlockPrice;
    private Integer extraUnitMinutes;
    private Long extraUnitPrice;
    private Integer graceMinutes;
    private Long overnightPrice;
    private Long dailyPrice;
    private Long cycleSubtotal;
    private Long pricingAdjustment;
    private Long amount;
    private List<CheckoutRoomChargeCycleResponse> cycles;
}
