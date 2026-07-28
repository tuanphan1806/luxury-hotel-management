package com.hotel.backend.dto.response;

import com.hotel.backend.constant.CashierShiftStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record CashierShiftResponse(
        Long id,
        String shiftCode,
        LocalDate businessDate,
        CashierShiftStatus status,
        Long openedById,
        String openedByName,
        String openedByRole,
        Instant openedAtUtc,
        Long closedById,
        String closedByName,
        String closedByRole,
        Instant closedAtUtc,
        BigDecimal openingCashAmount,
        BigDecimal expectedCashAmount,
        BigDecimal countedCashAmount,
        BigDecimal varianceAmount,
        String note,
        String closeNote,
        long movementCount,
        List<CashMovementResponse> movements) {
}
