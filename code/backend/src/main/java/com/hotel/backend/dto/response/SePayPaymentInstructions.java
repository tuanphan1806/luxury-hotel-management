package com.hotel.backend.dto.response;

import java.time.LocalDateTime;

public record SePayPaymentInstructions(
        String qrCodeUrl,
        String transferContent,
        String bankAccountNumber,
        String bankCode,
        String bankName,
        String accountHolder,
        Long expectedAmount,
        LocalDateTime expiresAt) {
}
