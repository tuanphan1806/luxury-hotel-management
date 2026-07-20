package com.hotel.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.config.SePayConfig;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentProviderEventStatus;
import com.hotel.backend.constant.PaymentPurpose;
import com.hotel.backend.constant.RefundSourceType;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.request.SePayEventIgnoreRequest;
import com.hotel.backend.dto.request.SePayEventMatchRequest;
import com.hotel.backend.dto.request.SePayEventRefundRequest;
import com.hotel.backend.dto.request.ManualPaymentReconciliationRequest;
import com.hotel.backend.dto.response.PaymentRefundResponse;
import com.hotel.backend.dto.response.SePayPaymentInstructions;
import com.hotel.backend.dto.response.SePayApiTransaction;
import com.hotel.backend.entity.PaymentProviderEvent;
import com.hotel.backend.entity.PaymentRefund;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.PaymentProviderEventRepository;
import com.hotel.backend.repository.PaymentRefundRepository;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.persistence.EntityManager;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SePayServiceTest {

    private static final String WEBHOOK_SECRET = "test-sepay-webhook-secret";
    private static final String BANK_ACCOUNT = "0123456789";
    private static final String BANK_CODE = "NCB";
    private static final String PAYMENT_CODE = "LP77ABCDEF12";

    @Mock PaymentTransactionRepository transactionRepository;
    @Mock PaymentProviderEventRepository providerEventRepository;
    @Mock PaymentRefundRepository refundRepository;
    @Mock PaymentProviderEventService providerEventService;
    @Mock EntityManager entityManager;
    @Mock ReservationRepository reservationRepository;
    @Mock ReservationService reservationService;
    @Mock PaymentRefundService paymentRefundService;
    @Mock ReservationAuditService reservationAuditService;

    private SePayConfig config;
    private SePayService sePayService;

    @BeforeEach
    void setUp() {
        config = new SePayConfig();
        config.setQrBaseUrl("https://vietqr.app/img");
        config.setQrBankAccount(BANK_ACCOUNT);
        config.setMerchantBankAccount(BANK_ACCOUNT);
        config.setQrBankCode(BANK_CODE);
        config.setQrBankName("National Citizen Bank");
        config.setQrAccountHolder("LUXURY HOTEL");
        config.setQrTemplate("compact");
        config.setStoreName("Luxury Hotel");
        config.setWebhookSecret(WEBHOOK_SECRET);
        config.setWebhookTimestampToleranceSeconds(300L);

        lenient().when(providerEventService.ingest(any(PaymentProviderEvent.class)))
                .thenAnswer(invocation -> {
                    PaymentProviderEvent event = invocation.getArgument(0);
                    if (event.getId() == null) {
                        event.setId("event-" + event.getProviderEventId());
                    }
                    return event;
                });
        AtomicReference<PaymentProviderEvent> processingEvent = new AtomicReference<>();
        lenient().when(providerEventService.startProcessing(any(PaymentProviderEvent.class)))
                .thenAnswer(invocation -> {
                    PaymentProviderEvent event = invocation.getArgument(0);
                    event.setStatus(PaymentProviderEventStatus.PROCESSING);
                    event.setProcessingAttempts(
                            (event.getProcessingAttempts() != null
                                    ? event.getProcessingAttempts() : 0) + 1);
                    event.setLastAttemptAtUtc(Instant.now());
                    processingEvent.set(event);
                    return event;
                });
        lenient().when(providerEventRepository.findByIdForUpdate(any(String.class)))
                .thenAnswer(invocation -> Optional.ofNullable(processingEvent.get()));

        sePayService = new SePayService(
                config,
                new ObjectMapper(),
                entityManager,
                transactionRepository,
                providerEventRepository,
                refundRepository,
                providerEventService,
                reservationRepository,
                reservationService,
                paymentRefundService,
                reservationAuditService);
    }

    @Test
    void qrInstructionsContainMerchantAccountAmountAndTransferContent() {
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(15);
        PaymentTransaction transaction = PaymentTransaction.builder()
                .provider(PaymentProvider.SEPAY)
                .txnRef(PAYMENT_CODE)
                .amount(500_000L)
                .expectedAmount(500_000L)
                .expiresAt(expiresAt)
                .build();

        SePayPaymentInstructions instructions = sePayService.instructionsFor(transaction);

        assertNotNull(instructions);
        assertEquals(PAYMENT_CODE, instructions.transferContent());
        assertEquals(BANK_ACCOUNT, instructions.bankAccountNumber());
        assertEquals(BANK_CODE, instructions.bankCode());
        assertEquals(500_000L, instructions.expectedAmount());
        assertEquals(expiresAt, instructions.expiresAt());
        assertTrue(instructions.qrCodeUrl().contains("acc=" + BANK_ACCOUNT));
        assertTrue(instructions.qrCodeUrl().contains("bank=" + BANK_CODE));
        assertTrue(instructions.qrCodeUrl().contains("amount=500000"));
        assertTrue(instructions.qrCodeUrl().contains("des=" + PAYMENT_CODE));
    }

    @Test
    void virtualAccountQrDoesNotReplaceCanonicalMerchantIdentity() {
        String merchantIdentity = sePayService.configuredMerchantAccountId();
        config.setQrBankAccount("96247Z8VDD");

        PaymentTransaction transaction = PaymentTransaction.builder()
                .provider(PaymentProvider.SEPAY)
                .txnRef(PAYMENT_CODE)
                .amount(50_000L)
                .expectedAmount(50_000L)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();

        SePayPaymentInstructions instructions = sePayService.instructionsFor(transaction);

        assertEquals("96247Z8VDD", instructions.bankAccountNumber());
        assertTrue(instructions.qrCodeUrl().contains("acc=96247Z8VDD"));
        assertEquals(merchantIdentity, sePayService.configuredMerchantAccountId());
    }

    @Test
    void reviewIgnorePersistsReasonReviewerAndTerminalStatus() {
        PaymentProviderEvent event = PaymentProviderEvent.builder()
                .id("review-ignore")
                .provider(PaymentProvider.SEPAY)
                .status(PaymentProviderEventStatus.REVIEW_REQUIRED)
                .amount(100_000L)
                .build();
        when(providerEventRepository.findByIdForUpdate("review-ignore"))
                .thenReturn(Optional.of(event));
        SePayEventIgnoreRequest request = new SePayEventIgnoreRequest();
        request.setReason("Đã xác minh đây là giao dịch test");

        var response = sePayService.ignoreReviewEvent(
                "review-ignore", request, reviewer());

        assertEquals(PaymentProviderEventStatus.IGNORED, response.status());
        assertEquals("Đã xác minh đây là giao dịch test", response.reviewNote());
        assertNotNull(response.reviewedAtUtc());
        verify(providerEventRepository).save(event);
        verify(reservationAuditService).recordTarget(
                eq("PAYMENT_PROVIDER_EVENT"), eq("review-ignore"), any(), any());
    }

    @Test
    void reviewRefundCreatesProviderEventOnlyRefundWithoutReservation() {
        PaymentProviderEvent event = PaymentProviderEvent.builder()
                .id("review-refund")
                .provider(PaymentProvider.SEPAY)
                .status(PaymentProviderEventStatus.REVIEW_REQUIRED)
                .transferType("in")
                .amount(120_000L)
                .build();
        when(providerEventRepository.findByIdForUpdate("review-refund"))
                .thenReturn(Optional.of(event));
        when(paymentRefundService.requestUnmatchedEventRefund(
                eq(event), eq(RefundChannel.MANUAL_BANK_TRANSFER), any(), any()))
                .thenReturn(PaymentRefundResponse.builder()
                        .refundId("refund-unmatched")
                        .amount(120_000L)
                        .build());
        SePayEventRefundRequest request = new SePayEventRefundRequest();
        request.setRefundChannel(RefundChannel.MANUAL_BANK_TRANSFER);
        request.setReason("Không xác định được booking owner");

        PaymentRefundResponse response = sePayService.refundReviewEvent(
                "review-refund", request, reviewer());

        assertEquals("refund-unmatched", response.getRefundId());
        assertEquals(PaymentProviderEventStatus.PROCESSED, event.getStatus());
        assertNotNull(event.getProcessedAtUtc());
        verify(reservationAuditService).recordTarget(
                eq("PAYMENT_PROVIDER_EVENT"), eq("review-refund"), any(), any());
    }

    @Test
    void reviewMatchUsesSharedAllocationStateMachine() {
        Reservation reservation = Reservation.builder()
                .reservationCode("RSV-501")
                .status(ReservationStatus.CHECKED_IN)
                .totalAmount(BigDecimal.valueOf(300_000L))
                .build();
        reservation.setId(501L);
        PaymentTransaction transaction = PaymentTransaction.builder()
                .id("payment-review-match")
                .reservation(reservation)
                .txnRef(PAYMENT_CODE)
                .provider(PaymentProvider.SEPAY)
                .purpose(PaymentPurpose.FINAL_PAYMENT)
                .status(PaymentStatus.PENDING)
                .amount(100_000L)
                .expectedAmount(100_000L)
                .build();
        PaymentProviderEvent event = PaymentProviderEvent.builder()
                .id("review-match")
                .provider(PaymentProvider.SEPAY)
                .providerEventId("bank-event-review")
                .providerTxnId("bank-txn-review")
                .providerReference("bank-reference-review")
                .status(PaymentProviderEventStatus.REVIEW_REQUIRED)
                .transferType("in")
                .amount(100_000L)
                .providerOccurredAtUtc(Instant.now())
                .providerOccurredAt(Instant.now().toString())
                .processingAttempts(1)
                .build();
        when(providerEventRepository.findByIdForUpdate("review-match"))
                .thenReturn(Optional.of(event));
        when(transactionRepository.findById("payment-review-match"))
                .thenReturn(Optional.of(transaction));
        when(transactionRepository.findByIdForUpdate("payment-review-match"))
                .thenReturn(Optional.of(transaction));
        when(reservationRepository.findByIdForUpdate(501L))
                .thenReturn(Optional.of(reservation));
        when(paymentRefundService.getNetPaidAmount(501L)).thenReturn(0L);
        SePayEventMatchRequest request = new SePayEventMatchRequest();
        request.setPaymentTransactionId("payment-review-match");
        request.setNote("Đối chiếu sao kê thủ công");

        var response = sePayService.matchReviewEvent(
                "review-match", request, reviewer());

        assertEquals(PaymentProviderEventStatus.PROCESSED, response.status());
        assertEquals(PaymentStatus.SUCCESS, transaction.getStatus());
        assertEquals(100_000L, transaction.getAcceptedAmount());
        assertEquals(0L, transaction.getRefundRequiredAmount());
        verify(transactionRepository).save(transaction);
        verify(reservationAuditService).recordTarget(
                eq("PAYMENT_PROVIDER_EVENT"), eq("review-match"), any(), any());
    }

    @Test
    void manualReconciliationRejectsEventWithoutProviderTime() {
        PaymentProviderEvent event = PaymentProviderEvent.builder()
                .id("manual-no-provider-time")
                .provider(PaymentProvider.SEPAY)
                .providerEventId("bank-manual-no-time")
                .merchantAccountId(sePayService.configuredMerchantAccountId())
                .status(PaymentProviderEventStatus.REVIEW_REQUIRED)
                .transferType("in")
                .amount(100_000L)
                .build();
        when(providerEventRepository.findByIdForUpdate("manual-no-provider-time"))
                .thenReturn(Optional.of(event));
        ManualPaymentReconciliationRequest request = manualRequest("payment-no-time");

        assertThrows(RuntimeException.class, () -> sePayService.manuallyReconcileReviewEvent(
                "manual-no-provider-time", request, adminReviewer()));

        verify(transactionRepository, never()).findById(any(String.class));
        verify(transactionRepository, never()).save(any(PaymentTransaction.class));
    }

    @Test
    void manualReconciliationUsesServerEventAndCanonicalPaymentStateMachine() {
        Reservation reservation = Reservation.builder()
                .reservationCode("RSV-MANUAL-501")
                .status(ReservationStatus.CHECKED_IN)
                .totalAmount(BigDecimal.valueOf(300_000L))
                .build();
        reservation.setId(501L);
        PaymentTransaction transaction = PaymentTransaction.builder()
                .id("payment-manual-reconcile")
                .reservation(reservation)
                .txnRef(PAYMENT_CODE)
                .provider(PaymentProvider.SEPAY)
                .purpose(PaymentPurpose.FINAL_PAYMENT)
                .status(PaymentStatus.PENDING)
                .amount(100_000L)
                .expectedAmount(100_000L)
                .build();
        Instant providerTime = Instant.parse("2026-07-15T03:30:00Z");
        PaymentProviderEvent event = PaymentProviderEvent.builder()
                .id("manual-review-event")
                .provider(PaymentProvider.SEPAY)
                .providerEventId("bank-manual-event")
                .providerTxnId("bank-manual-txn")
                .providerReference("bank-manual-reference")
                .merchantAccountId(sePayService.configuredMerchantAccountId())
                .status(PaymentProviderEventStatus.REVIEW_REQUIRED)
                .transferType("in")
                .amount(100_000L)
                .providerOccurredAt(providerTime.toString())
                .providerOccurredAtUtc(providerTime)
                .processingAttempts(1)
                .build();
        when(providerEventRepository.findByIdForUpdate("manual-review-event"))
                .thenReturn(Optional.of(event));
        when(transactionRepository.findById("payment-manual-reconcile"))
                .thenReturn(Optional.of(transaction));
        when(transactionRepository.findByIdForUpdate("payment-manual-reconcile"))
                .thenReturn(Optional.of(transaction));
        when(reservationRepository.findByIdForUpdate(501L))
                .thenReturn(Optional.of(reservation));
        when(paymentRefundService.getNetPaidAmount(501L)).thenReturn(0L);

        var response = sePayService.manuallyReconcileReviewEvent(
                "manual-review-event", manualRequest("payment-manual-reconcile"), adminReviewer());

        assertEquals(PaymentProviderEventStatus.PROCESSED, response.status());
        assertEquals(PaymentStatus.SUCCESS, transaction.getStatus());
        assertEquals(100_000L, transaction.getReceivedAmount());
        assertEquals(100_000L, transaction.getAcceptedAmount());
        assertEquals(providerTime, event.getProviderOccurredAtUtc());
        verify(transactionRepository).save(transaction);
        verify(reservationAuditService).record(
                eq(reservation), eq("PAYMENT_PROVIDER_EVENT"), eq("manual-review-event"),
                eq(com.hotel.backend.constant.ReservationAuditAction.PAYMENT_MARKED_PAID_MANUALLY),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void webhookSignatureRequiresExactRawBodyAndFreshTimestamp() throws Exception {
        byte[] rawBody = "{\"id\":9001,\"transferAmount\":50000}".getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = sign(rawBody, timestamp);

        assertTrue(sePayService.verifyWebhookSignature(rawBody, signature, timestamp));
        assertFalse(sePayService.verifyWebhookSignature(
                "{\"id\":9001,\"transferAmount\":50001}".getBytes(StandardCharsets.UTF_8),
                signature,
                timestamp));

        String staleTimestamp = String.valueOf(Instant.now().minusSeconds(301).getEpochSecond());
        assertFalse(sePayService.verifyWebhookSignature(
                rawBody,
                sign(rawBody, staleTimestamp),
                staleTimestamp));
        assertFalse(sePayService.verifyWebhookSignature(rawBody, signature, "not-a-timestamp"));
    }

    @Test
    void webhookApiKeyIsConstantTimePrimaryAuthenticationAndDoesNotDowngrade() throws Exception {
        byte[] rawBody = "{\"id\":9001}".getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        config.setWebhookApiKey("sepay-api-key-123");

        assertTrue(sePayService.verifyWebhookAuthentication(
                rawBody, "Apikey sepay-api-key-123", null, null));
        assertTrue(sePayService.verifyWebhookAuthentication(
                rawBody, "apikey sepay-api-key-123", null, null));
        assertFalse(sePayService.verifyWebhookAuthentication(
                rawBody, "Apikey wrong", sign(rawBody, timestamp), timestamp));
        assertFalse(sePayService.verifyWebhookAuthentication(
                rawBody, null, sign(rawBody, timestamp), timestamp));
    }

    @Test
    void outgoingWebhookCompletesExactlyMatchedPendingRefundOnlyOnce() {
        String refundCode = "RF0123456789ABCDEF";
        PaymentRefund refund = PaymentRefund.builder()
                .id("refund-out-1")
                .refundCode(refundCode)
                .channel(RefundChannel.MANUAL_BANK_TRANSFER)
                .status(RefundStatus.REQUESTED)
                .amount(125_000L)
                .requestedAmount(125_000L)
                .build();
        AtomicReference<PaymentProviderEvent> recordedEvent = new AtomicReference<>();
        when(providerEventRepository.findByProviderAndProviderEventId(
                PaymentProvider.SEPAY, "99001"))
                .thenAnswer(invocation -> Optional.ofNullable(recordedEvent.get()));
        when(providerEventRepository.save(any(PaymentProviderEvent.class)))
                .thenAnswer(invocation -> {
                    PaymentProviderEvent event = invocation.getArgument(0);
                    recordedEvent.set(event);
                    return event;
                });
        when(refundRepository.findPendingByRefundCodes(
                any(), eq(RefundChannel.MANUAL_BANK_TRANSFER), any()))
                .thenReturn(java.util.List.of(refund));
        when(paymentRefundService.completeFromSePayOutgoing(
                eq("refund-out-1"), any(PaymentProviderEvent.class), eq(125_000L)))
                .thenReturn(PaymentRefundResponse.builder()
                        .refundId("refund-out-1")
                        .status(RefundStatus.SUCCEEDED)
                        .build());

        Map<String, Object> first = sePayService.handleWebhook(
                outgoingWebhookPayload(99001L, refundCode, 125_000L));
        Map<String, Object> replay = sePayService.handleWebhook(
                outgoingWebhookPayload(99001L, refundCode, 125_000L));

        assertEquals("outgoing_refund_completed", first.get("message"));
        assertEquals("already_processed", replay.get("message"));
        assertEquals(PaymentProviderEventStatus.PROCESSED, recordedEvent.get().getStatus());
        assertEquals(refundCode, recordedEvent.get().getPaymentCode());
        verify(paymentRefundService, times(1)).completeFromSePayOutgoing(
                eq("refund-out-1"), any(PaymentProviderEvent.class), eq(125_000L));
    }

    @Test
    void outgoingWebhookWithRightCodeButWrongAmountGoesToReview() {
        String refundCode = "RFABCDEF0123456789";
        PaymentRefund refund = PaymentRefund.builder()
                .id("refund-out-amount")
                .refundCode(refundCode)
                .channel(RefundChannel.MANUAL_BANK_TRANSFER)
                .status(RefundStatus.REQUESTED)
                .amount(200_000L)
                .requestedAmount(200_000L)
                .build();
        AtomicReference<PaymentProviderEvent> recordedEvent = new AtomicReference<>();
        when(providerEventRepository.save(any(PaymentProviderEvent.class)))
                .thenAnswer(invocation -> {
                    PaymentProviderEvent event = invocation.getArgument(0);
                    recordedEvent.set(event);
                    return event;
                });
        when(refundRepository.findPendingByRefundCodes(
                any(), eq(RefundChannel.MANUAL_BANK_TRANSFER), any()))
                .thenReturn(java.util.List.of(refund));

        Map<String, Object> result = sePayService.handleWebhook(
                outgoingWebhookPayload(99002L, refundCode, 199_000L));

        assertEquals("outgoing_refund_amount_mismatch_review_required", result.get("message"));
        assertEquals(PaymentProviderEventStatus.REVIEW_REQUIRED, recordedEvent.get().getStatus());
        verify(paymentRefundService, never()).completeFromSePayOutgoing(
                any(String.class), any(PaymentProviderEvent.class), anyLong());
    }

    @Test
    void providerTestWebhookIsAcknowledgedWithoutTouchingFinancialLedger() {
        byte[] payload = ("{"
                + "\"id\":0,"
                + "\"gateway\":\"SePay\","
                + "\"transactionDate\":\"2026-07-16 21:02:52\","
                + "\"accountNumber\":\"0000000000\","
                + "\"code\":\"SEPAYTEST\","
                + "\"content\":\"SEPAY TEST WEBHOOK\","
                + "\"transferType\":\"in\","
                + "\"description\":\"SePay test webhook delivery\","
                + "\"transferAmount\":10000,"
                + "\"accumulated\":10000,"
                + "\"referenceCode\":\"TEST1784210572\""
                + "}").getBytes(StandardCharsets.UTF_8);

        Map<String, Object> result = sePayService.handleWebhook(payload);

        assertEquals(true, result.get("success"));
        assertEquals("provider_test_acknowledged", result.get("message"));
        verify(providerEventService, never()).ingest(any(PaymentProviderEvent.class));
        verify(transactionRepository, never()).save(any(PaymentTransaction.class));
    }

    @Test
    void zeroIdWithDifferentMerchantButWithoutProviderTestMarkersIsRejected() {
        assertThrows(RuntimeException.class, () -> sePayService.handleWebhook(
                webhookPayload(0L, "9999999999", "TEST-NOT-SEPAY-PROBE", 10_000L)));
        verify(providerEventService, never()).ingest(any(PaymentProviderEvent.class));
    }

    @Test
    void exactPendingPaymentBecomesSuccessAndDuplicateEventHasNoSecondSideEffect() {
        PaymentTransaction transaction = payment(PaymentStatus.PENDING, ReservationStatus.PAYMENT_PENDING, 50_000L);
        AtomicReference<PaymentProviderEvent> recordedEvent = new AtomicReference<>();
        stubPaymentLookup(transaction);
        when(providerEventRepository.findByProviderAndProviderEventId(PaymentProvider.SEPAY, "9001"))
                .thenAnswer(invocation -> Optional.ofNullable(recordedEvent.get()));
        when(providerEventRepository.save(any(PaymentProviderEvent.class)))
                .thenAnswer(invocation -> {
                    PaymentProviderEvent event = invocation.getArgument(0);
                    recordedEvent.set(event);
                    return event;
                });
        when(paymentRefundService.getNetPaidAmount(transaction.getReservation().getId())).thenReturn(0L);

        byte[] payload = webhookPayload(9001L, 50_000L);
        Map<String, Object> first = sePayService.handleWebhook(payload);
        Map<String, Object> duplicate = sePayService.handleWebhook(payload);

        assertEquals("payment_success", first.get("message"));
        assertEquals("already_processed", duplicate.get("message"));
        assertEquals(PaymentStatus.SUCCESS, transaction.getStatus());
        assertEquals(50_000L, transaction.getReceivedAmount());
        assertEquals(50_000L, transaction.getAcceptedAmount());
        assertEquals(0L, transaction.getRefundRequiredAmount());
        assertEquals("9001", transaction.getProviderTxnId());
        assertNotNull(transaction.getPaidAt());
        assertNotNull(recordedEvent.get());
        assertEquals(PaymentProviderEventStatus.PROCESSED, recordedEvent.get().getStatus());
        assertEquals(transaction, recordedEvent.get().getPaymentTransaction());

        verify(transactionRepository, times(1)).save(transaction);
        verify(reservationService, times(1))
                .convertHoldsAfterPayment(transaction.getReservation().getId());
        verify(paymentRefundService, never())
                .requestLateCapturedPaymentRefund(any(PaymentTransaction.class), any(String.class));
        verify(paymentRefundService, never())
                .requestReservationRefund(anyLong(), anyLong(), any(String.class), any(String.class));
        verify(entityManager).clear();
    }

    @Test
    void providerTimestampAtSecondPrecisionAcceptsPaymentCreatedInTheSameSecond() {
        PaymentTransaction transaction = payment(
                PaymentStatus.PENDING, ReservationStatus.PAYMENT_PENDING, 50_000L);
        transaction.setCreatedAt(LocalDateTime.of(2026, 7, 15, 10, 30, 0, 26_000_000));
        transaction.setExpiresAt(LocalDateTime.of(2026, 7, 15, 10, 35, 0, 26_000_000));
        stubSingleEvent(transaction, "9012");
        when(paymentRefundService.getNetPaidAmount(transaction.getReservation().getId()))
                .thenReturn(0L);

        Map<String, Object> result = sePayService.handleWebhook(
                webhookPayload(9012L, 50_000L));

        assertEquals("payment_success", result.get("message"));
        assertEquals(PaymentStatus.SUCCESS, transaction.getStatus());
        assertEquals(50_000L, transaction.getAcceptedAmount());
        assertEquals(0L, transaction.getRefundRequiredAmount());
        verify(reservationService).convertHoldsAfterPayment(
                transaction.getReservation().getId());
        verify(paymentRefundService, never()).requestCapturedPaymentRefund(
                any(PaymentTransaction.class), anyLong(), any(RefundSourceType.class),
                any(String.class), any(PaymentProviderEvent.class),
                any(String.class), any(String.class));
    }

    @Test
    void providerTimestampFromThePreviousSecondIsStillRejected() {
        PaymentTransaction transaction = payment(
                PaymentStatus.PENDING, ReservationStatus.PAYMENT_PENDING, 50_000L);
        transaction.setCreatedAt(LocalDateTime.of(2026, 7, 15, 10, 30, 0, 26_000_000));
        transaction.setExpiresAt(LocalDateTime.of(2026, 7, 15, 10, 35, 0, 26_000_000));
        stubSingleEvent(transaction, "9013");

        Map<String, Object> result = sePayService.handleWebhook(
                webhookPayload(9013L, 50_000L, "2026-07-15 10:29:59"));

        assertEquals("late_payment_refund_pending", result.get("message"));
        assertEquals(0L, transaction.getAcceptedAmount());
        assertEquals(50_000L, transaction.getRefundRequiredAmount());
        verify(reservationService, never()).convertHoldsAfterPayment(anyLong());
    }

    /**
     * Giao dịch thử trên dashboard SePay không có mã payment vẫn phải vào hàng
     * đợi đối soát, nhưng scheduler không được ghi lại event và báo lỗi sau mỗi
     * chu kỳ polling.
     */
    @Test
    void unmatchedSePayTestTransferIsQueuedOnlyOnce() {
        AtomicReference<PaymentProviderEvent> recordedEvent = new AtomicReference<>();
        when(providerEventRepository.findByProviderAndProviderReference(
                eq(PaymentProvider.SEPAY), any(String.class)))
                .thenAnswer(invocation -> Optional.ofNullable(recordedEvent.get()));
        when(providerEventRepository.findByProviderAndProviderEventId(
                PaymentProvider.SEPAY, "sepay-test-event"))
                .thenAnswer(invocation -> Optional.ofNullable(recordedEvent.get()));
        when(providerEventRepository.save(any(PaymentProviderEvent.class)))
                .thenAnswer(invocation -> {
                    PaymentProviderEvent event = invocation.getArgument(0);
                    recordedEvent.set(event);
                    return event;
                });

        SePayApiTransaction testTransfer = new SePayApiTransaction(
                "sepay-test-event",
                "2026-07-15T19:01:36+07:00",
                BANK_ACCOUNT,
                "in",
                BigDecimal.valueOf(5_000L),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "SEPAY-DASHBOARD-TEST",
                null,
                "NCB");

        sePayService.reconcile(testTransfer);
        sePayService.reconcile(testTransfer);

        assertNotNull(recordedEvent.get());
        assertEquals(PaymentProviderEventStatus.REVIEW_REQUIRED, recordedEvent.get().getStatus());
        verify(providerEventRepository, times(1)).save(any(PaymentProviderEvent.class));
        verify(transactionRepository, never()).save(any(PaymentTransaction.class));
    }

    /**
     * Event đã vào hàng REVIEW_REQUIRED là công việc thủ công. Polling tiếp theo
     * phải dừng trước cả bước tìm PaymentTransaction để không lặp query/log.
     */
    @Test
    void reviewEventWithPaymentCodeIsNotMatchedAgainByPolling() {
        AtomicReference<PaymentProviderEvent> recordedEvent = new AtomicReference<>();
        when(providerEventRepository.findByProviderAndProviderReference(
                eq(PaymentProvider.SEPAY), any(String.class)))
                .thenAnswer(invocation -> Optional.ofNullable(recordedEvent.get()));
        when(providerEventRepository.findByProviderAndProviderEventId(
                PaymentProvider.SEPAY, "review-with-code"))
                .thenAnswer(invocation -> Optional.ofNullable(recordedEvent.get()));
        when(providerEventRepository.save(any(PaymentProviderEvent.class)))
                .thenAnswer(invocation -> {
                    PaymentProviderEvent event = invocation.getArgument(0);
                    recordedEvent.set(event);
                    return event;
                });
        when(transactionRepository.findByTxnRef(PAYMENT_CODE)).thenReturn(Optional.empty());

        SePayApiTransaction transfer = new SePayApiTransaction(
                "review-with-code",
                "2026-07-15T19:05:00+07:00",
                BANK_ACCOUNT,
                "in",
                BigDecimal.valueOf(5_000L),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "TEST " + PAYMENT_CODE,
                null,
                null,
                "NCB");

        sePayService.reconcile(transfer);
        sePayService.reconcile(transfer);

        assertEquals(PAYMENT_CODE, recordedEvent.get().getPaymentCode());
        assertEquals(PaymentProviderEventStatus.REVIEW_REQUIRED, recordedEvent.get().getStatus());
        verify(transactionRepository, times(1)).findByTxnRef(PAYMENT_CODE);
        verify(providerEventRepository, times(1)).save(any(PaymentProviderEvent.class));
    }

    @Test
    void underpaymentIsCapturedButUnacceptedThenCancelsAndRefundsAtomically() {
        PaymentTransaction transaction = payment(PaymentStatus.PENDING, ReservationStatus.PAYMENT_PENDING, 50_000L);
        stubSingleEvent(transaction, "9002");

        Map<String, Object> result = sePayService.handleWebhook(webhookPayload(9002L, 40_000L));

        assertEquals("underpayment_refund_pending", result.get("message"));
        assertEquals(PaymentStatus.SUCCESS, transaction.getStatus());
        assertEquals(PaymentProvider.SEPAY, transaction.getProvider());
        assertEquals(50_000L, transaction.getExpectedAmount());
        assertEquals(40_000L, transaction.getReceivedAmount());
        assertEquals(50_000L, transaction.getAmount());
        assertEquals(0L, transaction.getAcceptedAmount());
        assertEquals(40_000L, transaction.getRefundRequiredAmount());
        verify(reservationService).cancelForPaymentFailure(
                eq(77L), eq("UNDERPAYMENT"), any(String.class));
        verify(paymentRefundService).requestCapturedPaymentRefund(
                eq(transaction), eq(40_000L), eq(RefundSourceType.UNACCEPTED_PAYMENT),
                any(String.class), any(PaymentProviderEvent.class),
                any(String.class), eq("sepay_webhook"));
        verify(reservationService, never()).convertHoldsAfterPayment(any(Long.class));
    }

    @Test
    void latePaymentIsCapturedButUnacceptedAndRequestsAFullRefund() {
        PaymentTransaction transaction = payment(PaymentStatus.FAILED, ReservationStatus.CANCELLED, 50_000L);
        stubSingleEvent(transaction, "9003");

        Map<String, Object> result = sePayService.handleWebhook(webhookPayload(9003L, 50_000L));

        assertEquals("late_payment_refund_pending", result.get("message"));
        assertEquals(PaymentStatus.SUCCESS, transaction.getStatus());
        assertEquals(PaymentProvider.SEPAY, transaction.getProvider());
        assertEquals(50_000L, transaction.getReceivedAmount());
        assertEquals(0L, transaction.getAcceptedAmount());
        assertEquals(50_000L, transaction.getRefundRequiredAmount());
        verify(paymentRefundService).requestCapturedPaymentRefund(
                eq(transaction), eq(50_000L), eq(RefundSourceType.UNACCEPTED_PAYMENT),
                any(String.class), any(PaymentProviderEvent.class),
                any(String.class), eq("sepay_webhook"));
        verify(reservationService, never()).convertHoldsAfterPayment(any(Long.class));
    }

    /** Tiền vào của QR đã bị hủy vẫn phải được tìm bằng paymentCode và hoàn lại. */
    @Test
    void cancelledPaymentIsHandledAsLatePayment() {
        PaymentTransaction transaction = payment(
                PaymentStatus.CANCELLED, ReservationStatus.CANCELLED, 50_000L);
        stubSingleEvent(transaction, "90031");

        Map<String, Object> result = sePayService.handleWebhook(webhookPayload(90031L, 50_000L));

        assertEquals("late_payment_refund_pending", result.get("message"));
        assertEquals(PaymentStatus.SUCCESS, transaction.getStatus());
        assertEquals(0L, transaction.getAcceptedAmount());
        assertEquals(50_000L, transaction.getRefundRequiredAmount());
        verify(transactionRepository).findByTxnRef(PAYMENT_CODE);
        verify(paymentRefundService).requestCapturedPaymentRefund(
                eq(transaction), eq(50_000L), eq(RefundSourceType.UNACCEPTED_PAYMENT),
                any(String.class), any(PaymentProviderEvent.class),
                any(String.class), eq("sepay_webhook"));
    }

    @Test
    void overpaymentKeepsReceiptAndQueuesOnlyTheExcessForRefund() {
        PaymentTransaction transaction = payment(
                PaymentStatus.PENDING, ReservationStatus.PAYMENT_PENDING, 50_000L);
        stubSingleEvent(transaction, "9004");
        when(paymentRefundService.getNetPaidAmount(transaction.getReservation().getId()))
                .thenReturn(0L);

        Map<String, Object> result = sePayService.handleWebhook(webhookPayload(9004L, 60_000L));

        assertEquals("payment_success_excess_refund_pending", result.get("message"));
        assertEquals(60_000L, transaction.getReceivedAmount());
        assertEquals(50_000L, transaction.getExpectedAmount());
        assertEquals(50_000L, transaction.getAcceptedAmount());
        assertEquals(10_000L, transaction.getRefundRequiredAmount());
        verify(paymentRefundService).requestCapturedPaymentRefund(
                eq(transaction), eq(10_000L), eq(RefundSourceType.CHECKOUT_OVERPAYMENT),
                any(String.class), any(PaymentProviderEvent.class),
                any(String.class), eq("sepay_webhook"));
        verify(reservationService).convertHoldsAfterPayment(transaction.getReservation().getId());
    }

    @Test
    void secondRealBankTransferIsRecordedSeparatelyAndFullyRefunded() {
        PaymentTransaction original = payment(
                PaymentStatus.SUCCESS, ReservationStatus.DRAFT, 50_000L);
        stubSingleEvent(original, "9005");
        AtomicReference<PaymentTransaction> captured = new AtomicReference<>();
        when(transactionRepository.save(any(PaymentTransaction.class)))
                .thenAnswer(invocation -> {
                    PaymentTransaction payment = invocation.getArgument(0);
                    if (payment != original) captured.set(payment);
                    return payment;
                });

        Map<String, Object> result = sePayService.handleWebhook(webhookPayload(9005L, 50_000L));

        assertEquals("additional_transfer_refund_pending", result.get("message"));
        assertNotNull(captured.get());
        assertEquals(PaymentPurpose.ADDITIONAL_TRANSFER, captured.get().getPurpose());
        assertEquals(PaymentStatus.SUCCESS, captured.get().getStatus());
        assertEquals(50_000L, captured.get().getAmount());
        assertEquals(0L, captured.get().getAcceptedAmount());
        assertEquals(50_000L, captured.get().getRefundRequiredAmount());
        assertEquals("9005", captured.get().getProviderTxnId());
        verify(paymentRefundService).requestCapturedPaymentRefund(
                eq(captured.get()), eq(50_000L), eq(RefundSourceType.ADDITIONAL_TRANSFER),
                any(String.class), any(PaymentProviderEvent.class),
                any(String.class), eq("sepay_webhook"));
        verify(reservationService, never()).convertHoldsAfterPayment(any(Long.class));
    }

    @Test
    void paymentReceivedAfterQrExpiryIsNeverConvertedToDraft() {
        PaymentTransaction transaction = payment(
                PaymentStatus.PENDING, ReservationStatus.PAYMENT_PENDING, 50_000L);
        transaction.setExpiresAt(LocalDateTime.of(2026, 7, 15, 10, 0));
        stubSingleEvent(transaction, "9006");

        Map<String, Object> result = sePayService.handleWebhook(webhookPayload(9006L, 50_000L));

        assertEquals("late_payment_refund_pending", result.get("message"));
        assertEquals(PaymentStatus.SUCCESS, transaction.getStatus());
        assertEquals(0L, transaction.getAcceptedAmount());
        assertEquals(50_000L, transaction.getRefundRequiredAmount());
        verify(paymentRefundService).requestCapturedPaymentRefund(
                eq(transaction), eq(50_000L), eq(RefundSourceType.UNACCEPTED_PAYMENT),
                any(String.class), any(PaymentProviderEvent.class),
                any(String.class), eq("sepay_webhook"));
        verify(reservationService, never()).convertHoldsAfterPayment(any(Long.class));
    }

    @Test
    void webhookAndApiV2IdsForSameReferenceLessTransferAreDeduplicatedByFingerprint() {
        PaymentTransaction transaction = payment(
                PaymentStatus.PENDING, ReservationStatus.PAYMENT_PENDING, 50_000L);
        AtomicReference<PaymentProviderEvent> recordedEvent = new AtomicReference<>();
        stubPaymentLookup(transaction);
        when(providerEventRepository.findByProviderAndProviderReference(
                eq(PaymentProvider.SEPAY), any(String.class)))
                .thenAnswer(invocation -> Optional.ofNullable(recordedEvent.get()));
        when(providerEventRepository.findByProviderAndProviderEventId(PaymentProvider.SEPAY, "9007"))
                .thenReturn(Optional.empty());
        when(providerEventRepository.save(any(PaymentProviderEvent.class)))
                .thenAnswer(invocation -> {
                    PaymentProviderEvent event = invocation.getArgument(0);
                    recordedEvent.set(event);
                    return event;
                });
        when(paymentRefundService.getNetPaidAmount(transaction.getReservation().getId())).thenReturn(0L);

        sePayService.handleWebhook(webhookPayload(9007L, 50_000L));
        sePayService.reconcile(new SePayApiTransaction(
                "api-v2-uuid-9007",
                "2026-07-15T10:30:00+07:00",
                BANK_ACCOUNT,
                "in",
                BigDecimal.valueOf(50_000L),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                PAYMENT_CODE,
                null,
                PAYMENT_CODE,
                "NCB"));

        verify(transactionRepository, times(1)).save(transaction);
        verify(reservationService, times(1))
                .convertHoldsAfterPayment(transaction.getReservation().getId());
        verify(paymentRefundService, never())
                .requestLateCapturedPaymentRefund(any(PaymentTransaction.class), any(String.class));
    }

    @Test
    void sameBankReferenceOnAnotherAccountCannotPoisonMerchantReceipt() {
        PaymentTransaction transaction = payment(
                PaymentStatus.PENDING, ReservationStatus.PAYMENT_PENDING, 50_000L);
        Map<String, PaymentProviderEvent> eventsByReference = new HashMap<>();
        stubPaymentLookup(transaction);
        when(providerEventRepository.findByProviderAndProviderReference(
                eq(PaymentProvider.SEPAY), any(String.class)))
                .thenAnswer(invocation -> Optional.ofNullable(
                        eventsByReference.get(invocation.getArgument(1))));
        when(providerEventRepository.findByProviderAndProviderEventId(
                eq(PaymentProvider.SEPAY), any(String.class))).thenReturn(Optional.empty());
        when(providerEventRepository.save(any(PaymentProviderEvent.class)))
                .thenAnswer(invocation -> {
                    PaymentProviderEvent event = invocation.getArgument(0);
                    eventsByReference.put(event.getProviderReference(), event);
                    return event;
                });
        when(paymentRefundService.getNetPaidAmount(transaction.getReservation().getId())).thenReturn(0L);

        assertThrows(RuntimeException.class, () -> sePayService.handleWebhook(
                webhookPayload(9010L, "9999999999", "SAME-BANK-REFERENCE", 50_000L)));
        Map<String, Object> merchantAccount = sePayService.handleWebhook(
                webhookPayload(9011L, BANK_ACCOUNT, "SAME-BANK-REFERENCE", 50_000L));

        assertEquals("payment_success", merchantAccount.get("message"));
        assertEquals(1, eventsByReference.size());
        verify(transactionRepository, times(1)).save(transaction);
        verify(reservationService, times(1))
                .convertHoldsAfterPayment(transaction.getReservation().getId());
    }

    private PaymentTransaction payment(
            PaymentStatus paymentStatus,
            ReservationStatus reservationStatus,
            long expectedAmount) {
        Reservation reservation = Reservation.builder()
                .reservationCode("RSV-SEPAY-77")
                .status(reservationStatus)
                .totalAmount(BigDecimal.valueOf(100_000L))
                .build();
        reservation.setId(77L);
        return PaymentTransaction.builder()
                .id("payment-sepay-77")
                .reservation(reservation)
                .txnRef(PAYMENT_CODE)
                .provider(PaymentProvider.SEPAY)
                .purpose(PaymentPurpose.DEPOSIT)
                .status(paymentStatus)
                .amount(expectedAmount)
                .expectedAmount(expectedAmount)
                .currency("VND")
                .build();
    }

    private void stubPaymentLookup(PaymentTransaction transaction) {
        when(transactionRepository.findByTxnRef(PAYMENT_CODE)).thenReturn(Optional.of(transaction));
        when(reservationRepository.findByIdForUpdate(transaction.getReservation().getId()))
                .thenReturn(Optional.of(transaction.getReservation()));
        when(transactionRepository.findByTxnRefForUpdate(PAYMENT_CODE))
                .thenReturn(Optional.of(transaction));
        when(transactionRepository.findByProviderAndProviderTxnId(
                eq(PaymentProvider.SEPAY), any(String.class))).thenReturn(Optional.empty());
        lenient().when(transactionRepository.save(any(PaymentTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubSingleEvent(PaymentTransaction transaction, String eventId) {
        stubPaymentLookup(transaction);
        when(providerEventRepository.findByProviderAndProviderEventId(PaymentProvider.SEPAY, eventId))
                .thenReturn(Optional.empty());
        when(providerEventRepository.save(any(PaymentProviderEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private User reviewer() {
        User user = User.builder()
                .username("review-staff")
                .fullName("Review Staff")
                .type(UserType.STAFF)
                .build();
        user.setId(99L);
        return user;
    }

    private User adminReviewer() {
        User user = User.builder()
                .username("recovery-admin")
                .fullName("Recovery Admin")
                .type(UserType.ADMIN)
                .build();
        user.setId(100L);
        return user;
    }

    private ManualPaymentReconciliationRequest manualRequest(String paymentId) {
        ManualPaymentReconciliationRequest request = new ManualPaymentReconciliationRequest();
        request.setPaymentTransactionId(paymentId);
        request.setReasonCode("WEBHOOK_RECOVERY");
        request.setNote("Đã đối chiếu event SePay bền vững trong hàng review");
        request.setEvidenceReference("sepay-dashboard:bank-manual-event");
        return request;
    }

    private byte[] webhookPayload(long eventId, long amount) {
        return webhookPayload(eventId, amount, "2026-07-15 10:30:00");
    }

    private byte[] webhookPayload(long eventId, long amount, String transactionDate) {
        return ("{"
                + "\"id\":" + eventId + ","
                + "\"gateway\":\"NCB\","
                + "\"transactionDate\":\"" + transactionDate + "\","
                + "\"accountNumber\":\"" + BANK_ACCOUNT + "\","
                + "\"code\":\"" + PAYMENT_CODE + "\","
                + "\"content\":\"" + PAYMENT_CODE + "\","
                + "\"transferType\":\"in\","
                + "\"transferAmount\":" + amount
                + "}").getBytes(StandardCharsets.UTF_8);
    }

    private byte[] webhookPayload(long eventId, String accountNumber, String reference, long amount) {
        return ("{"
                + "\"id\":" + eventId + ","
                + "\"gateway\":\"NCB\","
                + "\"transactionDate\":\"2026-07-15 10:30:00\","
                + "\"accountNumber\":\"" + accountNumber + "\","
                + "\"code\":\"" + PAYMENT_CODE + "\","
                + "\"content\":\"" + PAYMENT_CODE + "\","
                + "\"transferType\":\"in\","
                + "\"transferAmount\":" + amount + ","
                + "\"referenceCode\":\"" + reference + "\""
                + "}").getBytes(StandardCharsets.UTF_8);
    }

    private byte[] outgoingWebhookPayload(long eventId, String refundCode, long amount) {
        return ("{"
                + "\"id\":" + eventId + ","
                + "\"gateway\":\"NCB\","
                + "\"transactionDate\":\"2026-07-18 10:30:00\","
                + "\"accountNumber\":\"" + BANK_ACCOUNT + "\","
                + "\"content\":\"HOAN " + refundCode + "\","
                + "\"transferType\":\"out\","
                + "\"transferAmount\":" + amount + ","
                + "\"referenceCode\":\"OUT-" + eventId + "\""
                + "}").getBytes(StandardCharsets.UTF_8);
    }

    private String sign(byte[] rawBody, String timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
    }
}
