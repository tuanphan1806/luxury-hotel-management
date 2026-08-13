package com.hotel.backend.dto.response;

import com.hotel.backend.constant.PricingTransitionReason;
import com.hotel.backend.constant.StayPackage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutRoomChargeCycleResponse {
    private Integer sequence;
    private StayPackage appliedPackage;
    private PricingTransitionReason transitionReason;
    private LocalDateTime billableStart;
    private LocalDateTime billableEnd;
    private LocalDateTime includedCheckout;
    private Long billableMinutes;
    private Integer chargedExtraUnits;
    private Long unitPrice;
    private Integer roomQuantity;
    private Long amount;
}
