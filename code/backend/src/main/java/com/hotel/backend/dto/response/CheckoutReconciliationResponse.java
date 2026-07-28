package com.hotel.backend.dto.response;

import com.hotel.backend.constant.CheckoutReconciliationStatus;
import com.hotel.backend.constant.PricingAlgorithmVersion;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutReconciliationResponse {
    private Long reservationId;
    private String reservationCode;
    private Long requiredAmount;
    private Long acceptedAmount;
    private Long reservedRefundAmount;
    private Long uncoveredRefundAmount;
    private Long outstandingAmount;
    private Long deltaAmount;
    private PricingAlgorithmVersion pricingVersion;
    private Long plannedRoomCharge;
    private Long actualRoomCharge;
    private Long extraGuestCharge;
    private Long postCommitmentRoomIncrease;
    private Long lateCheckoutFee;
    private Long earlyCheckoutAdjustment;
    private Long checkoutAdditionalFee;
    private Long addOnServiceAmount;
    private boolean paymentPending;
    private boolean refundPending;
    private CheckoutReconciliationStatus status;
    private List<String> blockingReasons;
}
