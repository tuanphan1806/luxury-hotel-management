package com.hotel.backend.dto.response;

import com.hotel.backend.constant.PricingTransitionReason;
import com.hotel.backend.constant.StayClassification;
import com.hotel.backend.constant.StayPackage;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PricingQuoteLineResponse {
    private Long roomTypeId;
    private String roomTypeCode;
    private String roomTypeName;
    private Integer quantity;
    private Integer lineGuestCount;
    private Long rateProfileId;
    private Integer rateProfileVersion;
    private StayClassification stayClassification;
    private StayPackage appliedPackage;
    private PricingTransitionReason transitionReason;
    private LocalDateTime packageIncludedCheckout;
    private BigDecimal roomCharge;
    private Integer extraGuestCount;
    private BigDecimal extraGuestCharge;
    private BigDecimal lineTotalBeforeServices;
    private List<PricingQuoteCycleResponse> cycles;
}
