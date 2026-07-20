package com.hotel.backend.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.constant.CheckoutCorrectionType;
import com.hotel.backend.constant.CheckoutReconciliationRequestStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class CheckoutReconciliationRequestResponse {
    private Long id;
    private Long reservationId;
    private String reservationCode;
    private CheckoutReconciliationRequestStatus status;
    private CheckoutReconciliationResponse mismatchSnapshot;
    private String reasonCode;
    private String reasonNote;
    private String requestedByName;
    private String requestedByRole;
    private Instant createdAtUtc;
    private CheckoutCorrectionType correctionType;
    private JsonNode correctionDetail;
    private String resolutionReasonCode;
    private String resolutionNote;
    private String resolvedByName;
    private String resolvedByRole;
    private Instant resolvedAtUtc;
    private String correlationId;
}
