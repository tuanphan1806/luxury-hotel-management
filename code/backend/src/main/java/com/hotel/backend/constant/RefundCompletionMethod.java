package com.hotel.backend.constant;

/**
 * Bằng chứng đã thực sự hoàn tiền. Mọi phương thức đều phải đi qua một
 * completion method trước khi reservation được chuyển sang trạng thái cuối.
 */
public enum RefundCompletionMethod {
    SEPAY_WEBHOOK,
    MANUAL_FALLBACK,
    CASH_HANDOVER,
    PROVIDER_API,
    LEGACY
}
