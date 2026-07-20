package com.hotel.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class BusinessMonitoringSummaryResponse {
    private Instant generatedAtUtc;
    private long durableSePayEventsToday;
    private long providerEventsRetrying;
    private long paymentEventsReviewRequired;
    private long unmatchedIncomingTransfers;
    private long unmatchedOutgoingRefundTransfers;
    private long overdueActiveRoomHolds;
    private long stalePendingRefunds;
    private long pendingCheckoutExceptionRequests;
    private long stalePendingCheckoutExceptionRequests;
    private long highRiskActionsLast24Hours;
    private long pendingEmailAlerts;
    private long failedEmailAlerts;
    private long staleEmailAlerts;
    private long webhookAuthenticationFailuresInWindow;
    private String sePayReconciliationStatus;
    private Instant sePayReconciliationLastRunAtUtc;
    private long refundPendingThresholdMinutes;
    private long reconciliationStaleThresholdMinutes;
    private long checkoutPendingThresholdMinutes;
    private long roomHoldOverdueGraceMinutes;
    private long emailOutboxStaleThresholdMinutes;
    private long webhookAuthenticationFailureThresholdCount;
    private long webhookAuthenticationFailureWindowMinutes;
}
