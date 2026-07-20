package com.hotel.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.config.SePayConfig;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentPurpose;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.PaymentProviderEventStatus;
import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.constant.RefundSourceType;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.dto.request.SePayWebhookRequest;
import com.hotel.backend.dto.request.SePayEventMatchRequest;
import com.hotel.backend.dto.request.SePayEventIgnoreRequest;
import com.hotel.backend.dto.request.SePayEventRefundRequest;
import com.hotel.backend.dto.request.ManualPaymentReconciliationRequest;
import com.hotel.backend.dto.response.SePayApiTransaction;
import com.hotel.backend.dto.response.SePayPaymentInstructions;
import com.hotel.backend.dto.response.SePayReviewEventResponse;
import com.hotel.backend.dto.response.PaymentRefundResponse;
import com.hotel.backend.dto.response.PaymentResponse;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.PaymentProviderEvent;
import com.hotel.backend.entity.PaymentRefund;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.PaymentProviderEventRepository;
import com.hotel.backend.repository.PaymentRefundRepository;
import com.hotel.backend.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.persistence.EntityManager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j(topic = "SEPAY-SERVICE")
@Service
@RequiredArgsConstructor
public class SePayService {

    private static final Pattern PAYMENT_CODE = Pattern.compile("(?i)LP[0-9A-Z]{8,38}");
    private static final Pattern REFUND_CODE = Pattern.compile(
            "(?i)(?<![0-9A-Z])RF[0-9A-Z]{16}(?![0-9A-Z])");
    private static final EnumSet<RefundStatus> SEPAY_REFUND_PENDING_STATUSES = EnumSet.of(
            RefundStatus.REQUESTED,
            RefundStatus.PROCESSING,
            RefundStatus.READY_FOR_MANUAL_TRANSFER);
    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter PROVIDER_LOCAL_DATE_TIME =
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd HH:mm:ss")
                    .optionalStart()
                    .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
                    .optionalEnd()
                    .toFormatter();

    private final SePayConfig config;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final PaymentTransactionRepository transactionRepository;
    private final PaymentProviderEventRepository providerEventRepository;
    private final PaymentRefundRepository refundRepository;
    private final PaymentProviderEventService providerEventService;
    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final PaymentRefundService paymentRefundService;
    private final ReservationAuditService reservationAuditService;

