package com.hotel.backend.dto.response;

import com.hotel.backend.constant.CheckoutReconciliationStatus;
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
    private Long lateCheckoutFee;
    private Long earlyCheckoutAdjustment;
    private Long checkoutAdditionalFee;
    private boolean paymentPending;
    private boolean refundPending;
    private CheckoutReconciliationStatus status;
    private List<String> blockingReasons;
}
