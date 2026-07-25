package com.hotel.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.dto.response.CheckoutReconciliationRequestResponse;
import com.hotel.backend.dto.response.CheckoutReconciliationResponse;
import com.hotel.backend.entity.CheckoutReconciliationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CheckoutReconciliationRequestMapper {

    private final ObjectMapper objectMapper;

    public CheckoutReconciliationRequestResponse toResponse(
            CheckoutReconciliationRequest entity) {
        CheckoutReconciliationResponse snapshot = objectMapper.convertValue(
                entity.getMismatchSnapshotJson(), CheckoutReconciliationResponse.class);
        return CheckoutReconciliationRequestResponse.builder()
                .id(entity.getId())
                .reservationId(entity.getReservation().getId())
                .reservationCode(entity.getReservation().getReservationCode())
                .status(entity.getStatus())
                .mismatchSnapshot(snapshot)
                .reasonCode(entity.getReasonCode())
                .reasonNote(entity.getReasonNote())
                .requestedByName(entity.getRequestedByName())
                .requestedByRole(entity.getRequestedByRole())
                .createdAtUtc(entity.getCreatedAtUtc())
                .correctionType(entity.getCorrectionType())
                .correctionDetail(entity.getCorrectionDetailJson())
                .resolutionReasonCode(entity.getResolutionReasonCode())
                .resolutionNote(entity.getResolutionNote())
                .resolvedByName(entity.getResolvedByName())
                .resolvedByRole(entity.getResolvedByRole())
                .resolvedAtUtc(entity.getResolvedAtUtc())
                .correlationId(entity.getCorrelationId())
                .build();
    }
}