    public void validateCheckoutConfig() {
        validateQrConfig();
        if (config.getPaymentTimeoutMinutes() <= 0) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "SEPAY_PAYMENT_TIMEOUT_MINUTES phải lớn hơn 0");
        }
        if (config.getWebhookRateLimitPerMinute() <= 0) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "SEPAY_WEBHOOK_RATE_LIMIT_PER_MINUTE phải lớn hơn 0");
        }
        if (config.getRefundWebhookTimeoutMinutes() <= 0) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "SEPAY_REFUND_WEBHOOK_TIMEOUT_MINUTES phải lớn hơn 0");
        }
        boolean webhookReady = hasText(config.getWebhookApiKey())
                || hasText(config.getWebhookSecret());
        boolean reconciliationReady = config.isReconciliationEnabled()
                && hasText(config.getApiAccessToken());
        if (!webhookReady && !reconciliationReady) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "SePay chưa có kênh xác nhận giao dịch: cần SEPAY_WEBHOOK_SECRET, "
                            + "SEPAY_WEBHOOK_API_KEY hoặc bật đối soát với SEPAY_API_ACCESS_TOKEN");
        }
    }

    private void validateQrConfig() {
        if (!hasText(config.getQrBankAccount()) || !hasText(config.getQrBankCode())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Thiếu SEPAY_QR_BANK_ACCOUNT hoặc SEPAY_QR_BANK_CODE");
        }
        if (!config.getQrBankAccount().matches("[A-Za-z0-9]{1,19}")) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "SEPAY_QR_BANK_ACCOUNT chỉ được chứa chữ/số và tối đa 19 ký tự");
        }
        if (!config.getQrBankCode().matches("[A-Za-z0-9]{3,20}")) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "SEPAY_QR_BANK_CODE chỉ được chứa chữ/số từ 3 đến 20 ký tự");
        }
    }

    public String newPaymentCode(Long bookingId) {
        String random = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 6).toUpperCase(Locale.ROOT);
        return ("LP" + bookingId + Long.toString(System.currentTimeMillis(), 36) + random)
                .toUpperCase(Locale.ROOT);
    }

    public LocalDateTime newExpiryTime() {
        return LocalDateTime.now().plusMinutes(config.getPaymentTimeoutMinutes());
    }

    public SePayPaymentInstructions instructionsFor(PaymentTransaction transaction) {
        if (transaction == null || transaction.getProvider() != PaymentProvider.SEPAY) {
            return null;
        }
        validateQrConfig();
        long expectedAmount = transaction.getExpectedAmount() != null
                ? transaction.getExpectedAmount() : transaction.getAmount();
        String qrCodeUrl = UriComponentsBuilder.fromUriString(config.getQrBaseUrl())
                .queryParam("acc", config.getQrBankAccount())
                .queryParam("bank", config.getQrBankCode())
                .queryParam("amount", expectedAmount)
                .queryParam("des", transaction.getTxnRef())
                .queryParam("template", safeTemplate(config.getQrTemplate()))
                .queryParam("showinfo", true)
                .queryParamIfPresent("holder", optional(config.getQrAccountHolder()))
                .queryParamIfPresent("store", optional(config.getStoreName()))
                .build()
                .encode()
                .toUriString();
        return new SePayPaymentInstructions(
                qrCodeUrl,
                transaction.getTxnRef(),
                config.getQrBankAccount(),
                config.getQrBankCode(),
                config.getQrBankName(),
                config.getQrAccountHolder(),
                expectedAmount,
                transaction.getExpiresAt());
    }

    public boolean verifyWebhookSignature(byte[] rawBody, String signature, String timestampValue) {
        if (!hasText(config.getWebhookSecret()) || rawBody == null || rawBody.length == 0
                || !hasText(signature) || !hasText(timestampValue)) {
            return false;
        }
        try {
            long timestamp = Long.parseLong(timestampValue);
            long now = Instant.now().getEpochSecond();
            long tolerance = Math.min(3_600L,
                    Math.max(0L, config.getWebhookTimestampToleranceSeconds()));
            if (timestamp < now - tolerance || timestamp > now + tolerance) {
                return false;
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.getWebhookSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            mac.update((timestampValue + ".").getBytes(StandardCharsets.US_ASCII));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exception) {
            log.warn("Không thể xác thực chữ ký webhook SePay: {}", exception.getMessage());
            return false;
        }
    }

    /**
     * HMAC-SHA256 is the recommended webhook contract. API Key remains a
     * supported alternative. When an API Key is configured, never downgrade to
     * HMAC after a bad Authorization header because that would weaken the
     * explicitly selected authentication mode.
     */
    public boolean verifyWebhookAuthentication(
            byte[] rawBody,
            String authorization,
            String signature,
            String timestampValue) {
        if (hasText(config.getWebhookApiKey())) {
            if (!hasText(authorization)
                    || !authorization.regionMatches(true, 0, "Apikey ", 0, 7)) {
                return false;
            }
            byte[] expected = config.getWebhookApiKey().trim().getBytes(StandardCharsets.UTF_8);
            byte[] presented = authorization.substring(7).trim().getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, presented);
        }
        return verifyWebhookSignature(rawBody, signature, timestampValue);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> handleWebhook(byte[] rawBody) {
        try {
            SePayWebhookRequest payload = objectMapper.readValue(rawBody, SePayWebhookRequest.class);
            // The Dashboard's "Test send" is a signed connectivity probe, not a
            // bank transaction.  It deliberately uses a synthetic merchant
            // account (currently 0000000000), so acknowledge only the provider's
            // explicit test markers and never ingest it into the financial ledger.
            // Real events with a mismatched account still fail in processIncoming.
            if (isProviderTestWebhook(payload)) {
                return Map.of("success", true, "message", "provider_test_acknowledged");
            }
            String outcome = processIncoming(
                    payload.getId() != null ? String.valueOf(payload.getId()) : null,
                    payload.getReferenceCode(),
                    payload.getAccountNumber(),
                    payload.getTransferType(),
                    payload.getTransferAmount(),
                    payload.getAccumulated(),
                    payload.getCode(),
                    payload.getContent(),
                    payload.getGateway(),
                    payload.getTransactionDate(),
                    "sepay_webhook",
                    sha256(rawBody));
            return Map.of("success", true, "message", outcome);
        } catch (java.io.IOException exception) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Payload webhook SePay không hợp lệ");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void reconcile(SePayApiTransaction transaction) {
        if (transaction == null) return;
        long transferAmount = transferAmount(transaction);
        long accumulated = transaction.accumulated() != null
                ? transaction.accumulated().longValue() : 0L;
        processIncoming(
                transaction.id(),
                transaction.referenceNumber(),
                transaction.accountNumber(),
                transaction.transferType(),
                transferAmount,
                accumulated,
                transaction.code(),
                transaction.transactionContent(),
                transaction.bankBrandName(),
                transaction.transactionDate(),
                "sepay_reconciliation",
                sha256(String.join("|",
                        value(transaction.id()),
                        value(transaction.referenceNumber()),
                        value(transaction.accountNumber()),
                        value(transaction.transactionContent()),
                        String.valueOf(transferAmount))));
    }

    @Transactional(readOnly = true)
    public List<SePayReviewEventResponse> getReviewQueue() {
        return providerEventRepository.findByProviderAndStatusOrderByCreatedAtAsc(
                        PaymentProvider.SEPAY,
                        PaymentProviderEventStatus.REVIEW_REQUIRED)
                .stream()
                .map(SePayReviewEventResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public SePayReviewEventResponse getReviewEvent(String eventId) {
        PaymentProviderEvent event = providerEventRepository.findById(eventId)
                .filter(item -> item.getProvider() == PaymentProvider.SEPAY)
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy sự kiện SePay"));
        return SePayReviewEventResponse.from(event);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getManualRecoveryCandidates() {
        return transactionRepository.findManualRecoveryCandidates(
                        PaymentProvider.SEPAY,
                        List.of(PaymentStatus.PENDING, PaymentStatus.CANCELLED, PaymentStatus.FAILED),
                        PageRequest.of(0, 100))
                .stream()
                .map(PaymentResponse::from)
                .toList();
    }

    @Transactional
    public SePayReviewEventResponse matchReviewEvent(
            String eventId,
            SePayEventMatchRequest request,
            User reviewer) {
        PaymentProviderEvent event = requireReviewEventForUpdate(eventId);
        if (!"in".equalsIgnoreCase(value(event.getTransferType()))) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ giao dịch tiền vào mới được match với payment");
        }
        PaymentTransaction snapshot = transactionRepository.findById(request.getPaymentTransactionId())
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy payment cần match"));
        if (snapshot.getProvider() != PaymentProvider.SEPAY) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Review event SePay chỉ được match với payment SePay");
        }

        reservationRepository.findByIdForUpdate(snapshot.getReservation().getId())
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        PaymentTransaction transaction = transactionRepository
                .findByIdForUpdate(snapshot.getId())
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy payment cần match"));

        // A reviewer-supplied timestamp is the explicit correction for an
        // event whose provider time was missing/ambiguous.  Prefer it when
        // present; otherwise retain the durable provider timestamp.
        Instant occurredAt = request.getProviderOccurredAtUtc() != null
                ? request.getProviderOccurredAtUtc() : event.getProviderOccurredAtUtc();
        if (occurredAt == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Event thiếu providerOccurredAt; cần cung cấp providerOccurredAtUtc đã đối soát");
        }
        if (event.getAmount() == null || event.getAmount() <= 0L) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Event không có số tiền vào hợp lệ để match");
        }

        event.setProviderOccurredAtUtc(occurredAt);
        if (request.getProviderOccurredAtUtc() != null
                || !hasText(event.getProviderOccurredAt())) {
            event.setProviderOccurredAt(occurredAt.toString());
        }
        event.setPaymentCode(transaction.getTxnRef());
        event.setPaymentTransaction(transaction);
        event.setReviewedBy(reviewerName(reviewer));
        event.setReviewedAtUtc(Instant.now());
        event.setReviewNote(hasText(request.getNote())
                ? request.getNote().trim() : "Manual match");
        event.setStatus(PaymentProviderEventStatus.PROCESSING);
        event.setProcessingAttempts(
                (event.getProcessingAttempts() != null ? event.getProcessingAttempts() : 0) + 1);
        event.setLastAttemptAtUtc(Instant.now());
        event.setNextRetryAtUtc(null);
        event.setLastError(null);
        providerEventRepository.save(event);

        String outcome = applyIncomingToTransaction(
                event,
                transaction,
                event.getProviderTxnId() != null
                        ? event.getProviderTxnId() : event.getProviderEventId(),
                event.getProviderReference(),
                event.getAmount(),
                "SEPAY",
                event.getProviderOccurredAt(),
                "sepay_manual_match");
        event.setReviewNote(event.getReviewNote() + "; outcome=" + outcome);
        providerEventRepository.save(event);
        reservationAuditService.recordTarget(
                "PAYMENT_PROVIDER_EVENT", event.getId(),
                ReservationAuditAction.PROVIDER_EVENT_MATCHED,
                "Match event vào payment " + transaction.getId() + "; " + outcome);
        return SePayReviewEventResponse.from(event);
    }

    /**
     * ADMIN recovery path. This is not a generic "set paid" operation: it can
     * only reconcile one existing REVIEW_REQUIRED incoming SePay event and it
     * delegates all allocation/late/under/over-payment decisions to the same
     * state machine used by the automatic webhook flow.
     */
    @Transactional
    public SePayReviewEventResponse manuallyReconcileReviewEvent(
            String eventId,
            ManualPaymentReconciliationRequest request,
            User reviewer) {
        PaymentProviderEvent event = requireReviewEventForUpdate(eventId);
        if (!"in".equalsIgnoreCase(value(event.getTransferType()))) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ giao dịch tiền vào mới được đối soát payment thủ công");
        }
        if (!hasText(event.getMerchantAccountId())
                || !event.getMerchantAccountId().equals(configuredMerchantAccountId())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Merchant account của event không khớp cấu hình hiện tại");
        }
        if (event.getProviderOccurredAtUtc() == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Event thiếu provider time; không được nhập thời gian thay thế ở đường khôi phục");
        }
        if (event.getAmount() == null || event.getAmount() <= 0L) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Event không có số tiền vào hợp lệ để đối soát");
        }

        PaymentTransaction snapshot = transactionRepository
                .findById(request.getPaymentTransactionId())
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy payment cần đối soát"));
        if (snapshot.getProvider() != PaymentProvider.SEPAY) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Event SePay chỉ được đối soát với payment SePay");
        }
        if (!List.of(PaymentStatus.PENDING, PaymentStatus.CANCELLED, PaymentStatus.FAILED)
                .contains(snapshot.getStatus())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Payment không còn ở trạng thái cho phép khôi phục; không được ghi đè giao dịch đã chốt");
        }

        reservationRepository.findByIdForUpdate(snapshot.getReservation().getId())
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        PaymentTransaction transaction = transactionRepository
                .findByIdForUpdate(snapshot.getId())
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy payment cần đối soát"));

        String oldPaymentStatus = transaction.getStatus().name();
        String oldReservationStatus = transaction.getReservation().getStatus().name();
        String correlationId = UUID.randomUUID().toString();
        event.setPaymentCode(transaction.getTxnRef());
        event.setPaymentTransaction(transaction);
        event.setReviewedBy(reviewerName(reviewer));
        event.setReviewedAtUtc(Instant.now());
        event.setReviewNote(request.getReasonCode().trim() + ": " + request.getNote().trim());
        event.setStatus(PaymentProviderEventStatus.PROCESSING);
        event.setProcessingAttempts(
                (event.getProcessingAttempts() != null ? event.getProcessingAttempts() : 0) + 1);
        event.setLastAttemptAtUtc(Instant.now());
        event.setNextRetryAtUtc(null);
        event.setLastError(null);
        providerEventRepository.save(event);

        String outcome = applyIncomingToTransaction(
                event,
                transaction,
                hasText(event.getProviderTxnId())
                        ? event.getProviderTxnId() : event.getProviderEventId(),
                event.getProviderReference(),
                event.getAmount(),
                "SEPAY",
                event.getProviderOccurredAt(),
                "sepay_admin_reconciliation");
        event.setReviewNote(event.getReviewNote() + "; outcome=" + outcome);
        providerEventRepository.save(event);

        reservationAuditService.record(
                transaction.getReservation(),
                "PAYMENT_PROVIDER_EVENT",
                event.getId(),
                ReservationAuditAction.PAYMENT_MARKED_PAID_MANUALLY,
                "ADMIN đối soát event SePay bằng state machine thanh toán chuẩn",
                Map.of(
                        "eventStatus", PaymentProviderEventStatus.REVIEW_REQUIRED.name(),
                        "paymentStatus", oldPaymentStatus,
                        "reservationStatus", oldReservationStatus),
                Map.of(
                        "eventStatus", event.getStatus().name(),
                        "paymentStatus", transaction.getStatus().name(),
                        "reservationStatus", transaction.getReservation().getStatus().name()),
                Map.ofEntries(
                        Map.entry("reasonCode", request.getReasonCode().trim()),
                        Map.entry("note", request.getNote().trim()),
                        Map.entry("evidenceReference", value(request.getEvidenceReference()).trim()),
                        Map.entry("providerEventId", value(event.getProviderEventId())),
                        Map.entry("providerTransactionId", value(event.getProviderTxnId())),
                        Map.entry("providerOccurredAtUtc", event.getProviderOccurredAtUtc().toString()),
                        Map.entry("amount", event.getAmount()),
                        Map.entry("outcome", outcome)),
                correlationId,
                "PAYMENT_MARKED_PAID_MANUALLY:" + event.getId());
        return SePayReviewEventResponse.from(event);
    }

    @Transactional
    public SePayReviewEventResponse ignoreReviewEvent(
            String eventId,
            SePayEventIgnoreRequest request,
            User reviewer) {
        PaymentProviderEvent event = requireReviewEventForUpdate(eventId);
        event.setStatus(PaymentProviderEventStatus.IGNORED);
        event.setReviewedBy(reviewerName(reviewer));
        event.setReviewedAtUtc(Instant.now());
        event.setReviewNote(request.getReason().trim());
        event.setMessage("Staff/Admin ignore review event: " + request.getReason().trim());
        event.setProcessedAtUtc(Instant.now());
        event.setNextRetryAtUtc(null);
        event.setLastError(null);
        providerEventRepository.save(event);
        reservationAuditService.recordTarget(
                "PAYMENT_PROVIDER_EVENT", event.getId(),
                ReservationAuditAction.PROVIDER_EVENT_IGNORED,
                request.getReason().trim());
        return SePayReviewEventResponse.from(event);
    }

    @Transactional
    public PaymentRefundResponse refundReviewEvent(
            String eventId,
            SePayEventRefundRequest request,
            User reviewer) {
        PaymentProviderEvent event = requireReviewEventForUpdate(eventId);
        if (!"in".equalsIgnoreCase(value(event.getTransferType()))) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Không được tạo thêm refund từ giao dịch tiền ra chưa khớp");
        }
        PaymentRefundResponse refund = paymentRefundService.requestUnmatchedEventRefund(
                event,
                request.getRefundChannel(),
                request.getReason().trim(),
                reviewerName(reviewer));
        event.setStatus(PaymentProviderEventStatus.PROCESSED);
        event.setReviewedBy(reviewerName(reviewer));
        event.setReviewedAtUtc(Instant.now());
        event.setReviewNote("Tạo refund unmatched " + refund.getRefundId()
                + ": " + request.getReason().trim());
        event.setMessage("Unmatched transfer đã được chuyển thành nghĩa vụ hoàn tiền");
        event.setProcessedAtUtc(Instant.now());
        event.setNextRetryAtUtc(null);
        event.setLastError(null);
        providerEventRepository.save(event);
        reservationAuditService.recordTarget(
                "PAYMENT_PROVIDER_EVENT", event.getId(),
                ReservationAuditAction.PROVIDER_EVENT_REFUND_CREATED,
                "Tạo refund " + refund.getRefundId() + " cho unmatched transfer");
        return refund;
    }

    private PaymentProviderEvent requireReviewEventForUpdate(String eventId) {
        PaymentProviderEvent event = providerEventRepository.findByIdForUpdate(eventId)
                .filter(item -> item.getProvider() == PaymentProvider.SEPAY)
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy sự kiện SePay"));
        if (event.getStatus() != PaymentProviderEventStatus.REVIEW_REQUIRED) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Sự kiện SePay không còn ở trạng thái REVIEW_REQUIRED");
        }
        return event;
    }

    private String reviewerName(User reviewer) {
        if (reviewer == null) return "hotel_system";
        if (hasText(reviewer.getUsername())) return reviewer.getUsername();
        if (hasText(reviewer.getFullName())) return reviewer.getFullName();
        return "user:" + reviewer.getId();
    }

    @Transactional(readOnly = true)
    public boolean hasDurableEvent(SePayApiTransaction transaction) {
        if (transaction == null) return false;
        long transferAmount = transferAmount(transaction);
        long accumulated = transaction.accumulated() != null
                ? transaction.accumulated().longValue() : 0L;
        String payloadHash = sha256(String.join("|",
                value(transaction.id()),
                value(transaction.referenceNumber()),
                value(transaction.accountNumber()),
                value(transaction.transactionContent()),
                String.valueOf(transferAmount)));
        String durableReference = stableProviderReference(
                transaction.referenceNumber(),
                transaction.accountNumber(),
                transaction.transferType(),
                transferAmount,
                accumulated,
                transaction.transactionDate(),
                transaction.transactionContent());
        String eventId = hasText(transaction.id())
                ? transaction.id() : "missing-" + payloadHash.substring(0, 24);
        String merchantAccountId = merchantAccountId(transaction.accountNumber());
        String dedupKey = canonicalDedupKey(
                merchantAccountId,
                durableReference,
                eventId,
                transaction.id(),
                payloadHash,
                transaction.transferType(),
                transferAmount,
                transaction.transactionDate(),
                transaction.transactionContent());
        return findExistingEvent(dedupKey, eventId, durableReference) != null;
    }

    public String configuredMerchantAccountId() {
        return merchantAccountId(configuredMerchantAccountNumber());
    }

    public Instant providerOccurredAtUtc(SePayApiTransaction transaction) {
        return transaction != null ? parseProviderOccurredAt(transaction.transactionDate()) : null;
    }

    private long transferAmount(SePayApiTransaction transaction) {
        if (transaction == null) return 0L;
        if ("out".equalsIgnoreCase(value(transaction.transferType()))) {
            return transaction.amountOut() != null
                    ? transaction.amountOut().longValue() : 0L;
        }
        return transaction.amountIn() != null
                ? transaction.amountIn().longValue() : 0L;
    }

    private String processIncoming(
            String providerTransactionId,
            String providerReference,
            String accountNumber,
            String transferType,
            Long receivedAmount,
            Long accumulated,
            String extractedCode,
            String content,
            String gateway,
            String transactionDate,
            String source,
            String payloadHash) {
        // Merchant identity is part of webhook authentication, not a normal
        // provider-event outcome. Reject before durable ingestion so a forged
        // account cannot become a valid/ignored financial event.
        if (!isConfiguredMerchantAccount(accountNumber)) {
            if ("sepay_webhook".equals(source)) {
                throw new AppException(ErrorCode.INVALID_REQUEST,
                        "Merchant account trong webhook SePay không khớp tài khoản nhận đã cấu hình");
            }
            log.warn("Bỏ qua giao dịch SePay từ merchant account không khớp trong reconciliation");
            return "ignored_different_account";
        }
        String durableReference = stableProviderReference(
                providerReference,
                accountNumber,
                transferType,
                receivedAmount,
                accumulated,
                transactionDate,
                content);
        String eventId = hasText(providerTransactionId)
                ? providerTransactionId : "missing-" + payloadHash.substring(0, 24);
        String paymentCode = resolvePaymentCode(extractedCode, content);
        String merchantAccountId = merchantAccountId(accountNumber);
        String dedupKey = canonicalDedupKey(
                merchantAccountId,
                durableReference,
                eventId,
                providerTransactionId,
                payloadHash,
                transferType,
                receivedAmount,
                transactionDate,
                content);
        Instant providerOccurredAtUtc = parseProviderOccurredAt(transactionDate);
        PaymentProviderEvent duplicateEvent = findExistingEvent(
                dedupKey, eventId, durableReference);
        String duplicateOutcome = existingEventOutcome(duplicateEvent);
        if (duplicateOutcome != null) return duplicateOutcome;

        PaymentProviderEvent event;
        if (duplicateEvent != null) {
            // RECEIVED/FAILED_RETRYABLE may be retried. Terminal states and an
            // in-flight PROCESSING event returned above.
            event = duplicateEvent;
            event.setPayloadHash(payloadHash);
            event.setProviderTxnId(providerTransactionId);
            event.setBankReferenceCode(providerReference);
            event.setMerchantAccountId(merchantAccountId);
            event.setDedupKey(dedupKey);
            event.setTransferType(transferType);
            event.setAccountNumberMasked(maskAccount(accountNumber));
            event.setAmount(receivedAmount);
            event.setPaymentCode(paymentCode);
            event.setProviderOccurredAt(transactionDate);
            event.setProviderOccurredAtUtc(providerOccurredAtUtc);
            if (event.getReceivedAtUtc() == null) {
                event.setReceivedAtUtc(Instant.now());
            }
        } else {
            event = PaymentProviderEvent.builder()
                    .provider(PaymentProvider.SEPAY)
                    .providerEventId(eventId)
                    .providerReference(durableReference)
                    .bankReferenceCode(providerReference)
                    .providerTxnId(providerTransactionId)
                    .merchantAccountId(merchantAccountId)
                    .dedupKey(dedupKey)
                    .status(PaymentProviderEventStatus.RECEIVED)
                    .payloadHash(payloadHash)
                    .transferType(transferType)
                    .accountNumberMasked(maskAccount(accountNumber))
                    .amount(receivedAmount)
                    .paymentCode(paymentCode)
                    .providerOccurredAt(transactionDate)
                    .providerOccurredAtUtc(providerOccurredAtUtc)
                    .receivedAtUtc(Instant.now())
                    .message("Đã nhận sự kiện từ " + source)
                    .build();
        }

        try {
            event = providerEventService.ingest(event);
        } catch (DataIntegrityViolationException race) {
            event = providerEventService.findExisting(event).orElseThrow(() -> race);
        }

        String racedOutcome = existingEventOutcome(event);
        if (racedOutcome != null) return racedOutcome;

        if (providerOccurredAtUtc == null) {
            markEvent(event, PaymentProviderEventStatus.REVIEW_REQUIRED,
                    "Timestamp provider không hợp lệ; cần đối soát thủ công", null);
            return "invalid_provider_time_review_required";
        }

        try {
            event = providerEventService.startProcessing(event);
            // ingest/startProcessing commit in REQUIRES_NEW transactions. The
            // surrounding persistence context may still contain an older
            // @Version snapshot (especially on webhook/reconciliation retry).
            // Detach it and lock a fresh managed instance so the terminal event
            // update commits atomically with payment/refund side effects.
            event = reloadProcessingEvent(event);

            if (receivedAmount == null || receivedAmount <= 0L) {
                markEvent(event, PaymentProviderEventStatus.IGNORED,
                        "Số tiền giao dịch SePay không hợp lệ", null);
                return "ignored_invalid_amount";
            }
            if ("out".equalsIgnoreCase(value(transferType))) {
                return applyOutgoingToRefund(event, content, receivedAmount);
            }
            if (!"in".equalsIgnoreCase(value(transferType))) {
                markEvent(event, PaymentProviderEventStatus.IGNORED,
                        "Loại giao dịch SePay không được hỗ trợ", null);
                return "ignored_unknown_transfer_type";
            }
            // Lookup theo paymentCode không lọc trạng thái. Giao dịch FAILED/CANCELLED
            // vẫn phải được tìm thấy để ghi nhận tiền đến muộn và tạo yêu cầu hoàn.
            PaymentTransaction transaction = findPayment(paymentCode, content);
            if (transaction == null || transaction.getProvider() != PaymentProvider.SEPAY) {
                markEvent(event, PaymentProviderEventStatus.REVIEW_REQUIRED,
                        transaction == null
                                ? "Không tìm thấy mã thanh toán SePay tương ứng"
                                : "Mã thanh toán không thuộc kênh SePay",
                        transaction);
                // Không ghi reference/account/content/amount vào log ứng dụng;
                // các trường này có thể là dữ liệu ngân hàng nhạy cảm.
                log.warn("SePay incoming transfer queued for review: eventId={}",
                        event.getProviderEventId());
                return "payment_review_required";
            }

            reservationRepository.findByIdForUpdate(transaction.getReservation().getId())
                    .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
            transaction = transactionRepository.findByTxnRefForUpdate(transaction.getTxnRef())
                    .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                            "Không tìm thấy giao dịch SePay"));

            return applyIncomingToTransaction(
                    event,
                    transaction,
                    providerTransactionId,
                    durableReference,
                    receivedAmount,
                    gateway,
                    transactionDate,
                    source);
        } catch (RuntimeException failure) {
            providerEventService.markRetryable(event.getId(), failure);
            throw failure;
        }
    }

    private String applyOutgoingToRefund(
            PaymentProviderEvent event,
            String content,
            long transferredAmount) {
        LinkedHashSet<String> refundCodes = resolveRefundCodes(content);
        if (refundCodes.isEmpty()) {
            markEvent(event, PaymentProviderEventStatus.REVIEW_REQUIRED,
                    "Giao dịch tiền ra không chứa refund_code hợp lệ", null);
            log.warn("SePay outgoing transfer without refund code queued for review: eventId={}",
                    event.getProviderEventId());
            return "outgoing_refund_review_required";
        }

        List<PaymentRefund> matches = refundRepository.findPendingByRefundCodes(
                refundCodes,
                RefundChannel.MANUAL_BANK_TRANSFER,
                SEPAY_REFUND_PENDING_STATUSES);
        if (matches.size() != 1) {
            markEvent(event, PaymentProviderEventStatus.REVIEW_REQUIRED,
                    matches.isEmpty()
                            ? "Không tìm thấy refund PENDING khớp refund_code"
                            : "Nội dung chứa nhiều refund_code đang chờ; không tự động gán",
                    null);
            log.warn("SePay outgoing transfer queued for review: eventId={}, candidateCount={}",
                    event.getProviderEventId(), matches.size());
            return "outgoing_refund_review_required";
        }

        PaymentRefund refund = matches.get(0);
        long expectedAmount = refund.getRequestedAmount() != null
                ? refund.getRequestedAmount() : refund.getAmount();
        event.setPaymentCode(refund.getRefundCode());
        if (transferredAmount != expectedAmount) {
            markEvent(event, PaymentProviderEventStatus.REVIEW_REQUIRED,
                    "refund_code đúng nhưng số tiền ra không khớp expected_amount", null);
            return "outgoing_refund_amount_mismatch_review_required";
        }

        PaymentRefundResponse completed = paymentRefundService.completeFromSePayOutgoing(
                refund.getId(), event, transferredAmount);
        if (completed == null) {
            markEvent(event, PaymentProviderEventStatus.REVIEW_REQUIRED,
                    "Refund không còn ở trạng thái cho phép hoàn tất tự động", null);
            return "outgoing_refund_state_conflict_review_required";
        }
        markEvent(event, PaymentProviderEventStatus.PROCESSED,
                "Đã đối chiếu refund_code và expected_amount; refund hoàn tất", null);
        return "outgoing_refund_completed";
    }

    private LinkedHashSet<String> resolveRefundCodes(String content) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = REFUND_CODE.matcher(value(content).toUpperCase(Locale.ROOT));
        while (matcher.find()) {
            result.add(matcher.group().toUpperCase(Locale.ROOT));
        }
        return result;
    }

    private PaymentProviderEvent reloadProcessingEvent(PaymentProviderEvent snapshot) {
        if (snapshot == null || !hasText(snapshot.getId())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Sự kiện provider chưa có định danh để xử lý");
        }
        String eventId = snapshot.getId();
        entityManager.clear();
        return providerEventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy sự kiện provider đang xử lý"));
    }

    /** Shared allocation state machine used by automatic processing and review match. */
    private String applyIncomingToTransaction(
            PaymentProviderEvent event,
            PaymentTransaction transaction,
            String providerTransactionId,
            String durableReference,
            Long receivedAmount,
            String gateway,
            String transactionDate,
            String source) {
        PaymentTransaction duplicate = findAlreadyRecorded(providerTransactionId, durableReference);
        if (duplicate != null) {
            if (!duplicate.getId().equals(transaction.getId())) {
                log.error("Giao dịch SePay trùng provider id/reference với payment khác: incoming={}, existing={}",
                        transaction.getId(), duplicate.getId());
            }
            markEvent(event, PaymentProviderEventStatus.IGNORED,
                    "Giao dịch ngân hàng đã được ghi nhận", transaction);
            return "already_processed";
        }
        if (List.of(PaymentStatus.SUCCESS, PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED)
                .contains(transaction.getStatus())) {
            PaymentTransaction additionalTransfer = recordAdditionalTransfer(
                    transaction,
                    providerTransactionId,
                    durableReference,
                    receivedAmount,
                    gateway,
                    transactionDate);
            paymentRefundService.requestCapturedPaymentRefund(
                    additionalTransfer,
                    receivedAmount,
                    RefundSourceType.ADDITIONAL_TRANSFER,
                    "additional:" + event.getId(),
                    event,
                    "Hoàn toàn bộ khoản chuyển thêm ngoài nghĩa vụ reservation",
                    source);
            reservationAuditService.record(transaction.getReservation(),
                    ReservationAuditAction.PAYMENT_RECEIVED,
                    "Nhận thêm " + receivedAmount + " VND qua SePay; đã tạo nghĩa vụ hoàn");
            markEvent(event, PaymentProviderEventStatus.PROCESSED,
                    "Giao dịch chuyển thêm đã vào hàng đợi hoàn tiền", additionalTransfer);
            return "additional_transfer_refund_pending";
        }

        long expected = transaction.getExpectedAmount() != null
                ? transaction.getExpectedAmount() : transaction.getAmount();
        transaction.setExpectedAmount(expected);
        transaction.setReceivedAmount(receivedAmount);
        // `amount` is a legacy request amount and must not be overwritten by
        // the bank receipt.  Settlement uses expected/received/accepted fields.
        transaction.setProviderTxnId(providerTransactionId);
        transaction.setProviderReference(durableReference);
        transaction.setBankCode(gateway);
        transaction.setCardType("VIETQR");
        transaction.setProviderPayDate(transactionDate);
        transaction.setResponseCode("00");
        transaction.setPaidAt(LocalDateTime.now());
        transaction.setPaidAtUtc(event.getProviderOccurredAtUtc() != null
                ? event.getProviderOccurredAtUtc() : Instant.now());

        boolean recoveredOnTime = transaction.getPurpose() == PaymentPurpose.DEPOSIT
                && transaction.getStatus() == PaymentStatus.CANCELLED
                && reservationService.recoverOnTimeDepositPayment(
                transaction.getReservation().getId(), transaction.getId(),
                event.getProviderOccurredAtUtc());
        boolean latePayment = transaction.getStatus() == PaymentStatus.FAILED
                || (!recoveredOnTime && transaction.getStatus() == PaymentStatus.CANCELLED)
                || (!recoveredOnTime && !canAcceptSuccessfulPayment(
                transaction, event.getProviderOccurredAtUtc()));
        if (latePayment || receivedAmount < expected) {
            boolean underpayment = !latePayment && receivedAmount < expected;
            transaction.setStatus(PaymentStatus.SUCCESS);
            transaction.setAcceptedAmount(0L);
            transaction.setRefundRequiredAmount(receivedAmount);
            transaction.setMessage(latePayment
                    ? "Tiền đến sau khi giao dịch hoặc reservation hết hiệu lực; phải hoàn toàn bộ"
                    : "Số tiền chuyển khoản nhỏ hơn số tiền yêu cầu; phải hoàn toàn bộ");
            transactionRepository.save(transaction);
            if (underpayment) {
                reservationService.cancelForPaymentFailure(
                        transaction.getReservation().getId(),
                        "UNDERPAYMENT",
                        "Số tiền nhận được nhỏ hơn số tiền yêu cầu");
            } else if (transaction.getReservation().getStatus() == ReservationStatus.PAYMENT_PENDING) {
                reservationService.cancelForPaymentFailure(
                        transaction.getReservation().getId(),
                        "LATE_PAYMENT",
                        "Timestamp provider nằm ngoài cửa sổ thanh toán");
            }
            paymentRefundService.requestCapturedPaymentRefund(
                    transaction,
                    receivedAmount,
                    RefundSourceType.UNACCEPTED_PAYMENT,
                    "unaccepted:" + event.getId(),
                    event,
                    transaction.getMessage(),
                    source);
            reservationAuditService.record(transaction.getReservation(),
                    ReservationAuditAction.PAYMENT_RECEIVED,
                    "Nhận " + receivedAmount + " VND qua SePay nhưng không phân bổ; đã tạo nghĩa vụ hoàn");
            markEvent(event, PaymentProviderEventStatus.PROCESSED,
                    transaction.getMessage(), transaction);
            return latePayment ? "late_payment_refund_pending" : "underpayment_refund_pending";
        }

        long paidBefore = paymentRefundService.getNetPaidAmount(transaction.getReservation().getId());
        long reservationTotal = transaction.getReservation().getTotalAmount().longValue();
        long acceptedCapacity = Math.max(0L, reservationTotal - paidBefore);
        long acceptedAmount = Math.min(expected, acceptedCapacity);

        transaction.setStatus(PaymentStatus.SUCCESS);
        transaction.setAcceptedAmount(acceptedAmount);
        transaction.setRefundRequiredAmount(Math.max(0L, receivedAmount - acceptedAmount));
        transaction.setMessage("SePay đã xác nhận tiền vào tài khoản khách sạn");
        transactionRepository.save(transaction);
        if (transaction.getPurpose() == PaymentPurpose.DEPOSIT) {
            reservationService.convertHoldsAfterPayment(transaction.getReservation().getId());
        }
        reservationAuditService.record(transaction.getReservation(),
                ReservationAuditAction.PAYMENT_RECEIVED,
                "Nhận " + receivedAmount + " VND qua SePay, phân bổ " + acceptedAmount + " VND");

        long excess = Math.max(0L, receivedAmount - acceptedAmount);
        if (excess > 0L) {
            paymentRefundService.requestCapturedPaymentRefund(
                    transaction,
                    excess,
                    RefundSourceType.CHECKOUT_OVERPAYMENT,
                    "overpayment:" + event.getId(),
                    event,
                    "Hoàn phần chuyển khoản SePay vượt quá số tiền còn phải thu",
                    source);
            markEvent(event, PaymentProviderEventStatus.PROCESSED,
                    "Đã ghi nhận tiền vào và tạo hoàn phần thừa", transaction);
            return "payment_success_excess_refund_pending";
        }
        markEvent(event, PaymentProviderEventStatus.PROCESSED,
                "Đã ghi nhận thanh toán thành công", transaction);
        return "payment_success";
    }

    private String existingEventOutcome(PaymentProviderEvent event) {
        if (event == null) return null;
        if (event.getStatus() == PaymentProviderEventStatus.REVIEW_REQUIRED) {
            return "review_already_queued";
        }
        if (event.getStatus() == PaymentProviderEventStatus.PROCESSED
                || event.getStatus() == PaymentProviderEventStatus.IGNORED) {
            return "already_processed";
        }
        if (event.getStatus() == PaymentProviderEventStatus.PROCESSING) {
            return "processing_in_progress";
        }
        if (event.getStatus() == PaymentProviderEventStatus.FAILED_RETRYABLE
                && event.getNextRetryAtUtc() != null
                && event.getNextRetryAtUtc().isAfter(Instant.now())) {
            return "retry_scheduled";
        }
        return null;
    }

    private PaymentProviderEvent findExistingEvent(
            String dedupKey,
            String eventId,
            String providerReference) {
        // Contract order: provider event id, then the canonical merchant/
        // transaction (or fingerprint) key, then the legacy reference alias.
        if (hasText(eventId) && !eventId.startsWith("missing-")) {
            PaymentProviderEvent byEventId = providerEventRepository
                    .findByProviderAndProviderEventId(PaymentProvider.SEPAY, eventId)
                    .orElse(null);
            if (byEventId != null) return byEventId;
        }
        if (hasText(dedupKey)) {
            PaymentProviderEvent byDedupKey = providerEventRepository
                    .findByProviderAndDedupKey(PaymentProvider.SEPAY, dedupKey)
                    .orElse(null);
            if (byDedupKey != null) return byDedupKey;
        }
        if (hasText(providerReference)) {
            PaymentProviderEvent byReference = providerEventRepository
                    .findByProviderAndProviderReference(PaymentProvider.SEPAY, providerReference)
                    .orElse(null);
            if (byReference != null) return byReference;
        }
        return null;
    }

    private void markEvent(
            PaymentProviderEvent event,
            PaymentProviderEventStatus status,
            String message,
            PaymentTransaction transaction) {
        event.setStatus(status);
        event.setMessage(message);
        event.setPaymentTransaction(transaction);
        if (List.of(
                PaymentProviderEventStatus.PROCESSED,
                PaymentProviderEventStatus.IGNORED,
                PaymentProviderEventStatus.REVIEW_REQUIRED).contains(status)) {
            event.setProcessedAtUtc(Instant.now());
            event.setNextRetryAtUtc(null);
        }
        providerEventRepository.save(event);
        if (status == PaymentProviderEventStatus.REVIEW_REQUIRED) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("providerEventId", event.getProviderEventId());
            detail.put("transferType", event.getTransferType());
            detail.put("amount", event.getAmount());
            detail.put("reason", message);
            reservationAuditService.recordSystem(
                    transaction != null ? transaction.getReservation() : null,
                    "PAYMENT_PROVIDER_EVENT",
                    event.getId(),
                    ReservationAuditAction.PROVIDER_EVENT_REVIEW_REQUIRED,
                    "Sự kiện SePay cần đối soát thủ công",
                    null,
                    Map.of("status", PaymentProviderEventStatus.REVIEW_REQUIRED.name()),
                    detail,
                    null,
                    "PROVIDER_EVENT_REVIEW_REQUIRED:" + event.getId());
        }
    }

    private String merchantAccountId(String accountNumber) {
        if (hasText(config.getApiBankAccountId())) {
            return config.getApiBankAccountId().trim();
        }
        String normalized = normalizeAccount(accountNumber);
        return "acct:" + sha256(normalized).substring(0, 32);
    }

    /** Canonical order required by the provider-event contract. */
    private String canonicalDedupKey(
            String merchantAccountId,
            String providerReference,
            String providerEventId,
            String providerTxnId,
            String payloadHash,
            String transferType,
            Long receivedAmount,
            String providerOccurredAt,
            String normalizedContent) {
        String namespace;
        String identity;
        if (hasText(providerEventId) && !providerEventId.startsWith("missing-")) {
            namespace = "event";
            identity = providerEventId.trim();
        } else if (hasText(providerTxnId)) {
            namespace = "txn";
            identity = providerTxnId.trim();
        } else {
            namespace = "payload";
            identity = sha256(String.join("|",
                    value(merchantAccountId),
                    value(transferType).trim().toLowerCase(Locale.ROOT),
                    String.valueOf(receivedAmount != null ? receivedAmount : 0L),
                    normalizeProviderDate(providerOccurredAt),
                    value(normalizedContent).trim().replaceAll("\\s+", " ")
                            .toUpperCase(Locale.ROOT)));
        }
        return namespace + ":" + sha256(String.join("|",
                PaymentProvider.SEPAY.name(),
                value(merchantAccountId),
                identity));
    }

    private Instant parseProviderOccurredAt(String rawValue) {
        if (!hasText(rawValue)) return null;
        String trimmed = rawValue.trim();
        try {
            return Instant.parse(trimmed);
        } catch (Exception ignored) {
            // Continue with provider formats below.
        }
        try {
            return OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toInstant();
        } catch (Exception ignored) {
            // Continue with SePay local bank time below.
        }
        try {
            String localValue = trimmed.replace('T', ' ');
            return LocalDateTime.parse(localValue, PROVIDER_LOCAL_DATE_TIME)
                    .atZone(HOTEL_ZONE)
                    .toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private PaymentTransaction findPayment(String extractedCode, String content) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String code = normalizeCode(extractedCode);
        if (hasText(code)) {
            candidates.add(code);
        }
        Matcher matcher = PAYMENT_CODE.matcher(value(content).toUpperCase(Locale.ROOT));
        while (matcher.find()) {
            candidates.add(matcher.group().toUpperCase(Locale.ROOT));
        }
        for (String candidate : candidates) {
            PaymentTransaction match = transactionRepository.findByTxnRef(candidate).orElse(null);
            if (match != null) return match;
        }
        return null;
    }

    private String resolvePaymentCode(String extractedCode, String content) {
        String normalized = normalizeCode(extractedCode);
        if (hasText(normalized) && PAYMENT_CODE.matcher(normalized).matches()) {
            return normalized;
        }
        Matcher matcher = PAYMENT_CODE.matcher(value(content).toUpperCase(Locale.ROOT));
        return matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : null;
    }

    private PaymentTransaction findAlreadyRecorded(String providerTransactionId, String providerReference) {
        if (hasText(providerTransactionId)) {
            PaymentTransaction byTransaction = transactionRepository
                    .findByProviderAndProviderTxnId(PaymentProvider.SEPAY, providerTransactionId)
                    .orElse(null);
            if (byTransaction != null) return byTransaction;
        }
        if (hasText(providerReference)) {
            return transactionRepository
                    .findByProviderAndProviderReference(PaymentProvider.SEPAY, providerReference)
                    .orElse(null);
        }
        return null;
    }

    private boolean isConfiguredMerchantAccount(String accountNumber) {
        String configuredAccount = configuredMerchantAccountNumber();
        return hasText(accountNumber)
                && hasText(configuredAccount)
                && normalizeAccount(configuredAccount)
                .equals(normalizeAccount(accountNumber));
    }

    private String configuredMerchantAccountNumber() {
        return hasText(config.getMerchantBankAccount())
                ? config.getMerchantBankAccount()
                : config.getQrBankAccount();
    }

    private boolean isProviderTestWebhook(SePayWebhookRequest payload) {
        if (payload == null || payload.getId() == null || payload.getId() != 0L) {
            return false;
        }
        return "SEPAYTEST".equalsIgnoreCase(value(payload.getCode()).trim())
                && "SEPAY TEST WEBHOOK".equalsIgnoreCase(value(payload.getContent()).trim())
                && "SEPAY".equalsIgnoreCase(value(payload.getGateway()).trim())
                && "in".equalsIgnoreCase(value(payload.getTransferType()).trim())
                && value(payload.getReferenceCode()).trim().toUpperCase(Locale.ROOT)
                .startsWith("TEST")
                && value(payload.getDescription()).trim().toLowerCase(Locale.ROOT)
                .contains("sepay test webhook");
    }

    private String stableProviderReference(
            String providerReference,
            String accountNumber,
            String transferType,
            Long amount,
            Long accumulated,
            String transactionDate,
            String content) {
        if (hasText(providerReference)) {
            // SePay does not guarantee that a bank reference is globally unique.
            // Scope it with the receiving account and the transaction attributes
            // that are stable across webhook and API v2 reconciliation.
            String scopedReference = sha256(String.join("|",
                    normalizeAccount(accountNumber),
                    providerReference.trim().toUpperCase(Locale.ROOT),
                    value(transferType).trim().toLowerCase(Locale.ROOT),
                    String.valueOf(amount != null ? amount : 0L),
                    normalizeProviderDate(transactionDate)));
            return "SEPAY-REF-" + scopedReference.substring(0, 48);
        }
        String fingerprint = sha256(String.join("|",
                normalizeAccount(accountNumber),
                String.valueOf(amount != null ? amount : 0L),
                String.valueOf(accumulated != null ? accumulated : 0L),
                normalizeProviderDate(transactionDate),
                value(content).trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT)));
        return "SEPAY-FP-" + fingerprint.substring(0, 48);
    }

    private String normalizeProviderDate(String value) {
        String normalized = value(value).trim().replace('T', ' ');
        return normalized.length() > 19 ? normalized.substring(0, 19) : normalized;
    }

    /**
     * Mỗi giao dịch ngân hàng là một lần tiền thật vào tài khoản. Nếu
     * khách quét/chuyển lần nữa sau khi payment code đã được xử lý,
     * không được coi đó là webhook trùng. Ghi một payment riêng để
     * ledger hoàn tiền không làm mất khoản thu thêm này.
     */
    private PaymentTransaction recordAdditionalTransfer(
            PaymentTransaction original,
            String providerTransactionId,
            String providerReference,
            long receivedAmount,
            String gateway,
            String transactionDate) {
        String suffix = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 10).toUpperCase(Locale.ROOT);
        PaymentTransaction captured = PaymentTransaction.builder()
                .reservation(original.getReservation())
                .txnRef(original.getTxnRef() + "X" + suffix)
                .providerTxnId(providerTransactionId)
                .providerReference(providerReference)
                .provider(PaymentProvider.SEPAY)
                .purpose(PaymentPurpose.ADDITIONAL_TRANSFER)
                .status(PaymentStatus.SUCCESS)
                .amount(receivedAmount)
                .expectedAmount(0L)
                .receivedAmount(receivedAmount)
                .acceptedAmount(0L)
                .refundRequiredAmount(receivedAmount)
                .currency("VND")
                .orderInfo("Khoản chuyển thêm cho mã SePay " + original.getTxnRef())
                .bankCode(gateway)
                .cardType("VIETQR")
                .providerPayDate(transactionDate)
                .responseCode("00")
                .paidAt(LocalDateTime.now())
                .paidAtUtc(parseProviderOccurredAt(transactionDate) != null
                        ? parseProviderOccurredAt(transactionDate) : Instant.now())
                .message("Khách chuyển thêm sau khi mã thanh toán đã được xử lý; phải hoàn toàn bộ")
                .build();
        return transactionRepository.save(captured);
    }

    private boolean canAcceptSuccessfulPayment(
            PaymentTransaction transaction,
            Instant providerOccurredAtUtc) {
        if (providerOccurredAtUtc == null) {
            return false;
        }
        if (transaction.getCreatedAt() != null) {
            // SePay transactionDate is normally precise only to whole seconds,
            // while PostgreSQL/JPA keeps payment.created_at at microsecond precision.
            // Compare against the start of the creation second so a real transfer
            // stamped "10:30:00" is not rejected merely because the QR row was
            // persisted at "10:30:00.026".
            Instant acceptanceStartsAt = toProviderInstant(transaction.getCreatedAt())
                    .truncatedTo(ChronoUnit.SECONDS);
            if (providerOccurredAtUtc.isBefore(acceptanceStartsAt)) {
                return false;
            }
        }
        Instant expiresAt = transaction.getExpiresAtUtc() != null
                ? transaction.getExpiresAtUtc()
                : transaction.getExpiresAt() != null
                ? toProviderInstant(transaction.getExpiresAt()) : null;
        if (expiresAt != null && !providerOccurredAtUtc.isBefore(expiresAt)) {
            return false;
        }
        ReservationStatus status = transaction.getReservation().getStatus();
        if (transaction.getPurpose() == PaymentPurpose.DEPOSIT) {
            return status == ReservationStatus.PAYMENT_PENDING;
        }
        return status == ReservationStatus.CHECKED_IN;
    }

    private Instant toProviderInstant(LocalDateTime localDateTime) {
        return localDateTime.atZone(HOTEL_ZONE).toInstant();
    }

    private String safeTemplate(String template) {
        String normalized = value(template).trim().toLowerCase(Locale.ROOT);
        return java.util.Set.of("compact", "qronly", "standee").contains(normalized)
                ? normalized : "compact";
    }

    private java.util.Optional<String> optional(String value) {
        return hasText(value) ? java.util.Optional.of(value.trim()) : java.util.Optional.empty();
    }

    private String normalizeAccount(String value) {
        return value(value).replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private String normalizeCode(String value) {
        return value(value).replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private String maskAccount(String value) {
        String normalized = normalizeAccount(value);
        if (normalized.length() <= 4) return normalized;
        return "****" + normalized.substring(normalized.length() - 4);
    }

    private String sha256(String value) {
        return sha256(value(value).getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value != null ? value : new byte[0]));
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể tạo hash sự kiện SePay", exception);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String value(String value) {
        return value != null ? value : "";
    }
}
