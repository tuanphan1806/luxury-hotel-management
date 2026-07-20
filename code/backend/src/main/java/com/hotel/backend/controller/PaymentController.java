package com.hotel.backend.controller;

import com.hotel.backend.dto.request.PaymentRequest;
import com.hotel.backend.dto.request.ManualRefundCompleteRequest;
import com.hotel.backend.dto.request.ManualRefundFallbackOpenRequest;
import com.hotel.backend.dto.request.CashRefundCompleteRequest;
import com.hotel.backend.dto.response.PaymentResponse;
import com.hotel.backend.dto.response.ManualRefundDetailsResponse;
import com.hotel.backend.dto.response.PublicPaymentResultResponse;
import com.hotel.backend.dto.response.PaymentRefundResponse;
import com.hotel.backend.dto.response.RefundPayoutConfigResponse;
import com.hotel.backend.dto.response.SePayReviewEventResponse;
import com.hotel.backend.dto.request.RefundRequest;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.User;
import com.hotel.backend.service.PaymentService;
import com.hotel.backend.service.PaymentRefundService;
import com.hotel.backend.service.RefundPayoutConfigService;
import com.hotel.backend.service.PaymentResultService;
import com.hotel.backend.service.VNPayService;
import com.hotel.backend.service.SePayService;
import com.hotel.backend.service.RefundRecipientService;
import com.hotel.backend.service.IdempotencyService;
import com.hotel.backend.service.AuthRateLimitService;
import com.hotel.backend.service.BusinessMetricService;
import com.hotel.backend.service.BusinessMonitoringService;
import com.hotel.backend.security.ClientIpResolver;
import com.hotel.backend.config.SePayConfig;
import com.hotel.backend.dto.request.RefundRecipientRequest;
import com.hotel.backend.dto.request.SePayEventMatchRequest;
import com.hotel.backend.dto.request.SePayEventIgnoreRequest;
import com.hotel.backend.dto.request.SePayEventRefundRequest;
import com.hotel.backend.dto.request.ManualPaymentReconciliationRequest;
import com.hotel.backend.dto.request.CancelRefundRequest;
import com.hotel.backend.dto.response.RefundRecipientResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j(topic = "PAYMENT-CONTROLLER")
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentResultService paymentResultService;
    private final VNPayService vnPayService;
    private final SePayService sePayService;
    private final PaymentRefundService paymentRefundService;
    private final RefundPayoutConfigService refundPayoutConfigService;
    private final RefundRecipientService refundRecipientService;
    private final IdempotencyService idempotencyService;
    private final AuthRateLimitService authRateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final SePayConfig sePayConfig;
    private final BusinessMetricService businessMetrics;
    private final BusinessMonitoringService businessMonitoringService;

    @Value("${app.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    // ==================== TẠO GIAO DỊCH ====================

    /**
     * POST /api/payments/create
     * NextJS gọi endpoint này để lấy hướng dẫn SePay VietQR và URL trang theo dõi.
     */
    @Operation(summary = "Create payment", description = "API create a payment transaction and return the payment URL")
    @PostMapping("/create")
    public ResponseEntity<PaymentResponse> createPayment(
            @Valid @RequestBody PaymentRequest request,
            HttpServletRequest httpRequest,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @AuthenticationPrincipal User currentUser) {

        log.info("Yêu cầu tạo thanh toán: bookingId={}, provider={}, orderInfo={}",
                request.getBookingId(), request.getProvider(), request.getOrderInfo());

        String actorScope = idempotencyService.actorScope(currentUser, guestToken);
        PaymentResponse response = idempotencyService.execute(
                idempotencyKey,
                "PAYMENT_CREATE",
                actorScope,
                request,
                "PAYMENT_TRANSACTION",
                () -> paymentService.createPayment(request, httpRequest, currentUser, guestToken),
                PaymentResponse::getTransactionId,
                transactionId -> paymentService.replayPaymentResponse(
                        transactionId, currentUser, guestToken));
        response.setPaymentUrl(frontendBaseUrl.replaceAll("/+$", "")
                + "/booking/payment-result?transactionId=" + response.getTransactionId());
        return ResponseEntity.ok(response);
    }

    // ==================== SEPAY WEBHOOK ====================

    /** API Key là contract hiện hành của SePay; HMAC chỉ còn là fallback legacy. */
    @Operation(summary = "SePay transaction webhook",
            description = "Receive a signed incoming-bank-transaction notification from SePay")
    @PostMapping(value = "/sepay/webhook", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> sePayWebhook(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-SePay-Signature", required = false) String signature,
            @RequestHeader(value = "X-SePay-Timestamp", required = false) String timestamp,
            HttpServletRequest httpRequest) {
        String clientIp = clientIpResolver.resolve(httpRequest);
        authRateLimitService.check(
                "sepay-webhook-ip:" + clientIp,
                sePayConfig.getWebhookRateLimitPerMinute(),
                Duration.ofMinutes(1));
        if (!sePayService.verifyWebhookAuthentication(
                rawBody, authorization, signature, timestamp)) {
            businessMetrics.increment("hotel.sepay.webhook.authentication.rejected");
            businessMonitoringService.recordWebhookAuthenticationFailure();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "Invalid SePay webhook authentication"));
        }
        Map<String, Object> outcome = sePayService.handleWebhook(rawBody);
        businessMetrics.increment(
                "hotel.sepay.webhook.outcomes",
                "outcome", businessMetrics.outcomeTag(outcome.get("message")));
        // ACK tối giản theo đúng contract SePay; kết quả nội bộ đã
        // được lưu trong payment_provider_events, không làm thay đổi body ACK.
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/sepay/events/review")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<List<SePayReviewEventResponse>> getSePayReviewQueue() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(sePayService.getReviewQueue());
    }

    @GetMapping("/sepay/events/{eventId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<SePayReviewEventResponse> getSePayEvent(
            @PathVariable String eventId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(sePayService.getReviewEvent(eventId));
    }

    @GetMapping("/sepay/recovery-candidates")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<PaymentResponse>> getSePayRecoveryCandidates() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(sePayService.getManualRecoveryCandidates());
    }

    @PatchMapping("/sepay/events/{eventId}/match")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<SePayReviewEventResponse> matchSePayEvent(
            @PathVariable String eventId,
            @Valid @RequestBody SePayEventMatchRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        SePayReviewEventResponse response = idempotencyService.execute(
                idempotencyKey,
                "SEPAY_EVENT_MATCH",
                idempotencyService.actorScope(currentUser, null),
                Map.of("eventId", eventId, "request", request),
                "PAYMENT_PROVIDER_EVENT",
                () -> sePayService.matchReviewEvent(eventId, request, currentUser),
                SePayReviewEventResponse::eventId,
                sePayService::getReviewEvent);
        return ResponseEntity.ok(response);
    }

    /**
     * Recovery-only path for a durable SePay event that could not be matched
     * automatically. It never accepts amount, provider time or payment status
     * from the client and therefore cannot create money outside the ledger.
     */
    @PatchMapping("/sepay/events/{eventId}/manual-reconcile")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SePayReviewEventResponse> manuallyReconcileSePayEvent(
            @PathVariable String eventId,
            @Valid @RequestBody ManualPaymentReconciliationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        SePayReviewEventResponse response = idempotencyService.execute(
                idempotencyKey,
                "SEPAY_EVENT_MANUAL_RECONCILIATION",
                idempotencyService.actorScope(currentUser, null),
                Map.of("eventId", eventId, "request", request),
                "PAYMENT_PROVIDER_EVENT",
                () -> sePayService.manuallyReconcileReviewEvent(eventId, request, currentUser),
                SePayReviewEventResponse::eventId,
                sePayService::getReviewEvent);
        businessMetrics.increment("hotel.sepay.manual.reconciliation", "result", "completed");
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/sepay/events/{eventId}/ignore")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<SePayReviewEventResponse> ignoreSePayEvent(
            @PathVariable String eventId,
            @Valid @RequestBody SePayEventIgnoreRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        SePayReviewEventResponse response = idempotencyService.execute(
                idempotencyKey,
                "SEPAY_EVENT_IGNORE",
                idempotencyService.actorScope(currentUser, null),
                Map.of("eventId", eventId, "request", request),
                "PAYMENT_PROVIDER_EVENT",
                () -> sePayService.ignoreReviewEvent(eventId, request, currentUser),
                SePayReviewEventResponse::eventId,
                sePayService::getReviewEvent);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/sepay/events/{eventId}/refund")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<PaymentRefundResponse> refundSePayEvent(
            @PathVariable String eventId,
            @Valid @RequestBody SePayEventRefundRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        PaymentRefundResponse response = idempotencyService.execute(
                idempotencyKey,
                "SEPAY_EVENT_REFUND",
                idempotencyService.actorScope(currentUser, null),
                Map.of("eventId", eventId, "request", request),
                "PAYMENT_REFUND",
                () -> sePayService.refundReviewEvent(eventId, request, currentUser),
                PaymentRefundResponse::getRefundId,
                paymentRefundService::getById);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /api/payments/cash
     * Staff ghi nhận thanh toán tiền mặt tại quầy.
     */
    @Operation(summary = "Create cash payment", description = "API record a cash payment for a booking")
    @PostMapping("/cash")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<PaymentResponse> createCashPayment(
            @Valid @RequestBody PaymentRequest request,
            HttpServletRequest httpRequest,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {

        log.info("Yêu cầu ghi nhận tiền mặt: bookingId={}, orderInfo={}",
                request.getBookingId(), request.getOrderInfo());

        String actorScope = idempotencyService.actorScope(currentUser, null);
        PaymentResponse response = idempotencyService.execute(
                idempotencyKey,
                "CASH_PAYMENT",
                actorScope,
                request,
                "PAYMENT_TRANSACTION",
                () -> paymentService.createCashPayment(request, httpRequest, currentUser),
                PaymentResponse::getTransactionId,
                transactionId -> paymentService.replayPaymentResponse(
                        transactionId, currentUser, null));
        return ResponseEntity.ok(response);
    }

    // ==================== VNPAY CALLBACKS ====================

    /**
     * GET /api/payments/vnpay/return
     * VNPay redirect khách hàng về đây sau khi thanh toán (qua browser)
     */
    @Operation(summary = "VNPay return callback", description = "API handle VNPay browser return callback after payment")
    @GetMapping("/vnpay/return")
    public void vnpayReturn(
            @RequestParam Map<String, String> params,
            HttpServletResponse response) throws IOException {

        log.info("VNPay Return URL được gọi");

        try {
            PaymentTransaction transaction = vnPayService.handleReturn(params);
            // Chỉ chuyển mã UUID tra cứu. Trạng thái, số tiền và booking được
            // frontend đối chiếu lại từ backend, không tin dữ liệu query string.
            String redirectUrl = String.format(
                    "%s/booking/payment-result?transactionId=%s",
                    frontendBaseUrl, transaction.getId()
            );
            response.sendRedirect(redirectUrl);

        } catch (Exception e) {
            log.error("Lỗi xử lý VNPay Return: {}", e.getMessage());
            response.sendRedirect(frontendBaseUrl + "/booking/payment-result?status=error");
        }
    }

    /**
     * GET /api/payments/vnpay/ipn
     * VNPay server gọi endpoint này để xác nhận giao dịch (server-to-server)
     * Đây là endpoint QUAN TRỌNG NHẤT để cập nhật DB
     */
    @Operation(summary = "VNPay IPN callback", description = "API handle VNPay server-to-server payment notification")
    @GetMapping("/vnpay/ipn")
    public ResponseEntity<Map<String, String>> vnpayIPN(
            @RequestParam Map<String, String> params) {

        log.info("VNPay IPN được gọi");
        Map<String, String> result = vnPayService.handleIPN(params);
        return ResponseEntity.ok(result);
    }

    // ==================== TRUY VẤN GIAO DỊCH ====================

    /**
     * Kết quả tối thiểu cho trang return. UUID transaction đóng vai trò mã tra cứu
     * khó đoán; response không trả thông tin cá nhân hoặc guest token.
     */
    @GetMapping("/result/{transactionId}")
    public ResponseEntity<PublicPaymentResultResponse> getPublicPaymentResult(
            @PathVariable String transactionId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(paymentResultService.getPublicResult(transactionId));
    }

    @PostMapping("/result/{transactionId}/abandon")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abandonPendingPayment(@PathVariable String transactionId) {
        paymentService.abandonPendingPayment(transactionId);
    }

    /**
     * GET /api/payments/{transactionId}
     * Lấy thông tin một giao dịch
     */
    @Operation(summary = "Get payment transaction", description = "API retrieve payment transaction detail by transaction id")
    @GetMapping("/{transactionId}")
    public ResponseEntity<PaymentResponse> getTransaction(
            @PathVariable String transactionId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(PaymentResponse.from(
                paymentService.getTransaction(transactionId, currentUser)));
    }

    /**
     * GET /api/payments/booking/{bookingId}
     * Lấy tất cả giao dịch của một booking
     */
    @Operation(summary = "Get booking payments", description = "API retrieve all payment transactions for a reservation")
    @GetMapping("/booking/{reservationId}")
    public ResponseEntity<List<PaymentResponse>> getByBooking(
            @PathVariable Long reservationId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(paymentService.getTransactionsByReservation(reservationId, currentUser)
                .stream()
                .map(PaymentResponse::from)
                .toList());
    }

    // ==================== HOÀN TIỀN ====================

    /**
     * POST /api/payments/refund
     * Yêu cầu hoàn tiền
     */
    @Operation(summary = "Refund payment", description = "API create a refund request for a payment transaction")
    @PostMapping("/refund")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<PaymentRefundResponse> refund(
            @Valid @RequestBody RefundRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        log.info("Yêu cầu hoàn tiền: transactionId={}, amount={}",
                request.getTransactionId(), request.getAmount());
        PaymentRefundResponse response = idempotencyService.execute(
                idempotencyKey,
                "REFUND_CREATE",
                idempotencyService.actorScope(currentUser, null),
                request,
                "PAYMENT_REFUND",
                () -> paymentService.refund(request, currentUser),
                PaymentRefundResponse::getRefundId,
                paymentRefundService::getById);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/refunds/pending")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<List<PaymentRefundResponse>> getPendingRefunds() {
        return ResponseEntity.ok(paymentService.getPendingRefunds());
    }

    @GetMapping("/refunds/payout-config")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<RefundPayoutConfigResponse> getRefundPayoutConfig() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(refundPayoutConfigService.getMasked());
    }

    @PutMapping("/refunds/{refundId}/recipient")
    public ResponseEntity<RefundRecipientResponse> submitRefundRecipient(
            @PathVariable String refundId,
            @Valid @RequestBody RefundRecipientRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser,
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        return ResponseEntity.ok(idempotencyService.execute(
                idempotencyKey,
                "REFUND_RECIPIENT_SUBMIT",
                idempotencyService.actorScope(currentUser, guestToken),
                Map.of("refundId", refundId, "request", request),
                "REFUND_RECIPIENT",
                () -> refundRecipientService.submitForRefund(
                        refundId, request, currentUser, guestToken),
                RefundRecipientResponse::getRecipientId,
                ignored -> refundRecipientService.getMaskedForRefund(
                        refundId, currentUser, guestToken)));
    }

    @GetMapping("/refunds/{refundId}/recipient")
    public ResponseEntity<RefundRecipientResponse> getRefundRecipient(
            @PathVariable String refundId,
            @AuthenticationPrincipal User currentUser,
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(refundRecipientService.getMaskedForRefund(
                        refundId, currentUser, guestToken));
    }

    @GetMapping("/refund/{refundId}/manual-details")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<ManualRefundDetailsResponse> getManualRefundDetails(
            @PathVariable String refundId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(paymentRefundService.getManualDetails(refundId));
    }

    @PatchMapping("/refund/{refundId}/manual-complete")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<PaymentRefundResponse> completeManualRefund(
            @PathVariable String refundId,
            @Valid @RequestBody ManualRefundCompleteRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(idempotencyService.execute(
                idempotencyKey,
                "REFUND_COMPLETE_BANK",
                idempotencyService.actorScope(currentUser, null),
                Map.of("refundId", refundId, "request", request),
                "PAYMENT_REFUND",
                () -> paymentRefundService.completeManualTransfer(refundId, request, currentUser),
                PaymentRefundResponse::getRefundId,
                paymentRefundService::getById));
    }

    @PatchMapping("/refund/{refundId}/manual-fallback/open")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PaymentRefundResponse> openManualRefundFallback(
            @PathVariable String refundId,
            @Valid @RequestBody ManualRefundFallbackOpenRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(idempotencyService.execute(
                idempotencyKey,
                "REFUND_OPEN_MANUAL_FALLBACK",
                idempotencyService.actorScope(currentUser, null),
                Map.of("refundId", refundId, "request", request),
                "PAYMENT_REFUND",
                () -> paymentRefundService.openManualFallback(refundId, request, currentUser),
                PaymentRefundResponse::getRefundId,
                paymentRefundService::getById));
    }

    @PatchMapping("/refund/{refundId}/cash-complete")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<PaymentRefundResponse> completeCashRefund(
            @PathVariable String refundId,
            @Valid @RequestBody CashRefundCompleteRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(idempotencyService.execute(
                idempotencyKey,
                "REFUND_COMPLETE_CASH",
                idempotencyService.actorScope(currentUser, null),
                Map.of("refundId", refundId, "request", request),
                "PAYMENT_REFUND",
                () -> paymentRefundService.completeCashAtCounter(refundId, request, currentUser),
                PaymentRefundResponse::getRefundId,
                paymentRefundService::getById));
    }

    @PatchMapping("/refund/{refundId}/reconcile")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<PaymentRefundResponse> reconcileRefund(
            @PathVariable String refundId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(idempotencyService.execute(
                idempotencyKey,
                "REFUND_RECONCILE",
                idempotencyService.actorScope(currentUser, null),
                Map.of("refundId", refundId),
                "PAYMENT_REFUND",
                () -> paymentService.reconcileRefund(refundId),
                PaymentRefundResponse::getRefundId,
                paymentRefundService::getById));
    }

    @PatchMapping("/refund/{refundId}/retry")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<PaymentRefundResponse> retryRefund(
            @PathVariable String refundId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(idempotencyService.execute(
                idempotencyKey,
                "REFUND_RETRY",
                idempotencyService.actorScope(currentUser, null),
                Map.of("refundId", refundId),
                "PAYMENT_REFUND",
                () -> paymentService.retryRefund(refundId),
                PaymentRefundResponse::getRefundId,
                paymentRefundService::getById));
    }

    /** Cancel a refund ledger row while preserving the obligation history. */
    @PatchMapping({"/refunds/{refundId}/cancel", "/refund/{refundId}/cancel"})
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
    public ResponseEntity<PaymentRefundResponse> cancelRefund(
            @PathVariable String refundId,
            @Valid @RequestBody CancelRefundRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(idempotencyService.execute(
                idempotencyKey,
                "REFUND_CANCEL",
                idempotencyService.actorScope(currentUser, null),
                Map.of("refundId", refundId, "request", request),
                "PAYMENT_REFUND",
                () -> paymentRefundService.cancel(refundId, request, currentUser),
                PaymentRefundResponse::getRefundId,
                paymentRefundService::getById));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
