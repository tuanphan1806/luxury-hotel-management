package com.hotel.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SePayApiTransaction(
        String id,
        @JsonProperty("transaction_date") String transactionDate,
        @JsonProperty("account_number") String accountNumber,
        @JsonProperty("transfer_type") String transferType,
        @JsonProperty("amount_in") BigDecimal amountIn,
        @JsonProperty("amount_out") BigDecimal amountOut,
        BigDecimal accumulated,
        @JsonProperty("transaction_content") String transactionContent,
        @JsonProperty("reference_number") String referenceNumber,
        String code,
        @JsonProperty("bank_brand_name") String bankBrandName) {
}
