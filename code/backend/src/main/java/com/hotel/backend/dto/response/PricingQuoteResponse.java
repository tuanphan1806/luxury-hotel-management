package com.hotel.backend.dto.response;

import com.hotel.backend.constant.PricingAlgorithmVersion;
import com.hotel.backend.constant.StayClassification;
import com.hotel.backend.constant.StayPackage;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PricingQuoteResponse {
    private UUID quoteId;
    private Instant quoteExpiresAtUtc;
    private Long stayPolicyVersionId;
    private Integer stayPolicyVersion;
    private PricingAlgorithmVersion pricingAlgorithmVersion;
    private String quoteHash;
    private LocalDateTime checkIn;
    private LocalDateTime checkOut;
    private Integer guestCount;
    private StayClassification displayClassification;
    private StayPackage displayPackageSummary;
    private LocalDateTime inventoryProtectedUntil;
    private BigDecimal roomCharge;
    private BigDecimal extraGuestCharge;
    private BigDecimal serviceCharge;
    private BigDecimal totalAmount;
    private List<PricingQuoteLineResponse> lines;
    private List<PricingQuoteServiceLineResponse> services;
}
