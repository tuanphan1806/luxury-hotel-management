package com.hotel.backend.constant;

public enum PaymentProviderEventStatus {
    RECEIVED,
    PROCESSING,
    PROCESSED,
    IGNORED,
    FAILED_RETRYABLE,
    REVIEW_REQUIRED
}
