package com.hotel.backend.dto.response;

import com.hotel.backend.constant.AddOnPricingUnit;
import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PricingQuoteServiceLineResponse {
    private Long serviceId;
    private String serviceCode;
    private String serviceName;
    private AddOnPricingUnit pricingUnit;
    private BigDecimal unitPrice;
    private Integer quantity;
    private Integer multiplier;
    private Integer billableQuantity;
    private BigDecimal totalPrice;
}
