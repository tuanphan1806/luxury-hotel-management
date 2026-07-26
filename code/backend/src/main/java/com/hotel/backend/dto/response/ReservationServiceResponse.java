package com.hotel.backend.dto.response;

import com.hotel.backend.constant.AddOnPricingUnit;
import com.hotel.backend.constant.ReservationServiceOrigin;
import com.hotel.backend.constant.ReservationServiceStatus;
import com.hotel.backend.entity.ReservationServiceOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationServiceResponse {
    private Long id;
    private Long reservationId;
    private Long serviceId;
    private String serviceCode;
    private String serviceName;
    private String serviceNameEn;
    private String imageUrl;
    private BigDecimal unitPrice;
    private AddOnPricingUnit pricingUnit;
    private Integer quantity;
    private Integer pricingMultiplier;
    private Integer billableQuantity;
    private BigDecimal totalPrice;
    private ReservationServiceOrigin origin;
    private ReservationServiceStatus status;
    private String notes;
    private String cancellationReason;
    private Instant requestedAtUtc;
    private Instant confirmedAtUtc;
    private Instant fulfilledAtUtc;
    private Instant cancelledAtUtc;

    public static ReservationServiceResponse from(ReservationServiceOrder order) {
        return ReservationServiceResponse.builder()
                .id(order.getId())
                .reservationId(order.getReservation().getId())
                .serviceId(order.getService().getId())
                .serviceCode(order.getServiceCodeSnapshot())
                .serviceName(order.getServiceNameSnapshot())
                .serviceNameEn(order.getServiceNameEnSnapshot())
                .imageUrl(order.getServiceImageUrlSnapshot())
                .unitPrice(order.getUnitPriceSnapshot())
                .pricingUnit(order.getPricingUnitSnapshot())
                .quantity(order.getQuantity())
                .pricingMultiplier(order.getPricingMultiplier())
                .billableQuantity(order.getBillableQuantity())
                .totalPrice(order.getTotalPrice())
                .origin(order.getOrigin())
                .status(order.getStatus())
                .notes(order.getNotes())
                .cancellationReason(order.getCancellationReason())
                .requestedAtUtc(order.getRequestedAtUtc())
                .confirmedAtUtc(order.getConfirmedAtUtc())
                .fulfilledAtUtc(order.getFulfilledAtUtc())
                .cancelledAtUtc(order.getCancelledAtUtc())
                .build();
    }
}
