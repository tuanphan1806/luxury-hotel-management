package com.hotel.backend.dto.response;

import com.hotel.backend.constant.PricingAlgorithmVersion;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinalPaymentResponse {
    private Long reservationId;
    private PricingAlgorithmVersion pricingVersion;
    private Long totalAmount;
    private Long roomCharge;
    private Long plannedRoomCharge;
    private Long extraGuestCharge;
    /**
     * Informational room-price increase since commitment. For Pricing V2 this
     * amount is already included in roomCharge and must not be added again.
     */
    private Long postCommitmentRoomIncrease;
    private Long addOnServiceAmount;
    private Long paidAmount;
    private Long remainingAmount;
    private Long lateCheckoutFee;
    private Long refundableAmount;
    private Long earlyCheckoutAdjustment;
    private Long checkoutAdditionalFee;
    private boolean fullyPaid;
}
