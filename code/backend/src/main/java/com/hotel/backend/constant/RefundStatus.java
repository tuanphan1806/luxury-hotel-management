package com.hotel.backend.constant;

/** Trạng thái riêng của một yêu cầu hoàn, không ghi đè lịch sử giao dịch thu tiền. */
public enum RefundStatus {
    AWAITING_CUSTOMER_INFO,
    READY_FOR_MANUAL_TRANSFER,
    REQUESTED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    MANUAL_REVIEW,
    /**
     * The operator explicitly cancelled this ledger row.  It is deliberately
     * not a reserved status: the underlying obligation must be reactivated or
     * replaced before checkout can succeed.
     */
    CANCELLED;

    /** Stable target-contract name while database/API aliases remain compatible. */
    public String canonicalName() {
        return switch (this) {
            case REQUESTED -> "PENDING";
            case AWAITING_CUSTOMER_INFO -> "AWAITING_RECIPIENT";
            case READY_FOR_MANUAL_TRANSFER -> "READY_FOR_PAYMENT";
            case SUCCEEDED -> "COMPLETED";
            default -> name();
        };
    }
}
