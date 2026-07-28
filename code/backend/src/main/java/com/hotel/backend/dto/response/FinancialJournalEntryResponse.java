package com.hotel.backend.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.constant.FinancialPostingKind;
import com.hotel.backend.constant.FinancialSourceType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record FinancialJournalEntryResponse(
        Long id,
        String entryNumber,
        LocalDate businessDate,
        LocalDate originalBusinessDate,
        Instant occurredAtUtc,
        Instant postedAtUtc,
        FinancialSourceType sourceType,
        String sourceId,
        FinancialPostingKind postingKind,
        String currency,
        String description,
        boolean latePosting,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        Long reservationId,
        String reservationCode,
        JsonNode detail,
        List<FinancialJournalLineResponse> lines) {
}
