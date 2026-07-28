package com.hotel.backend.dto.response;

import com.hotel.backend.constant.CashMovementDirection;
import com.hotel.backend.constant.CashMovementSourceType;
import com.hotel.backend.constant.CashMovementType;

import java.math.BigDecimal;
import java.time.Instant;

public record CashMovementResponse(
        Long id,
        Long cashierShiftId,
        CashMovementType movementType,
        CashMovementDirection direction,
        BigDecimal amount,
        CashMovementSourceType sourceType,
        String sourceId,
        Long reservationId,
        String reservationCode,
        String paymentTransactionId,
        String refundId,
        Long createdById,
        String createdByName,
        String createdByRole,
        String reason,
        Instant occurredAtUtc) {
}
