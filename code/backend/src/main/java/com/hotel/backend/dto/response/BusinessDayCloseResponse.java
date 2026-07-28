package com.hotel.backend.dto.response;

import com.hotel.backend.constant.BusinessDayCloseStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record BusinessDayCloseResponse(
        Long id,
        LocalDate businessDate,
        BusinessDayCloseStatus status,
        boolean closed,
        boolean closeAllowed,
        List<String> blockers,
        Long closedById,
        String closedByName,
        String closedByRole,
        Instant closedAtUtc,
        long journalEntryCount,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        BigDecimal paymentReceivedAmount,
        BigDecimal refundCompletedAmount,
        BigDecimal recognizedRevenueAmount,
        BigDecimal pendingRefundPayableAmount,
        BigDecimal cashVarianceAmount,
        long openShiftCount,
        long unresolvedProviderEventCount,
        long unpostedPaymentCount,
        long unpostedRefundCount,
        long unpostedInvoiceCount,
        BigDecimal unreconciledFundsBalance,
        String note) {
}
