package com.hotel.backend.constant;

/**
 * Kênh khách sạn thực sự dùng để trả tiền cho khách. Tách khỏi
 * PaymentProvider vì Staff/Admin được chọn tiền mặt hoặc chuyển khoản QR,
 * không phụ thuộc giao dịch thu ban đầu.
 */
public enum RefundChannel {
    VNPAY_ORIGINAL,
    MANUAL_BANK_TRANSFER,
    CASH_AT_COUNTER
}
