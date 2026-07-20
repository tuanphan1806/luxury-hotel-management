package com.hotel.backend.constant;

/**
 * Internal lifecycle marker used while a cancellation refund is waiting for
 * its channel-specific completion condition. The reservation keeps its current
 * business status until the refund ledger is completed.
 */
public final class ReservationCancellationReasonCode {

    public static final String REFUND_PENDING_PREFIX = "REFUND_PENDING:";

    /** Compatibility marker emitted by the first QR-only implementation. */
    public static final String LEGACY_MANUAL_BANK_REFUND_PENDING_PREFIX =
            "REFUND_PENDING_MANUAL_BANK:";

    public static final String CANCELLATION_APPROVED = "CANCELLATION_APPROVED";
    public static final String STAFF_CANCELLED = "STAFF_CANCELLED";
    public static final String STAFF_REJECTED = "STAFF_REJECTED";

    private ReservationCancellationReasonCode() {
    }

    public static String pending(String finalReasonCode) {
        return REFUND_PENDING_PREFIX + finalReasonCode;
    }

    public static boolean isRefundPending(String reasonCode) {
        return reasonCode != null
                && (reasonCode.startsWith(REFUND_PENDING_PREFIX)
                || reasonCode.startsWith(LEGACY_MANUAL_BANK_REFUND_PENDING_PREFIX));
    }

    /** Kept so existing callers/data remain readable during the local rollout. */
    @Deprecated
    public static boolean isManualBankRefundPending(String reasonCode) {
        return isRefundPending(reasonCode);
    }

    public static String finalReasonCode(String pendingReasonCode) {
        if (pendingReasonCode == null) return null;
        if (pendingReasonCode.startsWith(REFUND_PENDING_PREFIX)) {
            return pendingReasonCode.substring(REFUND_PENDING_PREFIX.length());
        }
        if (pendingReasonCode.startsWith(LEGACY_MANUAL_BANK_REFUND_PENDING_PREFIX)) {
            return pendingReasonCode.substring(LEGACY_MANUAL_BANK_REFUND_PENDING_PREFIX.length());
        }
        return pendingReasonCode;
    }
}
