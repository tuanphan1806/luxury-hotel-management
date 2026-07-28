package com.hotel.backend.dto.response;

import com.hotel.backend.constant.PricingTransitionReason;
import com.hotel.backend.constant.StayPackage;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PricingQuoteCycleResponse {
    private Integer sequence;
    private StayPackage appliedPackage;
    private PricingTransitionReason transitionReason;
    private LocalDateTime billableStart;
    private LocalDateTime plannedSegmentEnd;
    private LocalDateTime packageIncludedCheckout;
    private Long billableMinutes;
    private Integer chargedExtraUnits;
    private BigDecimal roomChargePerRoom;
}
