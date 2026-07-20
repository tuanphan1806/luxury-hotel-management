package com.hotel.backend.service;

/** Kết quả đã được kiểm tra checksum từ Refund hoặc QueryDR. */
public record VNPayProviderResult(
        String responseCode,
        String transactionStatus,
        String transactionType,
        String providerTransactionNo,
        String message) {
}
