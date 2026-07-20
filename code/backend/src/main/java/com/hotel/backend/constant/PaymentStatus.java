package com.hotel.backend.constant;

public enum PaymentStatus {
    PENDING,      // Chờ thanh toán
    SUCCESS,      // Thanh toán thành công
    FAILED,       // Thanh toán thất bại
    CANCELLED,    // Đã hủy
    REFUNDED,     // Đã hoàn tiền
    REFUND_PENDING // Đang xử lý hoàn tiền
}
