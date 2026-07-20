package com.hotel.backend.dto.response;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinalPaymentResponse {
    private Long reservationId;
    private Long totalAmount;
    private Long roomCharge;
    private Long plannedRoomCharge;
    private Long paidAmount;
    private Long remainingAmount;
    private Long lateCheckoutFee;
    private Long refundableAmount;
    private Long earlyCheckoutAdjustment;
    private Long checkoutAdditionalFee;
    private boolean fullyPaid;
}
