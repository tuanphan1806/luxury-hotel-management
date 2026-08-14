package com.hotel.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.constant.CheckoutCorrectionType;
import com.hotel.backend.constant.CheckoutReconciliationRequestStatus;
import com.hotel.backend.constant.CheckoutReconciliationStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.request.CheckoutReconciliationEscalationRequest;
import com.hotel.backend.dto.request.CheckoutReconciliationResolutionRequest;
import com.hotel.backend.dto.response.CheckoutReconciliationRequestResponse;
import com.hotel.backend.dto.response.CheckoutReconciliationResponse;
import com.hotel.backend.entity.CheckoutReconciliationRequest;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.CheckoutReconciliationRequestRepository;
import com.hotel.backend.repository.MediaAssetRepository;
import com.hotel.backend.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private ObjectMapper objectMapper;
    private Reservation reservation;
    private CheckoutReconciliationRequest pending;
    private User requester;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new CheckoutReconciliationRequestService(
                requestRepository,
                reservationRepository,
                mediaAssetRepository,
                reservationService,
                sePayService,
                auditService,
                objectMapper,
                new CheckoutReconciliationRequestMapper(objectMapper),
                new CheckoutReconciliationAccessPolicy());

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
                .mismatchSnapshotJson(objectMapper.valueToTree(mismatch(500_000L, 400_000L)))
                .reasonCode("PAYMENT_NOT_LINKED")
                .reasonNote("Chưa ghép được giao dịch")
                .idempotencyKey("idem-11")
                .actorScope("STAFF:7")
                .createdAtUtc(java.time.Instant.now())
                .correlationId("correlation-11")
                .build();
    }

    @Test
    void repeatedCreateReturnsStoredResultWithoutRunningFinancialFlowAgain() {
        when(requestRepository.findByIdempotencyKeyAndActorScope("idem-11", "STAFF:7"))
                .thenReturn(Optional.of(pending));

        CheckoutReconciliationRequestResponse response = service.create(
                42L, escalation(), "idem-11", "STAFF:7", requester);

        assertThat(response.getId()).isEqualTo(11L);
        assertThat(response.getStatus()).isEqualTo(CheckoutReconciliationRequestStatus.PENDING);
        verify(reservationRepository, never()).findByIdForUpdate(any());
        verify(requestRepository, never()).save(any());
    }

    @Test
    void createRejectsWhenCanonicalReconciliationIsAlreadyMatched() {
        when(requestRepository.findByIdempotencyKeyAndActorScope("new-idem", "STAFF:7"))
                .thenReturn(Optional.empty());
        when(reservationRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(reservation));
        when(reservationService.getCheckoutReconciliation(42L, requester))
                .thenReturn(matched(500_000L));

        assertThatThrownBy(() -> service.create(
                42L, escalation(), "new-idem", "STAFF:7", requester))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST))
                .hasMessageContaining("đang lệch");

        verify(requestRepository, never()).save(any());
    }

    @Test
    void createPersistsTrimmedImmutableMismatchSnapshot() {
        CheckoutReconciliationEscalationRequest request = escalation();
        request.setReasonCode("  PAYMENT_NOT_LINKED  ");
        request.setNote("  Chưa ghép được giao dịch  ");
        CheckoutReconciliationResponse mismatch = mismatch(500_000L, 400_000L);
        when(requestRepository.findByIdempotencyKeyAndActorScope("new-idem", "STAFF:7"))
                .thenReturn(Optional.empty());
        when(reservationRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(reservation));
        when(reservationService.getCheckoutReconciliation(42L, requester)).thenReturn(mismatch);
        when(requestRepository.existsByReservationIdAndStatus(
                42L, CheckoutReconciliationRequestStatus.PENDING)).thenReturn(false);
        when(requestRepository.save(any())).thenAnswer(invocation -> {
            CheckoutReconciliationRequest saved = invocation.getArgument(0);
            saved.setId(12L);
            return saved;
        });

        CheckoutReconciliationRequestResponse response = service.create(
                42L, request, "new-idem", "STAFF:7", requester);

        assertThat(response.getId()).isEqualTo(12L);
        assertThat(response.getReasonCode()).isEqualTo("PAYMENT_NOT_LINKED");
        assertThat(response.getReasonNote()).isEqualTo("Chưa ghép được giao dịch");
        assertThat(response.getMismatchSnapshot().getRequiredAmount()).isEqualTo(500_000L);
        assertThat(response.getMismatchSnapshot().getAcceptedAmount()).isEqualTo(400_000L);
    }

    @Test
    void resolvingAnAlreadyClosedRequestIsIdempotent() {
        pending.setStatus(CheckoutReconciliationRequestStatus.REJECTED);
        User admin = admin();
        when(requestRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(pending));

        CheckoutReconciliationRequestResponse response = service.resolve(
                11L, rejection(), admin);

        assertThat(response.getStatus()).isEqualTo(CheckoutReconciliationRequestStatus.REJECTED);
        verify(reservationRepository, never()).findByIdForUpdate(any());
        verify(requestRepository, never()).save(any());
    }

    @Test
    void adminRejectionClosesOnlyTheRequestAndDoesNotMutateMoney() {
        User admin = admin();
        when(requestRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(pending));
        when(reservationRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(reservation));
        when(reservationService.getCheckoutReconciliation(42L, admin))
                .thenReturn(mismatch(500_000L, 400_000L));
        when(requestRepository.save(pending)).thenReturn(pending);

        CheckoutReconciliationRequestResponse response = service.resolve(
                11L, rejection(), admin);

        assertThat(response.getStatus()).isEqualTo(CheckoutReconciliationRequestStatus.REJECTED);
        assertThat(pending.getResolvedBy()).isSameAs(admin);
        assertThat(pending.getResolutionReasonCode()).isEqualTo("INVALID_REQUEST");
        assertThat(pending.getResolvedAtUtc()).isNotNull();
        verify(sePayService, never()).manuallyReconcileReviewEvent(any(), any(), any());
    }

    @Test
    void adminCannotUseExceptionQueueToRewriteFees() {
        User admin = admin();
        CheckoutReconciliationResolutionRequest resolution = new CheckoutReconciliationResolutionRequest();
        resolution.setApprove(true);
        resolution.setCorrectionType(CheckoutCorrectionType.FEE_CORRECTION);
        resolution.setCorrectedAdditionalFee(0L);
        resolution.setReasonCode("FEE_CORRECTION");
        resolution.setNote("Không được phép sửa tiền tại hàng đợi");
        when(requestRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(pending));
        when(reservationRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(reservation));
        when(reservationService.getCheckoutReconciliation(42L, admin))
                .thenReturn(mismatch(500_000L, 400_000L));

        assertThatThrownBy(() -> service.resolve(11L, resolution, admin))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST))
                .hasMessageContaining("Không được sửa số tiền");

        verify(requestRepository, never()).save(any());
        verify(sePayService, never()).manuallyReconcileReviewEvent(any(), any(), any());
    }

    @Test
    void automaticResolutionIsNoOpWhenThereIsNoPendingRequest() {
        when(requestRepository.findPendingByReservationIdForUpdate(42L)).thenReturn(List.of());

        int resolved = service.resolvePendingAutomatically(42L, "PAYMENT_SUCCEEDED");

        assertThat(resolved).isZero();
        verify(reservationService, never()).getCheckoutReconciliation(any(), any());
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
        CheckoutReconciliationResponse mismatch = mismatch(500_000L, 400_000L);
        when(requestRepository.findPendingByReservationIdForUpdate(42L))
                .thenReturn(List.of(pending));
        when(reservationService.getCheckoutReconciliation(42L, requester))
                .thenReturn(mismatch);

        int resolved = service.resolvePendingAutomatically(42L, "CASH_PAYMENT_SUCCEEDED");

        assertThat(resolved).isZero();
        assertThat(pending.getStatus()).isEqualTo(CheckoutReconciliationRequestStatus.PENDING);
        verify(requestRepository, never()).save(pending);
    }

    private CheckoutReconciliationEscalationRequest escalation() {
        CheckoutReconciliationEscalationRequest request =
                new CheckoutReconciliationEscalationRequest();
        request.setReasonCode("PAYMENT_NOT_LINKED");
        request.setNote("Chưa ghép được giao dịch");
        return request;
    }

    private CheckoutReconciliationResolutionRequest rejection() {
        CheckoutReconciliationResolutionRequest request =
                new CheckoutReconciliationResolutionRequest();
        request.setApprove(false);
        request.setReasonCode("INVALID_REQUEST");
        request.setNote("Dữ liệu chưa đủ để xử lý");
        return request;
    }

    private User admin() {
        User admin = new User();
        admin.setId(1L);
        admin.setType(UserType.ADMIN);
        admin.setUsername("admin-test");
        admin.setFullName("Admin Test");
        return admin;
    }

    private CheckoutReconciliationResponse matched(long amount) {
        return CheckoutReconciliationResponse.builder()
                .reservationId(42L)
                .reservationCode("RES-TEST-42")
                .requiredAmount(amount)
                .acceptedAmount(amount)
                .outstandingAmount(0L)
                .deltaAmount(0L)
                .status(CheckoutReconciliationStatus.MATCHED)
                .blockingReasons(List.of())
                .build();
    }

    private CheckoutReconciliationResponse mismatch(long required, long accepted) {
        return CheckoutReconciliationResponse.builder()
                .reservationId(42L)
                .reservationCode("RES-TEST-42")
                .requiredAmount(required)
                .acceptedAmount(accepted)
                .outstandingAmount(required - accepted)
                .deltaAmount(accepted - required)
                .status(CheckoutReconciliationStatus.MISMATCH)
                .blockingReasons(List.of("Còn thiếu " + (required - accepted) + " VND"))
                .build();
    }
}
