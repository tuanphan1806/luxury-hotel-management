package com.hotel.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.constant.CheckoutCorrectionType;
import com.hotel.backend.constant.CheckoutReconciliationRequestStatus;
import com.hotel.backend.constant.CheckoutReconciliationStatus;
import com.hotel.backend.dto.response.CheckoutReconciliationRequestResponse;
import com.hotel.backend.dto.response.CheckoutReconciliationResponse;
import com.hotel.backend.entity.CheckoutReconciliationRequest;
import com.hotel.backend.entity.Reservation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CheckoutReconciliationRequestMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CheckoutReconciliationRequestMapper mapper =
            new CheckoutReconciliationRequestMapper(objectMapper);

    @Test
    void mapsTheExistingResponseContractIncludingSnapshotAndResolutionFields() {
        Reservation reservation = Reservation.builder()
                .reservationCode("RES-42")
                .build();
        reservation.setId(42L);
        CheckoutReconciliationResponse snapshot = CheckoutReconciliationResponse.builder()
                .reservationId(42L)
                .requiredAmount(110_000L)
                .acceptedAmount(100_000L)
                .deltaAmount(10_000L)
                .status(CheckoutReconciliationStatus.MISMATCH)
                .blockingReasons(List.of("OUTSTANDING_DEBT"))
                .build();
        Instant resolvedAt = Instant.parse("2026-07-23T16:00:00Z");
        CheckoutReconciliationRequest entity = CheckoutReconciliationRequest.builder()
                .id(9L)
                .reservation(reservation)
                .status(CheckoutReconciliationRequestStatus.APPROVED)
                .mismatchSnapshotJson(objectMapper.valueToTree(snapshot))
                .reasonCode("PAYMENT_NOT_LINKED")
                .reasonNote("Kiểm tra giao dịch")
                .requestedByName("Staff")
                .requestedByRole("STAFF")
                .correctionType(CheckoutCorrectionType.LINK_EXISTING_PAYMENT)
                .resolutionReasonCode("LINK_CONFIRMED")
                .resolutionNote("Đã liên kết")
                .resolvedByName("Admin")
                .resolvedByRole("ADMIN")
                .resolvedAtUtc(resolvedAt)
                .correlationId("correlation-9")
                .build();

        CheckoutReconciliationRequestResponse response = mapper.toResponse(entity);

        assertEquals(9L, response.getId());
        assertEquals(42L, response.getReservationId());
        assertEquals("RES-42", response.getReservationCode());
        assertEquals(CheckoutReconciliationStatus.MISMATCH,
                response.getMismatchSnapshot().getStatus());
        assertEquals(CheckoutCorrectionType.LINK_EXISTING_PAYMENT,
                response.getCorrectionType());
        assertEquals("Admin", response.getResolvedByName());
        assertEquals(resolvedAt, response.getResolvedAtUtc());
    }
}
