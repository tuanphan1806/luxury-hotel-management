package com.hotel.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.constant.CheckoutReconciliationRequestStatus;
import com.hotel.backend.constant.CheckoutReconciliationStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.response.CheckoutReconciliationResponse;
import com.hotel.backend.entity.CheckoutReconciliationRequest;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.CheckoutReconciliationRequestRepository;
import com.hotel.backend.repository.MediaAssetRepository;
import com.hotel.backend.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckoutReconciliationRequestServiceTest {

    @Mock private CheckoutReconciliationRequestRepository requestRepository;
    @Mock private ReservationRepository reservationRepository;
    @Mock private MediaAssetRepository mediaAssetRepository;
    @Mock private ReservationService reservationService;
    @Mock private SePayService sePayService;
    @Mock private ReservationAuditService auditService;

    private CheckoutReconciliationRequestService service;
    private Reservation reservation;
    private CheckoutReconciliationRequest pending;
    private User requester;

    @BeforeEach
    void setUp() {
        service = new CheckoutReconciliationRequestService(
                requestRepository,
                reservationRepository,
                mediaAssetRepository,
                reservationService,
                sePayService,
                auditService,
                new ObjectMapper());

        reservation = new Reservation();
        reservation.setId(42L);
        reservation.setReservationCode("RES-TEST-42");
        requester = new User();
        requester.setId(7L);
        requester.setType(UserType.STAFF);
        requester.setUsername("staff-test");
        pending = CheckoutReconciliationRequest.builder()
                .id(11L)
                .reservation(reservation)
                .requestedBy(requester)
                .requestedByName("staff-test")
                .requestedByRole("STAFF")
                .status(CheckoutReconciliationRequestStatus.PENDING)
                .correlationId("correlation-11")
                .build();
    }

    @Test
    void legitimateFinancialOperationAutomaticallyClosesPendingRequestWhenMatched() {
        CheckoutReconciliationResponse matched = CheckoutReconciliationResponse.builder()
                .reservationId(42L)
                .reservationCode("RES-TEST-42")
                .requiredAmount(500_000L)
                .acceptedAmount(500_000L)
                .status(CheckoutReconciliationStatus.MATCHED)
                .blockingReasons(List.of())
                .build();
        when(requestRepository.findPendingByReservationIdForUpdate(42L))
                .thenReturn(List.of(pending));
        when(reservationService.getCheckoutReconciliation(42L, requester))
                .thenReturn(matched);

        int resolved = service.resolvePendingAutomatically(42L, "CASH_PAYMENT_SUCCEEDED");

        assertThat(resolved).isEqualTo(1);
        assertThat(pending.getStatus())
                .isEqualTo(CheckoutReconciliationRequestStatus.RESOLVED_AUTOMATICALLY);
        assertThat(pending.getResolvedBy()).isNull();
        assertThat(pending.getResolvedByName()).isEqualTo("SYSTEM");
        assertThat(pending.getResolvedByRole()).isEqualTo("SYSTEM");
        assertThat(pending.getResolutionReasonCode()).isEqualTo("MATCHED_BY_VALID_OPERATION");
        assertThat(pending.getResolvedAtUtc()).isNotNull();
        assertThat(pending.getCorrectionDetailJson().path("moneyMutatedByAutoResolution").asBoolean())
                .isFalse();
        assertThat(pending.getCorrectionDetailJson().path("checkoutTriggered").asBoolean())
                .isFalse();
        verify(requestRepository).save(pending);
    }

    @Test
    void mismatchRemainsPendingAndDoesNotMutateQueueRecord() {
        CheckoutReconciliationResponse mismatch = CheckoutReconciliationResponse.builder()
                .reservationId(42L)
                .reservationCode("RES-TEST-42")
                .requiredAmount(500_000L)
                .acceptedAmount(400_000L)
                .outstandingAmount(100_000L)
                .status(CheckoutReconciliationStatus.MISMATCH)
                .blockingReasons(List.of("Còn thiếu 100000 VND"))
                .build();
        when(requestRepository.findPendingByReservationIdForUpdate(42L))
                .thenReturn(List.of(pending));
        when(reservationService.getCheckoutReconciliation(42L, requester))
                .thenReturn(mismatch);

        int resolved = service.resolvePendingAutomatically(42L, "CASH_PAYMENT_SUCCEEDED");

        assertThat(resolved).isZero();
        assertThat(pending.getStatus()).isEqualTo(CheckoutReconciliationRequestStatus.PENDING);
        verify(requestRepository, never()).save(pending);
    }
}
