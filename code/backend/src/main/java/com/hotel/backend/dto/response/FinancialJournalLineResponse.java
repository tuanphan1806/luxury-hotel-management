package com.hotel.backend.dto.response;

import com.hotel.backend.constant.FinancialAccountCode;
import com.hotel.backend.constant.FinancialEntryDirection;

import java.math.BigDecimal;

public record FinancialJournalLineResponse(
        int lineNumber,
        FinancialAccountCode accountCode,
        FinancialEntryDirection direction,
        BigDecimal amount,
        String description) {
}
