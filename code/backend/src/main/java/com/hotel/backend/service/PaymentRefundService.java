package com.hotel.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotel.backend.config.VNPayConfig;
import com.hotel.backend.config.SePayConfig;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.HoldStatus;
import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.constant.RefundCompletionMethod;
import com.hotel.backend.constant.RefundDestinationStatus;
import com.hotel.backend.constant.RefundRecipientStatus;
import com.hotel.backend.constant.RefundRoute;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.ReservationCancellationReasonCode;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.constant.RefundSourceType;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.constant.UploadFolder;
import com.hotel.backend.dto.request.ManualRefundCompleteRequest;
import com.hotel.backend.dto.request.ManualRefundFallbackOpenRequest;
import com.hotel.backend.dto.request.CashRefundCompleteRequest;
import com.hotel.backend.dto.request.CancelRefundRequest;
import com.hotel.backend.dto.request.RefundRequest;
import com.hotel.backend.dto.response.ManualRefundDetailsResponse;
import com.hotel.backend.dto.response.PaymentRefundResponse;
import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.dto.response.ReservationRefundResponse;
import com.hotel.backend.entity.PaymentRefund;
import com.hotel.backend.entity.PaymentProviderEvent;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.RefundRecipient;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.User;
import com.hotel.backend.event.CheckoutReconciliationChangedEvent;
import com.hotel.backend.event.VNPayRefundRequestedEvent;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.PaymentRefundRepository;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.RefundRecipientRepository;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.repository.RoomHoldRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRefundService {

    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String CANCELLATION_SOURCE_PREFIX = "reservation-cancellation:";

    /**
     * Tóm tắt ledger hoàn tiền công khai cho một payment. Không suy
     * diễn kết quả hoàn từ PaymentStatus vì một payment có thể chỉ
     * được hoàn một phần hoặc còn một khoản đang chờ xử lý.
     */
    public record PaymentRefundSummary(
            long completedAmount,
            long outstandingAmount,
            RefundChannel latestChannel,
            RefundStatus latestStatus) {

        public static PaymentRefundSummary empty() {
            return new PaymentRefundSummary(0L, 0L, null, null);
        }
    }

    // FAILED vẫn giữ hạn mức: thao tác tiếp theo phải retry cùng bản ghi hoàn tiền,
    // không được tạo một khoản hoàn mới cho cùng số tiền.
    private static final EnumSet<RefundStatus> RESERVED_STATUSES =
            EnumSet.of(RefundStatus.AWAITING_CUSTOMER_INFO,
                    RefundStatus.READY_FOR_MANUAL_TRANSFER,
                    RefundStatus.REQUESTED, RefundStatus.PROCESSING,
                    RefundStatus.SUCCEEDED, RefundStatus.FAILED,
                    RefundStatus.MANUAL_REVIEW);
    // Tiền đã được dành cho một refund không được tiếp tục dùng
    // để xác nhận cọc, tính số còn phải thu hoặc checkout, kể cả
    // khi Staff/nhà cung cấp chưa hoàn tất chuyển tiền.
    private static final EnumSet<RefundStatus> NET_DEDUCTED_STATUSES =
            EnumSet.copyOf(RESERVED_STATUSES);
    private static final EnumSet<RefundStatus> BANK_CONFIRMATION_PENDING_STATUSES = EnumSet.of(
            RefundStatus.REQUESTED,
            RefundStatus.PROCESSING,
            RefundStatus.READY_FOR_MANUAL_TRANSFER);
    private static final List<RefundSourceType> REQUIRED_OBLIGATION_SOURCES = List.of(
            RefundSourceType.UNACCEPTED_PAYMENT,
            RefundSourceType.ADDITIONAL_TRANSFER,
            RefundSourceType.CHECKOUT_OVERPAYMENT);

    private final PaymentRefundRepository refundRepository;
    private final RefundRecipientRepository recipientRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final ReservationRepository reservationRepository;
    private final RoomHoldRepository roomHoldRepository;
    private final VNPayRefundGateway vnPayGateway;
    private final VNPayConfig vnPayConfig;
    private final SePayConfig sePayConfig;
    private final ApplicationEventPublisher eventPublisher;
    private final PlatformTransactionManager transactionManager;
    private final RefundDataCipher refundDataCipher;
    private final ReservationAuditService reservationAuditService;
    private final MediaAssetService mediaAssetService;

    @Value("${app.refund.cancellation-policy-code:CURRENT_CANCELLATION_POLICY}")
    private String cancellationPolicyCode;

    @Value("${app.refund.cancellation-policy-version:2026-07-19}")
    private String cancellationPolicyVersion;

    @Transactional
    public PaymentRefundResponse requestTransactionRefund(RefundRequest request, String requestedBy) {
        PaymentTransaction snapshot = transactionRepository.findById(request.getTransactionId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy giao dịch: " + request.getTransactionId()));
        reservationRepository.findByIdForUpdate(snapshot.getReservation().getId())
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        PaymentTransaction payment = transactionRepository.findByIdForUpdate(request.getTransactionId())
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy giao dịch: " + request.getTransactionId()));
        if (payment.getReservation().getStatus() != ReservationStatus.CANCELLED) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Endpoint hoàn theo giao dịch chỉ dùng cho reservation đã hủy");
        }
        List<PaymentRefund> created = allocate(List.of(payment), request.getAmount(),
                normalizeRefundChannel(request.getRefundChannel()), request.getReason(), requestedBy,
                RefundSourceType.ACCEPTED_ALLOCATION, null, null);
        if (created.size() != 1) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Không thể tạo yêu cầu hoàn cho giao dịch");
        }
        return PaymentRefundResponse.from(created.get(0));
    }

    /**
     * Tạo hoàn toàn bộ cho khoản tiền online đã vào tài khoản/capture nhưng callback đến sau khi
     * reservation không còn được phép nhận thanh toán. Gọi ngay trong transaction
     * callback để trạng thái nhận tiền và yêu cầu hoàn luôn commit cùng nhau.
     */
    @Transactional
    public PaymentRefundResponse requestLateCapturedPaymentRefund(
            PaymentTransaction payment,
            String requestedBy) {
        if (payment == null || payment.getId() == null
                || !List.of(PaymentProvider.VNPAY, PaymentProvider.SEPAY).contains(payment.getProvider())
                || payment.getAmount() == null || payment.getAmount() <= 0L) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Giao dịch online đến trễ không đủ dữ liệu để tạo yêu cầu hoàn");
        }
        long amount = payment.getReceivedAmount() != null
                ? payment.getReceivedAmount() : payment.getAmount();
        return requestCapturedPaymentRefund(
                payment,
                amount,
                RefundSourceType.UNACCEPTED_PAYMENT,
                "unaccepted:" + payment.getId(),
                null,
                "Hoàn toàn bộ giao dịch online đến sau khi reservation hết hiệu lực "
                        + payment.getTxnRef(),
                requestedBy);
    }

    /** Creates an idempotent refund tied to one captured payment/event source. */
    @Transactional
    public PaymentRefundResponse requestCapturedPaymentRefund(
            PaymentTransaction payment,
            long amount,
            RefundSourceType sourceType,
            String sourceKey,
            PaymentProviderEvent providerEvent,
            String reason,
            String requestedBy) {
        if (payment == null || payment.getId() == null || amount <= 0L) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Nguồn tiền hoàn không hợp lệ");
        }
        if (hasText(sourceKey)) {
            PaymentRefund existing = refundRepository.findBySourceKey(sourceKey).orElse(null);
            if (existing != null) return PaymentRefundResponse.from(existing);
        }
        if (payment.getReservation() != null) {
            reservationRepository.findByIdForUpdate(payment.getReservation().getId())
                    .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        }
        List<PaymentRefund> created = allocate(
                List.of(payment), amount, RefundChannel.MANUAL_BANK_TRANSFER,
                reason, requestedBy, sourceType, sourceKey, providerEvent);
        if (created.size() != 1) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Không thể tạo yêu cầu hoàn cho giao dịch");
        }
        return PaymentRefundResponse.from(created.get(0));
    }

    /**
     * Creates a refund obligation directly from an unmatched provider event.
     * No synthetic reservation or payment transaction is introduced.
     */
    @Transactional
    public PaymentRefundResponse requestUnmatchedEventRefund(
            PaymentProviderEvent providerEvent,
            RefundChannel channel,
            String reason,
            String requestedBy) {
        if (providerEvent == null || providerEvent.getId() == null
                || providerEvent.getProvider() != PaymentProvider.SEPAY
                || providerEvent.getAmount() == null || providerEvent.getAmount() <= 0L) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Sự kiện SePay không đủ dữ liệu để tạo hoàn tiền");
        }
        if (!List.of(RefundChannel.MANUAL_BANK_TRANSFER, RefundChannel.CASH_AT_COUNTER)
                .contains(channel)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Unmatched transfer chỉ hoàn bằng chuyển khoản thủ công hoặc tiền mặt");
        }

        String sourceKey = "unmatched:" + providerEvent.getId();
        PaymentRefund existing = refundRepository.findBySourceKey(sourceKey).orElse(null);
        if (existing != null) return PaymentRefundResponse.from(existing);

        RefundStatus status = channel == RefundChannel.CASH_AT_COUNTER
                ? RefundStatus.REQUESTED
                : RefundStatus.AWAITING_CUSTOMER_INFO;
        PaymentRefund refund = refundRepository.save(PaymentRefund.builder()
                .paymentTransaction(null)
                .reservation(null)
                .providerEvent(providerEvent)
                .sourceType(RefundSourceType.UNMATCHED_TRANSFER)
                .sourceKey(sourceKey)
                .provider(PaymentProvider.SEPAY)
                .channel(channel)
                .status(status)
                .amount(providerEvent.getAmount())
                .requestedAmount(providerEvent.getAmount())
                .requestId(vnPayGateway.newRequestId())
                .refundCode(newRefundCode())
                .transactionType("02")
                .reason(reason)
                .message(channel == RefundChannel.CASH_AT_COUNTER
                        ? "Chờ Staff/Admin hoàn tiền mặt cho unmatched transfer"
                        : "Chờ nhập tài khoản nhận hoàn cho unmatched transfer")
                .requestedBy(hasText(requestedBy) ? requestedBy : "hotel_system")
                .requestedAt(LocalDateTime.now(HOTEL_ZONE))
                .requestedAtUtc(Instant.now())
                .build());
        return PaymentRefundResponse.from(refund);
    }

    /** Phân bổ khoản hoàn vào ledger thu gốc nhưng dùng kênh chi do Staff/Admin chọn. */
    @Transactional
    public List<PaymentRefundResponse> requestReservationRefund(
            Long reservationId,
            long amount,
            String reason,
            String requestedBy) {
        return requestReservationRefund(reservationId, amount,
                RefundChannel.MANUAL_BANK_TRANSFER, reason, requestedBy);
    }

    @Transactional
    public List<PaymentRefundResponse> requestReservationRefund(
            Long reservationId,
            long amount,
            RefundChannel refundChannel,
            String reason,
            String requestedBy) {
        if (amount <= 0L) return List.of();
        reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        List<PaymentTransaction> payments = transactionRepository.findByReservationId(reservationId).stream()
                .sorted(Comparator.comparing(PaymentTransaction::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return allocate(payments, amount, normalizeRefundChannel(refundChannel), reason, requestedBy,
                RefundSourceType.ACCEPTED_ALLOCATION, null, null).stream()
                .map(PaymentRefundResponse::from)
                .toList();
    }

    /**
     * Creates the refund obligation that gates a reservation cancellation.
     * The source key is deterministic per reservation/payment so retries cannot
     * silently create a second cancellation obligation.
     */
    @Transactional
    public List<PaymentRefundResponse> requestCancellationRefund(
            Long reservationId,
            long amount,
            RefundChannel refundChannel,
            String reason,
            String requestedBy) {
        return requestCancellationRefund(
                reservationId,
                amount,
                refundChannel,
                reason,
                requestedBy,
                amount,
                0L,
                "NO_PENALTY",
                null);
    }

    /**
     * Creates a cancellation refund with an immutable policy snapshot. The
     * caller provides only the already validated penalty decision; allocation
     * still happens against canonical captured-payment ledger rows.
     */
    @Transactional
    public List<PaymentRefundResponse> requestCancellationRefund(
            Long reservationId,
            long amount,
            RefundChannel refundChannel,
            String reason,
            String requestedBy,
            long originalAmount,
            long penaltyAmount,
            String policyApplied,
            String policyNote) {
        if (amount <= 0L) return List.of();
        if (originalAmount <= 0L || penaltyAmount < 0L
                || penaltyAmount > originalAmount
                || amount != originalAmount - penaltyAmount) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Snapshot phí hủy không khớp số tiền hoàn từ ledger");
        }
        reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        List<PaymentTransaction> payments = transactionRepository.findByReservationId(reservationId).stream()
                .sorted(Comparator.comparing(PaymentTransaction::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        List<PaymentRefund> created = allocate(
                payments, amount, normalizeRefundChannel(refundChannel), reason, requestedBy,
                RefundSourceType.ACCEPTED_ALLOCATION,
                cancellationSourcePrefix(reservationId), null);
        created.forEach(refund -> refund.setRefundDetailJson(cancellationRefundDetail(
                cancellationSourcePrefix(reservationId),
                value(refund.getAmount()),
                originalAmount,
                penaltyAmount,
                amount,
                policyApplied,
                policyNote)));
        refundRepository.saveAll(created);
        return created.stream()
                .map(PaymentRefundResponse::from)
                .toList();
    }

    /**
     * Contract cũ gửi một provider cho cả reservation. Giá trị này không còn
     * được tin cậy; backend luôn định tuyến theo từng giao dịch thu tiền gốc.
     */
    @Deprecated
    @Transactional
    public List<PaymentRefundResponse> requestReservationRefund(
            Long reservationId,
            long amount,
            PaymentProvider ignoredProvider,
            String reason,
            String requestedBy) {
        return requestReservationRefund(reservationId, amount, reason, requestedBy);
    }

    private List<PaymentRefund> allocate(
            List<PaymentTransaction> payments,
            long amount,
            RefundChannel selectedChannel,
            String reason,
            String requestedBy) {
        return allocate(payments, amount, selectedChannel, reason, requestedBy,
                RefundSourceType.ACCEPTED_ALLOCATION, null, null);
    }

    private List<PaymentRefund> allocate(
            List<PaymentTransaction> payments,
            long amount,
            RefundChannel selectedChannel,
            String reason,
            String requestedBy,
            RefundSourceType sourceType,
            String sourceKey,
            PaymentProviderEvent providerEvent) {
        long remaining = amount;
        List<PaymentRefund> created = new ArrayList<>();

        RefundRecipient currentRecipient = payments.stream()
                .map(PaymentTransaction::getReservation)
                .filter(java.util.Objects::nonNull)
                .map(reservation -> recipientRepository
                        .findFirstByReservationIdAndStatusInOrderByCreatedAtDesc(
                                reservation.getId(),
                                EnumSet.of(RefundRecipientStatus.SUBMITTED,
                                        RefundRecipientStatus.VERIFIED))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);

        for (PaymentTransaction payment : payments) {
            if (remaining <= 0L) break;
            if (!List.of(PaymentStatus.SUCCESS, PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED)
                    .contains(payment.getStatus())) continue;
            long legacyGap = legacyCompletedGap(payment);
            if (legacyGap > 0L) {
                throw new AppException(ErrorCode.INVALID_REQUEST,
                        String.format("Giao dịch %s còn %,d VND hoàn cũ chưa có sổ đối soát; "
                                        + "đã chặn hoàn mới để tránh hoàn trùng",
                                payment.getTxnRef(), legacyGap));
            }

            RefundSourceType effectiveSourceType = sourceType != null
                    ? sourceType : RefundSourceType.LEGACY;
            long reserved = refundRepository.findByPaymentTransactionId(payment.getId()).stream()
                    .filter(refund -> RESERVED_STATUSES.contains(refund.getStatus()))
                    .filter(refund -> sameRefundPool(refund.getSourceType(), effectiveSourceType))
                    .mapToLong(refund -> value(refund.getAmount()))
                    .sum();
            long available = Math.max(0L,
                    refundableBase(payment, effectiveSourceType) - reserved);
            long refundAmount = Math.min(remaining, available);
            if (refundAmount <= 0L) continue;

            String originalDate = originalTransactionDate(payment);
            RefundChannel channel = selectedChannel;
            RefundStatus initialStatus = channel == RefundChannel.CASH_AT_COUNTER
                    ? RefundStatus.REQUESTED
                    : currentRecipient == null
                    ? RefundStatus.AWAITING_CUSTOMER_INFO
                    : RefundStatus.REQUESTED;

            PaymentRefund refund = PaymentRefund.builder()
                    .paymentTransaction(payment)
                    .reservation(payment.getReservation())
                    .providerEvent(providerEvent)
                    .provider(payment.getProvider())
                    .channel(channel)
                    .status(initialStatus)
                    .sourceType(effectiveSourceType)
                    .sourceKey(rowSourceKey(sourceKey, payment))
                    .recipient(channel == RefundChannel.MANUAL_BANK_TRANSFER
                            ? currentRecipient : null)
                    .amount(refundAmount)
                    .requestedAmount(refundAmount)
                    .refundCode(newRefundCode())
                    .requestId(vnPayGateway.newRequestId())
                    .transactionType(refundAmount < payment.getAmount() ? "03" : "02")
                    .originalTransactionDate(originalDate)
                    .reason(reason)
                    .message(channel == RefundChannel.CASH_AT_COUNTER
                            ? "Chờ Staff/Admin giao tiền mặt và xác nhận đã giao tiền"
                            : initialStatus == RefundStatus.AWAITING_CUSTOMER_INFO
                            ? "Chờ khách hàng cung cấp thông tin ngân hàng để hoàn bằng QR"
                            : "Đã có thông tin ngân hàng; chờ SePay xác nhận tự động giao dịch tiền ra")
                    .requestedBy(hasText(requestedBy) ? requestedBy : "hotel_system")
                    .requestedAt(LocalDateTime.now(HOTEL_ZONE))
                    .requestedAtUtc(Instant.now())
                    .manualFallbackAvailableAtUtc(
                            channel == RefundChannel.MANUAL_BANK_TRANSFER
                                    && currentRecipient != null
                                    ? newManualFallbackAvailableAt() : null)
                    .refundDetailJson(cancellationRefundDetail(sourceKey, refundAmount))
                    .build();
            refund = refundRepository.save(refund);
            created.add(refund);
            syncLegacyPaymentState(payment);
            remaining -= refundAmount;
        }

        if (remaining > 0L) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    String.format("Không đủ giao dịch hợp lệ để hoàn còn thiếu %,d VND", remaining));
        }
        return created;
    }

    private JsonNode cancellationRefundDetail(String sourceKey, long refundAmount) {
        return cancellationRefundDetail(
                sourceKey,
                refundAmount,
                refundAmount,
                0L,
                refundAmount,
                "NO_PENALTY",
                null);
    }

    private JsonNode cancellationRefundDetail(
            String sourceKey,
            long allocatedRefundAmount,
            long originalAmount,
            long penaltyAmount,
            long totalRefundAmount,
            String policyApplied,
            String policyNote) {
        if (!hasText(sourceKey) || !sourceKey.startsWith(CANCELLATION_SOURCE_PREFIX)) return null;
        ObjectNode detail = JsonNodeFactory.instance.objectNode();
        detail.put("currency", "VND");
        detail.put("originalAmount", originalAmount);
        BigDecimal penaltyPercent = originalAmount > 0L
                ? BigDecimal.valueOf(penaltyAmount)
                .multiply(BigDecimal.valueOf(100L))
                .divide(BigDecimal.valueOf(originalAmount), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        detail.put("penaltyPercent", penaltyPercent);
        detail.put("penaltyAmount", penaltyAmount);
        detail.put("refundAmount", totalRefundAmount);
        detail.put("allocatedRefundAmount", allocatedRefundAmount);
        detail.put("policyApplied", hasText(policyApplied)
                ? policyApplied.trim() : "MANUAL_PENALTY");
        if (hasText(policyNote)) detail.put("policyNote", policyNote.trim());
        detail.put("policyCode", hasText(cancellationPolicyCode)
                ? cancellationPolicyCode : "CURRENT_CANCELLATION_POLICY");
        detail.put("policyVersion", hasText(cancellationPolicyVersion)
                ? cancellationPolicyVersion : "2026-07-19");
        return detail;
    }

    private long refundableBase(PaymentTransaction payment, RefundSourceType sourceType) {
        if (payment.getAcceptedAmount() == null) {
            return value(payment.getAmount());
        }
        if (List.of(
                RefundSourceType.UNACCEPTED_PAYMENT,
                RefundSourceType.ADDITIONAL_TRANSFER,
                RefundSourceType.CHECKOUT_OVERPAYMENT).contains(sourceType)) {
            return value(payment.getRefundRequiredAmount());
        }
        return value(payment.getAcceptedAmount());
    }

    private boolean sameRefundPool(RefundSourceType existing, RefundSourceType requested) {
        if (existing == null || existing == RefundSourceType.LEGACY) return true;
        if (requested == null) return true;
        if (List.of(
                RefundSourceType.UNACCEPTED_PAYMENT,
                RefundSourceType.ADDITIONAL_TRANSFER,
                RefundSourceType.CHECKOUT_OVERPAYMENT).contains(requested)) {
            return List.of(
                    RefundSourceType.UNACCEPTED_PAYMENT,
                    RefundSourceType.ADDITIONAL_TRANSFER,
                    RefundSourceType.CHECKOUT_OVERPAYMENT).contains(existing);
        }
        return !List.of(
                RefundSourceType.UNACCEPTED_PAYMENT,
                RefundSourceType.ADDITIONAL_TRANSFER,
                RefundSourceType.CHECKOUT_OVERPAYMENT).contains(existing);
    }

    private String newRefundCode() {
        return "RF" + java.util.UUID.randomUUID().toString().replace("-", "")
                .substring(0, 16).toUpperCase(java.util.Locale.ROOT);
    }

    private Instant newManualFallbackAvailableAt() {
        int minutes = Math.max(1, sePayConfig.getRefundWebhookTimeoutMinutes());
        return Instant.now().plusSeconds(minutes * 60L);
    }

    private String cancellationSourcePrefix(Long reservationId) {
        return CANCELLATION_SOURCE_PREFIX + reservationId + ":";
    }

    private String rowSourceKey(String sourceKey, PaymentTransaction payment) {
        if (!hasText(sourceKey)) return sourceKey;
        return sourceKey.endsWith(":") && payment != null && payment.getId() != null
                ? sourceKey + payment.getId()
                : sourceKey;
    }

    /**
     * Được listener gọi sau khi transaction nghiệp vụ đã commit. Chỉ giữ khóa DB
     * trong hai transaction ngắn trước/sau HTTP; tuyệt đối không giữ pessimistic
     * lock trong lúc chờ VNPay.
     */
    public PaymentRefundResponse submitToVNPay(String refundId) {
        SubmissionClaim claim = requiresNew(() -> claimSubmission(refundId));
        if (claim.immediateResponse() != null) return claim.immediateResponse();

        try {
            VNPayProviderResult result = vnPayGateway.refund(claim.refund());
            return requiresNew(() -> finishSubmission(refundId, result, null));
        } catch (Exception exception) {
            return requiresNew(() -> finishSubmission(refundId, null, exception));
        }
    }

    private SubmissionClaim claimSubmission(String refundId) {
        lockRefundAggregate(refundId);
        PaymentRefund refund = refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy yêu cầu hoàn tiền: " + refundId));
        if (refund.getChannel() != RefundChannel.VNPAY_ORIGINAL) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Yêu cầu này không hoàn qua VNPay");
        }
        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            finalizeReservationAfterCancellationRefund(refund);
            return new SubmissionClaim(null, PaymentRefundResponse.from(refund));
        }
        if (!vnPayConfig.isRefundEnabled()) {
            refund.setMessage("VNPay refund đang tắt bởi cấu hình VNPAY_REFUND_ENABLED");
            return new SubmissionClaim(null,
                    PaymentRefundResponse.from(refundRepository.save(refund)));
        }
        if (!List.of(RefundStatus.REQUESTED, RefundStatus.FAILED).contains(refund.getStatus())) {
            return new SubmissionClaim(null, PaymentRefundResponse.from(refund));
        }

        try {
            vnPayGateway.validateRefundRequest(refund);
        } catch (Exception exception) {
            refund.setStatus(RefundStatus.FAILED);
            refund.setResponseCode("LOCAL_VALIDATION");
            refund.setMessage("Chưa gửi refund vì cấu hình/dữ liệu merchant không hợp lệ: "
                    + safeMessage(exception));
            refund = refundRepository.save(refund);
            syncLegacyPaymentState(refund.getPaymentTransaction());
            return new SubmissionClaim(null, PaymentRefundResponse.from(refund));
        }

        refund.setStatus(RefundStatus.PROCESSING);
        refund.setResponseCode("SUBMITTING");
        refund.setTransactionStatus(null);
        refund.setMessage("Đã nhận việc gửi refund; đang chờ phản hồi VNPay");
        refund = refundRepository.save(refund);
        syncLegacyPaymentState(refund.getPaymentTransaction());
        return new SubmissionClaim(refund, null);
    }

    private PaymentRefundResponse finishSubmission(
            String refundId,
            VNPayProviderResult result,
            Exception exception) {
        lockRefundAggregate(refundId);
        PaymentRefund refund = refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy yêu cầu hoàn tiền: " + refundId));
        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            finalizeReservationAfterCancellationRefund(refund);
            return PaymentRefundResponse.from(refund);
        }
        if (exception != null) {
            // Không gửi request thứ hai khi chưa biết request đầu đã tới VNPay hay chưa.
            // QueryDR là bước tiếp theo an toàn hơn một refund mới.
            refund.setStatus(RefundStatus.PROCESSING);
            refund.setResponseCode("NO_RESPONSE");
            refund.setMessage("Không nhận được phản hồi refund hợp lệ; cần QueryDR: "
                    + safeMessage(exception));
            log.error("VNPay refund outcome unknown: refundId={}, txnRef={}", refund.getId(),
                    refund.getPaymentTransaction().getTxnRef(), exception);
        } else {
            applyProviderResult(refund, result, false);
        }
        refund = refundRepository.save(refund);
        syncLegacyPaymentState(refund.getPaymentTransaction());
        finalizeReservationAfterCancellationRefund(refund);
        return PaymentRefundResponse.from(refund);
    }

    public PaymentRefundResponse reconcile(String refundId) {
        PaymentRefund snapshot = requiresNew(() -> loadRefundForQuery(refundId));
        if (snapshot.getStatus() == RefundStatus.SUCCEEDED) {
            return PaymentRefundResponse.from(snapshot);
        }
        try {
            VNPayProviderResult result = vnPayGateway.query(snapshot);
            return requiresNew(() -> finishReconcile(refundId, result, null));
        } catch (Exception exception) {
            return requiresNew(() -> finishReconcile(refundId, null, exception));
        }
    }

    private PaymentRefund loadRefundForQuery(String refundId) {
        lockRefundAggregate(refundId);
        PaymentRefund refund = refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy yêu cầu hoàn tiền: " + refundId));
        if (!vnPayConfig.isRefundEnabled()) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "VNPay refund đang tắt bởi cấu hình VNPAY_REFUND_ENABLED");
        }
        if (refund.getChannel() != RefundChannel.VNPAY_ORIGINAL) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Chỉ refund VNPay mới cần QueryDR");
        }
        if (refund.getStatus() != RefundStatus.PROCESSING
                && refund.getStatus() != RefundStatus.SUCCEEDED) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ yêu cầu VNPay đang chờ kết quả mới được QueryDR");
        }
        return refund;
    }

    private PaymentRefundResponse finishReconcile(
            String refundId,
            VNPayProviderResult result,
            Exception exception) {
        lockRefundAggregate(refundId);
        PaymentRefund refund = refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy yêu cầu hoàn tiền: " + refundId));
        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            finalizeReservationAfterCancellationRefund(refund);
            return PaymentRefundResponse.from(refund);
        }
        // Không ghi đè một quyết định mới hơn (ví dụ staff đã chuyển sang
        // MANUAL_REVIEW) bằng phản hồi của HTTP QueryDR bắt đầu từ state cũ.
        if (refund.getStatus() != RefundStatus.PROCESSING) {
            return PaymentRefundResponse.from(refund);
        }
        if (exception == null) {
            if ("00".equals(result.responseCode())
                    && List.of("02", "03").contains(result.transactionType())) {
                applyProviderResult(refund, result, true);
            } else {
                refund.setResponseCode(result.responseCode());
                refund.setTransactionStatus(result.transactionStatus());
                refund.setMessage("VNPay chưa trả về giao dịch hoàn tương ứng: " + result.message());
                refund.setStatus(RefundStatus.PROCESSING);
            }
        } else {
            refund.setMessage("QueryDR chưa xác định được trạng thái hoàn tiền: " + safeMessage(exception));
            refund.setStatus(RefundStatus.PROCESSING);
        }
        refund = refundRepository.save(refund);
        syncLegacyPaymentState(refund.getPaymentTransaction());
        finalizeReservationAfterCancellationRefund(refund);
        return PaymentRefundResponse.from(refund);
    }

    public PaymentRefundResponse retry(String refundId) {
        RetryOutcome outcome = requiresNew(() -> {
            PaymentRefund refund = refundRepository.findByIdForUpdate(refundId)
                    .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                            "Không tìm thấy yêu cầu hoàn tiền: " + refundId));
            if (refund.getStatus() == RefundStatus.CANCELLED) {
                if (refund.getChannel() == RefundChannel.VNPAY_ORIGINAL) {
                    throw new AppException(ErrorCode.INVALID_REQUEST,
                            "Không thể kích hoạt lại refund VNPay đã hủy vì request cũ có thể vẫn đang xử lý");
                }
                String cancelledReason = refund.getCancellationReason();
                RefundStatus reactivated = refund.getChannel() == RefundChannel.CASH_AT_COUNTER
                        ? RefundStatus.REQUESTED
                        : refund.getRecipient() == null
                        ? RefundStatus.AWAITING_CUSTOMER_INFO
                        : RefundStatus.REQUESTED;
                refund.setStatus(reactivated);
                refund.setCancelledAtUtc(null);
                refund.setCancelledBy(null);
                refund.setCancellationReason(null);
                refund.setActualRefundAmount(null);
                refund.setCompletedAt(null);
                refund.setCompletedAtUtc(null);
                refund.setResponseCode(null);
                refund.setTransactionStatus(null);
                refund.setCompletionMethod(null);
                refund.setCompletionProviderEvent(null);
                refund.setManualFallbackAvailableAtUtc(
                        refund.getChannel() == RefundChannel.MANUAL_BANK_TRANSFER
                                && refund.getRecipient() != null
                                ? newManualFallbackAvailableAt() : null);
                refund.setManualFallbackOpenedAtUtc(null);
                refund.setManualFallbackOpenedBy(null);
                refund.setManualFallbackReason(null);
                refund.setMessage("Đã kích hoạt lại nghĩa vụ hoàn tiền sau khi refund trước bị hủy");
                refund = refundRepository.save(refund);
                syncLegacyPaymentStateIfPresent(refund.getPaymentTransaction());
                auditRefund(refund, ReservationAuditAction.REFUND,
                        "Kích hoạt lại refund sau khi hủy: "
                                + (cancelledReason == null ? "" : cancelledReason));
                return new RetryOutcome(PaymentRefundResponse.from(refund), false);
            }
            if (refund.getChannel() != RefundChannel.VNPAY_ORIGINAL) {
                throw new AppException(ErrorCode.INVALID_REQUEST,
                        "Chỉ refund VNPay mới được gửi lại tới VNPay");
            }
            if (!List.of(RefundStatus.REQUESTED, RefundStatus.FAILED).contains(refund.getStatus())) {
                throw new AppException(ErrorCode.INVALID_REQUEST,
                        "Chỉ yêu cầu chưa gửi hoặc bị VNPay từ chối mới được gửi lại");
            }
            refund.setRequestHistory(appendRequestHistory(
                    refund.getRequestHistory(), refund.getRequestId()));
            refund.setRequestId(vnPayGateway.newRequestId());
            refund.setStatus(RefundStatus.REQUESTED);
            refund.setResponseCode(null);
            refund.setTransactionStatus(null);
            refund.setMessage("Đang gửi lại yêu cầu hoàn VNPay với requestId mới");
            refundRepository.save(refund);
            syncLegacyPaymentStateIfPresent(refund.getPaymentTransaction());
            return new RetryOutcome(null, true);
        });
        if (!outcome.submitToProvider()) return outcome.response();
        return submitToVNPay(refundId);
    }

    /**
     * Cancels a refund row without deleting it.  Cancellation is intentionally
     * conservative: a provider request in PROCESSING (or a non-cash REQUESTED
     * request that may already have crossed the network) cannot be cancelled
     * locally.  A cancelled obligation is excluded from active reservations but
     * remains visible and can be reactivated with the retry endpoint.
     */
    @Transactional
    public PaymentRefundResponse cancel(
            String refundId,
            CancelRefundRequest request,
            User currentUser) {
        String reason = request != null && request.getReason() != null
                ? request.getReason().trim() : "";
        if (reason.isBlank()) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Lý do hủy refund không được để trống");
        }
        lockRefundAggregate(refundId);
        PaymentRefund refund = refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy yêu cầu hoàn tiền: " + refundId));
        if (refund.getStatus() == RefundStatus.CANCELLED) {
            return PaymentRefundResponse.from(refund);
        }
        if (refund.getStatus() == RefundStatus.SUCCEEDED
                || refund.getStatus() == RefundStatus.PROCESSING
                || (refund.getStatus() == RefundStatus.REQUESTED
                && refund.getChannel() != RefundChannel.CASH_AT_COUNTER)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Refund đã gửi hoặc đang xử lý; cần đối soát thay vì hủy cục bộ");
        }
        if (!List.of(RefundStatus.AWAITING_CUSTOMER_INFO,
                RefundStatus.READY_FOR_MANUAL_TRANSFER,
                RefundStatus.REQUESTED,
                RefundStatus.FAILED,
                RefundStatus.MANUAL_REVIEW).contains(refund.getStatus())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Refund hiện không thể hủy ở trạng thái " + refund.getStatus());
        }
        String operator = currentUser != null && hasText(currentUser.getUsername())
                ? currentUser.getUsername() : "hotel_system";
        refund.setStatus(RefundStatus.CANCELLED);
        refund.setCancelledAtUtc(Instant.now());
        refund.setCancelledBy(operator);
        refund.setCancellationReason(reason);
        refund.setMessage("Refund đã bị hủy: " + reason);
        refund = refundRepository.save(refund);
        syncLegacyPaymentStateIfPresent(refund.getPaymentTransaction());
        auditRefund(refund, ReservationAuditAction.REFUND,
                "Hủy refund " + refund.getAmount() + " VND: " + reason);
        return PaymentRefundResponse.from(refund);
    }

    /**
     * Returns mandatory overpayment/unaccepted money that is no longer covered
     * by an active or succeeded ledger row.  This is the guard that prevents a
     * CANCELLED refund from silently making checkout appear balanced.
     */
    @Transactional(readOnly = true)
    public long getUncoveredRequiredRefundAmount(Long reservationId) {
        if (reservationId == null) return 0L;
        List<PaymentTransaction> payments = transactionRepository.findByReservationId(reservationId);
        List<PaymentRefund> refunds = refundRepository.findByReservationId(reservationId);
        long uncovered = 0L;
        for (PaymentTransaction payment : payments) {
            long required = value(payment.getRefundRequiredAmount());
            if (required <= 0L) continue;
            long covered = refunds.stream()
                    .filter(refund -> refund.getPaymentTransaction() != null
                            && java.util.Objects.equals(
                            refund.getPaymentTransaction().getId(), payment.getId()))
                    .filter(refund -> REQUIRED_OBLIGATION_SOURCES.contains(refund.getSourceType()))
                    .filter(refund -> RESERVED_STATUSES.contains(refund.getStatus()))
                    .mapToLong(refund -> value(refund.getAmount()))
                    .sum();
            uncovered += Math.max(0L, required - covered);
        }
        return uncovered;
    }

    /**
     * Re-activates cancelled mandatory refund rows (or creates a replacement
     * row if a legacy database has no row at all).  The caller must already hold
     * the reservation lock when this is used during checkout reconciliation.
     */
    @Transactional
    public List<PaymentRefundResponse> requestRequiredRefundReplacement(
            Long reservationId,
            RefundChannel channel,
            String reason,
            String requestedBy) {
        reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        RefundChannel selected = normalizeRefundChannel(channel);
        List<PaymentTransaction> payments = transactionRepository.findByReservationId(reservationId).stream()
                .sorted(Comparator.comparing(PaymentTransaction::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        List<PaymentRefund> refunds = refundRepository.findByReservationId(reservationId);
        List<PaymentRefundResponse> result = new ArrayList<>();
        long remaining = 0L;
        for (PaymentTransaction payment : payments) {
            long required = value(payment.getRefundRequiredAmount());
            if (required <= 0L) continue;
            long covered = refunds.stream()
                    .filter(refund -> refund.getPaymentTransaction() != null
                            && java.util.Objects.equals(
                            refund.getPaymentTransaction().getId(), payment.getId()))
                    .filter(refund -> REQUIRED_OBLIGATION_SOURCES.contains(refund.getSourceType()))
                    .filter(refund -> RESERVED_STATUSES.contains(refund.getStatus()))
                    .mapToLong(refund -> value(refund.getAmount()))
                    .sum();
            long gap = Math.max(0L, required - covered);
            if (gap <= 0L) continue;

            List<PaymentRefund> cancelled = refunds.stream()
                    .filter(refund -> refund.getPaymentTransaction() != null
                            && java.util.Objects.equals(
                            refund.getPaymentTransaction().getId(), payment.getId()))
                    .filter(refund -> REQUIRED_OBLIGATION_SOURCES.contains(refund.getSourceType()))
                    .filter(refund -> refund.getStatus() == RefundStatus.CANCELLED)
                    .sorted(Comparator.comparing(PaymentRefund::getUpdatedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            for (PaymentRefund refund : cancelled) {
                if (gap <= 0L) break;
                long amount = Math.min(gap, value(refund.getAmount()));
                if (amount <= 0L) continue;
                refund.setStatus(selected == RefundChannel.CASH_AT_COUNTER
                        ? RefundStatus.REQUESTED
                        : refund.getRecipient() == null
                        ? RefundStatus.AWAITING_CUSTOMER_INFO
                        : RefundStatus.REQUESTED);
                refund.setChannel(selected);
                refund.setCancelledAtUtc(null);
                refund.setCancelledBy(null);
                refund.setCancellationReason(null);
                refund.setCompletionMethod(null);
                refund.setCompletionProviderEvent(null);
                refund.setManualFallbackAvailableAtUtc(
                        selected == RefundChannel.MANUAL_BANK_TRANSFER
                                && refund.getRecipient() != null
                                ? newManualFallbackAvailableAt() : null);
                refund.setManualFallbackOpenedAtUtc(null);
                refund.setManualFallbackOpenedBy(null);
                refund.setManualFallbackReason(null);
                refund.setMessage("Đã tạo lại nghĩa vụ hoàn sau khi refund trước bị hủy");
                refundRepository.save(refund);
                syncLegacyPaymentStateIfPresent(payment);
                auditRefund(refund, ReservationAuditAction.REFUND,
                        "Tạo lại nghĩa vụ hoàn bắt buộc " + amount + " VND");
                result.add(PaymentRefundResponse.from(refund));
                gap -= amount;
            }
            if (gap > 0L) {
                PaymentRefund created = allocate(
                        List.of(payment), gap, selected, reason, requestedBy,
                        RefundSourceType.CHECKOUT_OVERPAYMENT,
                        "required-replacement:" + payment.getId(), null)
                        .stream().findFirst().orElse(null);
                if (created != null) result.add(PaymentRefundResponse.from(created));
                gap = 0L;
            }
            remaining += gap;
        }
        if (remaining > 0L) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    String.format("Không thể tạo lại nghĩa vụ hoàn còn thiếu %,d VND", remaining));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ManualRefundDetailsResponse getManualDetails(String refundId) {
        PaymentRefund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy yêu cầu hoàn tiền: " + refundId));
        ensureManualBankTransfer(refund);
        RefundRecipient recipient = requireRecipient(refund);
        Reservation refundReservation = refund.getReservation() != null
                ? refund.getReservation()
                : refund.getPaymentTransaction() != null
                ? refund.getPaymentTransaction().getReservation() : null;
        return ManualRefundDetailsResponse.builder()
                .refundId(refund.getId())
                .reservationId(refundReservation != null ? refundReservation.getId() : null)
                .reservationCode(refundReservation != null ? refundReservation.getReservationCode() : null)
                .amount(refund.getAmount())
                .expectedAmount(refund.getRequestedAmount() != null
                        ? refund.getRequestedAmount() : refund.getAmount())
                .refundCode(refund.getRefundCode())
                .status(refund.getStatus() != null ? refund.getStatus().name() : null)
                .canonicalStatus(refund.getStatus() != null
                        ? refund.getStatus().canonicalName() : null)
                .recipientId(recipient.getId())
                .recipientVersion(recipient.getVersion())
                .recipientStatus(recipient.getStatus())
                .bankCode(VietQrBankCatalog.canonicalCode(recipient.getBankCode(), recipient.getBankName()))
                .bankName(recipient.getBankName())
                .accountNumber(refundDataCipher.decrypt(recipient.getAccountNumberCiphertext()))
                .accountHolderName(refundDataCipher.decrypt(recipient.getAccountHolderCiphertext()))
                .transferContent(buildRefundTransferContent(refund, recipient))
                .refundQrCodeUrl(buildRefundQrCodeUrl(refund, recipient))
                .proofAssetId(refund.getProofAsset() != null ? refund.getProofAsset().getId() : null)
                .proofImageUrl(refund.getProofAsset() != null ? refund.getProofAsset().getUrl() : null)
                .proofContentType(refund.getProofAsset() != null
                        ? refund.getProofAsset().getContentType() : null)
                .awaitingBankConfirmation(BANK_CONFIRMATION_PENDING_STATUSES
                        .contains(refund.getStatus()))
                .fallbackAvailableAtUtc(refund.getManualFallbackAvailableAtUtc())
                .fallbackAvailable(isManualFallbackAvailable(refund))
                .fallbackOpened(refund.getManualFallbackOpenedAtUtc() != null)
                .fallbackOpenedBy(refund.getManualFallbackOpenedBy())
                .fallbackReason(refund.getManualFallbackReason())
                .completionMethod(refund.getCompletionMethod() != null
                        ? refund.getCompletionMethod().name() : null)
                .bankReferenceCode(refund.getCompletionProviderEvent() != null
                        ? refund.getCompletionProviderEvent().getBankReferenceCode() : null)
                .build();
    }

    /**
     * Links an uploaded receipt to exactly one pending bank refund. Uploading a
     * receipt does not complete the refund or finalize the reservation.
     */
    @Transactional
    public PaymentRefundResponse attachManualTransferProof(
            String refundId,
            Long proofAssetId,
            User currentUser) {
        lockRefundAggregate(refundId);
        PaymentRefund refund = refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy yêu cầu hoàn tiền: " + refundId));
        ensureManualBankTransfer(refund);
        if (!BANK_CONFIRMATION_PENDING_STATUSES.contains(refund.getStatus())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ có thể tải minh chứng cho khoản hoàn đang chờ chuyển khoản");
        }
        if (refund.getProofAsset() != null) {
            if (java.util.Objects.equals(refund.getProofAsset().getId(), proofAssetId)) {
                return PaymentRefundResponse.from(refund);
            }
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE,
                    "Khoản hoàn đã có minh chứng; hãy tải lại trang trước khi tiếp tục");
        }
        var proofAsset = mediaAssetService.claimFinancialEvidence(
                proofAssetId, UploadFolder.REFUND_PROOFS, currentUser);
        refund.setProofAsset(proofAsset);
        refund.setMessage("Đã lưu minh chứng dự phòng; vẫn chờ SePay xác nhận tự động");
        refund = refundRepository.save(refund);
        auditRefund(refund, ReservationAuditAction.REFUND,
                "Gắn minh chứng media #" + proofAsset.getId()
                        + " vào refund " + refund.getId() + "; chưa hoàn tất khoản hoàn");
        return PaymentRefundResponse.from(refund);
    }

    @Transactional
    public PaymentRefundResponse completeCashAtCounter(
            String refundId,
            CashRefundCompleteRequest request,
            User currentUser) {
        lockRefundAggregate(refundId);
        PaymentRefund refund = refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy yêu cầu hoàn tiền: " + refundId));
        if (refund.getChannel() != RefundChannel.CASH_AT_COUNTER) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Yêu cầu hoàn này không dùng tiền mặt tại quầy");
        }
        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            finalizeReservationAfterCancellationRefund(refund);
            return PaymentRefundResponse.from(refund);
        }
        if (refund.getStatus() != RefundStatus.REQUESTED || !request.isConfirmed()) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Khoản hoàn tiền mặt chưa được xác nhận hợp lệ");
        }

        String operator = currentUser != null && hasText(currentUser.getUsername())
                ? currentUser.getUsername() : "hotel_system";
        LocalDateTime refundedAt = request.getRefundedAt() != null
                ? request.getRefundedAt() : LocalDateTime.now(HOTEL_ZONE);
        if (refundedAt.isAfter(LocalDateTime.now(HOTEL_ZONE))) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Thời điểm giao tiền mặt không được nằm trong tương lai");
        }

        refund.setManualTransferredBy(operator);
        refund.setManualTransferredAt(refundedAt);
        refund.setManualTransferredAtUtc(refundedAt.atZone(HOTEL_ZONE).toInstant());
        refund.setResponseCode("CASH_CONFIRMED");
        refund.setTransactionStatus("00");
        refund = completeRefund(
                refund,
                RefundCompletionMethod.CASH_HANDOVER,
                "Staff/Admin đã xác nhận hoàn tiền mặt tại quầy",
                "Xác nhận đã giao tiền mặt " + refund.getAmount() + " VND trực tiếp cho khách");
        return PaymentRefundResponse.from(refund);
    }

    private String buildRefundQrCodeUrl(PaymentRefund refund, RefundRecipient recipient) {
        String holder = VietQrBankCatalog.normalizeHolder(
                refundDataCipher.decrypt(recipient.getAccountHolderCiphertext()));
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(sePayConfig.getQrBaseUrl())
                .queryParam("acc", refundDataCipher.decrypt(recipient.getAccountNumberCiphertext()))
                .queryParam("bank", VietQrBankCatalog.canonicalCode(recipient.getBankCode(), recipient.getBankName()))
                .queryParam("amount", refund.getAmount())
                .queryParam("des", buildRefundTransferContent(refund, recipient))
                .queryParam("template", safeQrTemplate(sePayConfig.getQrTemplate()))
                .queryParam("showinfo", true)
                .queryParam("fullacc", true);
        if (hasText(holder)) builder.queryParam("holder", holder);
        if (hasText(sePayConfig.getStoreName())) {
            builder.queryParam("store", VietQrBankCatalog.normalizeHolder(sePayConfig.getStoreName()));
        }
        return builder.build().encode().toUriString();
    }

    private String buildRefundTransferContent(PaymentRefund refund, RefundRecipient recipient) {
        String source = hasText(refund.getRefundCode())
                ? refund.getRefundCode() : refund.getId();
        String uniqueCode = source == null ? "REFUND" : source
                .replaceAll("[^A-Za-z0-9]", "")
                .toUpperCase(java.util.Locale.ROOT);
        String refundContent = "HOAN " + uniqueCode;
        return "ICB".equals(VietQrBankCatalog.canonicalCode(recipient.getBankCode(), recipient.getBankName()))
                ? "SEVQR " + refundContent : refundContent;
    }

    private String safeQrTemplate(String value) {
        if (!hasText(value)) return "compact";
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return List.of("compact", "qronly", "standee").contains(normalized)
                ? normalized : "compact";
    }

    private RefundChannel normalizeRefundChannel(RefundChannel channel) {
        RefundChannel normalized = channel != null ? channel : RefundChannel.MANUAL_BANK_TRANSFER;
        if (!List.of(RefundChannel.MANUAL_BANK_TRANSFER, RefundChannel.CASH_AT_COUNTER)
                .contains(normalized)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ hỗ trợ hoàn tiền mặt tại quầy hoặc QR/chuyển khoản ngân hàng");
        }
        return normalized;
    }

    @Transactional
    public PaymentRefundResponse completeFromSePayOutgoing(
            String refundId,
            PaymentProviderEvent event,
            long transferredAmount) {
        lockRefundAggregate(refundId);
        PaymentRefund refund = refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy yêu cầu hoàn tiền: " + refundId));
        ensureManualBankTransfer(refund);
        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            return sameEvent(refund.getCompletionProviderEvent(), event)
                    ? PaymentRefundResponse.from(refund) : null;
        }
        if (!BANK_CONFIRMATION_PENDING_STATUSES.contains(refund.getStatus())
                || refund.getRecipient() == null
                || refund.getCompletionProviderEvent() != null) {
            return null;
        }
        long expectedAmount = refund.getRequestedAmount() != null
                ? refund.getRequestedAmount() : refund.getAmount();
        if (transferredAmount != expectedAmount
                || !java.util.Objects.equals(refund.getRefundCode(), event.getPaymentCode())) {
            return null;
        }

        Instant providerTime = event.getProviderOccurredAtUtc();
        refund.setCompletionProviderEvent(event);
        refund.setProviderRefundTxnId(hasText(event.getProviderTxnId())
                ? event.getProviderTxnId() : event.getProviderEventId());
        refund.setManualTransferredBy("sepay_webhook");
        refund.setManualTransferredAtUtc(providerTime);
        if (providerTime != null) {
            refund.setManualTransferredAt(LocalDateTime.ofInstant(providerTime, HOTEL_ZONE));
        }
        refund.setResponseCode("SEPAY_OUT_MATCHED");
        refund.setTransactionStatus("00");
        verifyRecipient(refund.getRecipient(), "sepay_webhook");
        refund = completeRefund(
                refund,
                RefundCompletionMethod.SEPAY_WEBHOOK,
                "SePay đã xác nhận tự động giao dịch tiền ra",
                "SePay outgoing event " + event.getProviderEventId()
                        + " khớp refund_code và " + transferredAmount + " VND");
        return PaymentRefundResponse.from(refund);
    }

    @Transactional
    public PaymentRefundResponse openManualFallback(
            String refundId,
            ManualRefundFallbackOpenRequest request,
            User currentUser) {
        if (currentUser == null || currentUser.getType() == null
                || !"ADMIN".equals(currentUser.getType().name())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ Admin được mở fallback thủ công trước thời hạn");
        }
        lockRefundAggregate(refundId);
        PaymentRefund refund = refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy yêu cầu hoàn tiền: " + refundId));
        ensureManualBankTransfer(refund);
        requireRecipient(refund);
        if (!BANK_CONFIRMATION_PENDING_STATUSES.contains(refund.getStatus())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ refund đang chờ SePay mới được mở fallback thủ công");
        }
        if (refund.getManualFallbackOpenedAtUtc() != null) {
            return PaymentRefundResponse.from(refund);
        }
        String operator = currentUser.getUsername();
        refund.setManualFallbackOpenedAtUtc(Instant.now());
        refund.setManualFallbackOpenedBy(hasText(operator) ? operator : "admin");
        refund.setManualFallbackReason(request.getReason().trim());
        refund.setMessage("Admin đã mở xác nhận thủ công; refund vẫn chờ hoàn tất");
        refund = refundRepository.save(refund);
        auditRefund(refund, ReservationAuditAction.REFUND,
                "Mở fallback xác nhận thủ công: " + request.getReason().trim());
        return PaymentRefundResponse.from(refund);
    }

    @Transactional
    public PaymentRefundResponse completeManualTransfer(
            String refundId,
            ManualRefundCompleteRequest request,
            User currentUser) {
        lockRefundAggregate(refundId);
        PaymentRefund refund = refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy yêu cầu hoàn tiền: " + refundId));
        ensureManualBankTransfer(refund);
        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            finalizeReservationAfterCancellationRefund(refund);
            return PaymentRefundResponse.from(refund);
        }
        if (!BANK_CONFIRMATION_PENDING_STATUSES.contains(refund.getStatus())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Khoản hoàn không ở trạng thái chờ xác nhận ngân hàng");
        }
        if (!isManualFallbackAvailable(refund)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chưa hết thời gian chờ SePay; Admin cần mở fallback nếu phải xử lý sớm");
        }
        if (!hasText(request.getFallbackReason())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Lý do xác nhận thủ công không được để trống");
        }
        RefundRecipient recipient = requireRecipient(refund);
        if (!recipient.getId().equals(request.getRecipientId())
                || !java.util.Objects.equals(recipient.getVersion(), request.getRecipientVersion())) {
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE,
                    "Thông tin tài khoản nhận hoàn đã thay đổi; vui lòng tải lại trước khi xác nhận");
        }
        LocalDateTime transferredAt = request.getTransferredAt() != null
                ? request.getTransferredAt() : LocalDateTime.now(HOTEL_ZONE);
        if (transferredAt.isAfter(LocalDateTime.now(HOTEL_ZONE))) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Thời điểm chuyển khoản không được nằm trong tương lai");
        }
        String operator = currentUser != null && hasText(currentUser.getUsername())
                ? currentUser.getUsername() : "hotel_system";

        var proofAsset = refund.getProofAsset();
        if (proofAsset == null && request.getProofAssetId() != null) {
            proofAsset = mediaAssetService.claimFinancialEvidence(
                    request.getProofAssetId(), UploadFolder.REFUND_PROOFS, currentUser);
            refund.setProofAsset(proofAsset);
        } else if (proofAsset != null && request.getProofAssetId() != null
                && !java.util.Objects.equals(proofAsset.getId(), request.getProofAssetId())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Minh chứng xác nhận không khớp với refund hiện tại");
        }

        if (refund.getManualFallbackOpenedAtUtc() == null) {
            refund.setManualFallbackOpenedAtUtc(Instant.now());
            refund.setManualFallbackOpenedBy(operator);
        }
        refund.setManualFallbackReason(request.getFallbackReason().trim());
        refund.setManualTransferredBy(operator);
        refund.setManualTransferredAt(transferredAt);
        refund.setManualTransferredAtUtc(transferredAt.atZone(HOTEL_ZONE).toInstant());
        refund.setResponseCode("MANUAL_FALLBACK");
        refund.setTransactionStatus("00");
        verifyRecipient(recipient, operator);
        String proofNote = proofAsset != null
                ? "; minh chứng media #" + proofAsset.getId() : "; không đính kèm minh chứng";
        refund = completeRefund(
                refund,
                RefundCompletionMethod.MANUAL_FALLBACK,
                "Staff/Admin đã xác nhận hoàn QR bằng fallback thủ công",
                "Fallback thủ công " + refund.getAmount() + " VND; lý do: "
                        + request.getFallbackReason().trim() + proofNote);
        return PaymentRefundResponse.from(refund);
    }

    @Transactional(readOnly = true)
    public List<PaymentRefundResponse> getOperationalQueue() {
        return refundRepository.findOperationalQueue(
                        EnumSet.of(RefundStatus.AWAITING_CUSTOMER_INFO,
                                RefundStatus.READY_FOR_MANUAL_TRANSFER,
                                RefundStatus.REQUESTED, RefundStatus.PROCESSING,
                                RefundStatus.FAILED, RefundStatus.MANUAL_REVIEW))
                .stream().map(PaymentRefundResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public PaymentRefundResponse getById(String refundId) {
        return refundRepository.findById(refundId)
                .map(PaymentRefundResponse::from)
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy yêu cầu hoàn tiền"));
    }

    @Transactional(readOnly = true)
    public ReservationResponse applyReservationRefundSummary(ReservationResponse response) {
        if (response == null || response.getId() == null) return response;
        List<PaymentRefund> refunds = refundRepository.findByReservationId(response.getId());
        if (refunds.isEmpty()) {
            boolean mayNeedRefundDestination = response.getStatus() == ReservationStatus.CANCELLATION_PENDING
                    || (response.getStatus() == ReservationStatus.CHECKED_IN
                    && response.getRefundableAmount() != null
                    && response.getRefundableAmount().signum() > 0);
            if (!mayNeedRefundDestination) {
                return response;
            }
            List<PaymentTransaction> paid = transactionRepository.findByReservationId(response.getId()).stream()
                    .filter(payment -> List.of(PaymentStatus.SUCCESS,
                                    PaymentStatus.REFUND_PENDING, PaymentStatus.REFUNDED)
                            .contains(payment.getStatus()))
                    .toList();
            if (paid.isEmpty()) {
                response.setRefundRoute(RefundRoute.NONE);
                response.setRefundDestinationStatus(RefundDestinationStatus.NOT_REQUIRED);
                return response;
            }
            // Kênh hoàn mới không phụ thuộc kênh thu tiền gốc. Khách có thể khai báo
            // tài khoản trước để Staff/Admin chọn chuyển khoản QR khi duyệt hủy/đối soát.
            response.setRefundRoute(RefundRoute.MANUAL_BANK_TRANSFER);
            RefundRecipient preSubmitted = recipientRepository
                    .findFirstByReservationIdAndStatusInOrderByCreatedAtDesc(
                            response.getId(), EnumSet.of(RefundRecipientStatus.SUBMITTED,
                                    RefundRecipientStatus.VERIFIED))
                    .orElse(null);
            response.setRefundDestinationStatus(preSubmitted == null
                    ? RefundDestinationStatus.REQUIRED
                    : preSubmitted.getStatus() == RefundRecipientStatus.VERIFIED
                    ? RefundDestinationStatus.VERIFIED
                    : RefundDestinationStatus.SUBMITTED);
            response.setRefundBankSummary(preSubmitted != null
                    ? preSubmitted.getBankName() + " ****" + preSubmitted.getAccountNumberLast4()
                    : null);
            return response;
        }
        response.setRefunds(refunds.stream()
                .sorted(Comparator.comparing(PaymentRefund::getRequestedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(ReservationRefundResponse::from)
                .toList());
        boolean hasVnPay = refunds.stream().anyMatch(
                refund -> refund.getChannel() == RefundChannel.VNPAY_ORIGINAL);
        boolean hasManualBank = refunds.stream().anyMatch(
                refund -> refund.getChannel() == RefundChannel.MANUAL_BANK_TRANSFER);
        boolean hasCash = refunds.stream().anyMatch(
                refund -> refund.getChannel() == RefundChannel.CASH_AT_COUNTER);
        response.setRefundRoute(hasVnPay && hasManualBank
                ? RefundRoute.MIXED
                : hasManualBank
                ? RefundRoute.MANUAL_BANK_TRANSFER
                : hasCash
                ? RefundRoute.CASH_AT_COUNTER
                : hasVnPay
                ? RefundRoute.VNPAY_ORIGINAL
                : RefundRoute.NONE);

        PaymentRefund manualSummary = refunds.stream()
                .filter(refund -> refund.getChannel() == RefundChannel.MANUAL_BANK_TRANSFER
                        && List.of(RefundStatus.AWAITING_CUSTOMER_INFO,
                        RefundStatus.READY_FOR_MANUAL_TRANSFER).contains(refund.getStatus()))
                .findFirst()
                .orElseGet(() -> refunds.stream()
                        .filter(refund -> refund.getChannel() == RefundChannel.MANUAL_BANK_TRANSFER)
                        .max(Comparator.comparing(PaymentRefund::getUpdatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .orElse(null));
        if (manualSummary == null) {
            response.setRefundDestinationStatus(RefundDestinationStatus.NOT_REQUIRED);
            return response;
        }
        RefundRecipient recipient = manualSummary.getRecipient();
        response.setRefundDestinationStatus(recipient == null
                ? RefundDestinationStatus.REQUIRED
                : recipient.getStatus() == RefundRecipientStatus.VERIFIED
                ? RefundDestinationStatus.VERIFIED
                : RefundDestinationStatus.SUBMITTED);
        response.setRefundBankSummary(recipient != null
                ? recipient.getBankName() + " ****" + recipient.getAccountNumberLast4()
                : null);
        return response;
    }

    @Transactional(readOnly = true)
    public RefundChannel latestChannelForPayment(String paymentTransactionId) {
        return refundRepository.findByPaymentTransactionId(paymentTransactionId).stream()
                .max(Comparator.comparing(PaymentRefund::getUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(PaymentRefund::getChannel)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public PaymentRefundSummary getPaymentRefundSummary(String paymentTransactionId) {
        List<PaymentRefund> refunds = refundRepository.findByPaymentTransactionId(paymentTransactionId);
        if (refunds.isEmpty()) return PaymentRefundSummary.empty();

        long completedAmount = refunds.stream()
                .filter(refund -> refund.getStatus() == RefundStatus.SUCCEEDED)
                .mapToLong(refund -> value(refund.getAmount()))
                .sum();
        long outstandingAmount = refunds.stream()
                .filter(refund -> RESERVED_STATUSES.contains(refund.getStatus()))
                .filter(refund -> refund.getStatus() != RefundStatus.SUCCEEDED)
                .mapToLong(refund -> value(refund.getAmount()))
                .sum();
        PaymentRefund latest = refunds.stream()
                .max(Comparator.comparing(PaymentRefund::getUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        return new PaymentRefundSummary(
                completedAmount,
                outstandingAmount,
                latest != null ? latest.getChannel() : null,
                latest != null ? latest.getStatus() : null);
    }

    @Transactional(readOnly = true)
    public long getNetPaidAmount(Long reservationId) {
        List<PaymentTransaction> payments = transactionRepository.findByReservationId(reservationId).stream()
                .filter(payment -> List.of(PaymentStatus.SUCCESS, PaymentStatus.REFUND_PENDING,
                        PaymentStatus.REFUNDED).contains(payment.getStatus()))
                .toList();
        List<PaymentRefund> refunds = refundRepository.findByReservationId(reservationId);
        long gross = payments.stream()
                .mapToLong(payment -> payment.getAcceptedAmount() != null
                        ? payment.getAcceptedAmount() : value(payment.getAmount()))
                .sum();
        long effectiveRefunds = refunds.stream()
                .filter(refund -> NET_DEDUCTED_STATUSES.contains(refund.getStatus()))
                .filter(refund -> refund.getSourceType() == null
                        || !List.of(
                        RefundSourceType.UNACCEPTED_PAYMENT,
                        RefundSourceType.ADDITIONAL_TRANSFER,
                        RefundSourceType.CHECKOUT_OVERPAYMENT).contains(refund.getSourceType()))
                .mapToLong(refund -> refund.getAmount() != null ? refund.getAmount() : 0L)
                .sum();
        // Fallback cho dữ liệu REFUNDED rất cũ chưa có payment_refunds. Với dữ
        // liệu mới, phần chênh này bằng 0 nên không bị trừ hai lần.
        long legacyCompletedGap = payments.stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.REFUNDED)
                .mapToLong(payment -> {
                    long recorded = refunds.stream()
                            .filter(refund -> refund.getStatus() == RefundStatus.SUCCEEDED)
                            .filter(refund -> refund.getPaymentTransaction() != null
                                    && java.util.Objects.equals(
                                    refund.getPaymentTransaction().getId(), payment.getId()))
                            .mapToLong(refund -> value(refund.getAmount()))
                            .sum();
                    return Math.max(0L, value(payment.getRefundAmount()) - recorded);
                })
                .sum();
        return Math.max(0L, gross - effectiveRefunds - legacyCompletedGap);
    }

    /**
     * Tổng số tiền đã được giữ chỗ trong ledger hoàn nhưng nhà cung cấp/staff
     * chưa xác nhận hoàn xong. Dùng để thao tác lặp không tạo refund thứ hai.
     */
    @Transactional(readOnly = true)
    public long getOutstandingReservedRefundAmount(Long reservationId) {
        return refundRepository.findByReservationId(reservationId).stream()
                .filter(refund -> RESERVED_STATUSES.contains(refund.getStatus()))
                .filter(refund -> refund.getStatus() != RefundStatus.SUCCEEDED)
                .mapToLong(refund -> value(refund.getAmount()))
                .sum();
    }

    private void applyProviderResult(PaymentRefund refund, VNPayProviderResult result, boolean queryResult) {
        refund.setResponseCode(result.responseCode());
        refund.setTransactionStatus(result.transactionStatus());
        refund.setProviderRefundTxnId(result.providerTransactionNo());
        refund.setMessage(result.message());

        if ("00".equals(result.responseCode()) && "00".equals(result.transactionStatus())) {
            refund.setStatus(RefundStatus.SUCCEEDED);
            refund.setCompletionMethod(RefundCompletionMethod.PROVIDER_API);
            LocalDateTime completedAt = LocalDateTime.now(HOTEL_ZONE);
            refund.setCompletedAt(completedAt);
            refund.setActualRefundAmount(refund.getAmount());
            refund.setCompletedAtUtc(completedAt.atZone(HOTEL_ZONE).toInstant());
            return;
        }
        if ("94".equals(result.responseCode())
                || ("00".equals(result.responseCode())
                && (result.transactionStatus() == null
                || List.of("01", "05", "06").contains(result.transactionStatus())))) {
            refund.setStatus(RefundStatus.PROCESSING);
            return;
        }
        if (queryResult && "00".equals(result.responseCode())
                && !List.of("02", "03").contains(result.transactionType())) {
            refund.setStatus(RefundStatus.PROCESSING);
            return;
        }
        refund.setStatus(RefundStatus.FAILED);
    }

    void syncLegacyPaymentState(PaymentTransaction payment) {
        if (payment == null || payment.getId() == null) return;
        List<PaymentRefund> refunds = refundRepository.findByPaymentTransactionId(payment.getId());
        long tracked = refunds.stream()
                .filter(refund -> RESERVED_STATUSES.contains(refund.getStatus()))
                .mapToLong(refund -> value(refund.getAmount()))
                .sum();
        boolean pending = refunds.stream().anyMatch(refund ->
                refund.getStatus() == RefundStatus.AWAITING_CUSTOMER_INFO
                        || refund.getStatus() == RefundStatus.READY_FOR_MANUAL_TRANSFER
                        || refund.getStatus() == RefundStatus.REQUESTED
                        || refund.getStatus() == RefundStatus.PROCESSING
                        || refund.getStatus() == RefundStatus.FAILED
                        || refund.getStatus() == RefundStatus.MANUAL_REVIEW);
        long succeededAmount = refunds.stream()
                .filter(refund -> refund.getStatus() == RefundStatus.SUCCEEDED)
                .mapToLong(refund -> value(refund.getAmount()))
                .sum();
        boolean fullyRefunded = value(payment.getAmount()) > 0L
                && succeededAmount >= value(payment.getAmount());

        payment.setRefundAmount(tracked > 0L ? tracked : null);
        refunds.stream().max(Comparator.comparing(PaymentRefund::getUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .ifPresent(latest -> {
                    payment.setRefundProvider(latest.getProvider());
                    payment.setRefundTxnId(latest.getProviderRefundTxnId());
                });
        // New allocation rows keep the canonical payment status SUCCESS even
        // while an unaccepted/overpayment refund is pending. Legacy rows with
        // NULL allocation columns retain the old overlay for compatibility.
        if (payment.getAcceptedAmount() != null && payment.getRefundRequiredAmount() != null) {
            payment.setRefundCompletedAt(null);
        } else if (pending) {
            payment.setStatus(PaymentStatus.REFUND_PENDING);
            payment.setRefundCompletedAt(null);
        } else if (fullyRefunded) {
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.setRefundCompletedAt(refunds.stream()
                    .filter(refund -> refund.getStatus() == RefundStatus.SUCCEEDED)
                    .map(PaymentRefund::getCompletedAt)
                    .filter(java.util.Objects::nonNull)
                    .max(LocalDateTime::compareTo).orElse(LocalDateTime.now()));
        } else {
            // Hoàn thành một phần không biến toàn bộ payment thành
            // REFUNDED. Phần tiền còn lại vẫn là khoản thanh toán hợp lệ.
            payment.setStatus(PaymentStatus.SUCCESS);
            payment.setRefundCompletedAt(null);
        }
        transactionRepository.save(payment);
    }

    private String originalTransactionDate(PaymentTransaction payment) {
        return hasText(payment.getProviderCreateDate()) ? payment.getProviderCreateDate() : null;
    }

    private long legacyCompletedGap(PaymentTransaction payment) {
        if (payment.getStatus() != PaymentStatus.REFUNDED || value(payment.getRefundAmount()) == 0L) {
            return 0L;
        }
        long recorded = refundRepository.findByPaymentTransactionId(payment.getId()).stream()
                .filter(refund -> refund.getStatus() == RefundStatus.SUCCEEDED)
                .mapToLong(refund -> value(refund.getAmount()))
                .sum();
        return Math.max(0L, value(payment.getRefundAmount()) - recorded);
    }

    private long value(Long value) {
        return value != null ? value : 0L;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void ensureManualBankTransfer(PaymentRefund refund) {
        if (refund.getChannel() != RefundChannel.MANUAL_BANK_TRANSFER) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Yêu cầu này không phải hoàn chuyển khoản thủ công");
        }
    }

    private RefundRecipient requireRecipient(PaymentRefund refund) {
        if (refund.getRecipient() == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Khách hàng chưa cung cấp thông tin ngân hàng nhận hoàn tiền");
        }
        RefundRecipient recipient = refund.getRecipient();
        Long refundReservationId = refund.getPaymentTransaction() != null
                && refund.getPaymentTransaction().getReservation() != null
                ? refund.getPaymentTransaction().getReservation().getId() : null;
        if (refund.getReservation() != null) {
            refundReservationId = refund.getReservation().getId();
        }
        Long recipientReservationId = recipient.getReservation() != null
                ? recipient.getReservation().getId() : null;
        boolean directRefundMatch = recipient.getPaymentRefund() != null
                && java.util.Objects.equals(recipient.getPaymentRefund().getId(), refund.getId());
        if (refundReservationId != null
                && !java.util.Objects.equals(refundReservationId, recipientReservationId)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Thông tin tài khoản nhận hoàn không thuộc reservation này");
        }
        if (refundReservationId == null && !directRefundMatch) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Thông tin tài khoản chưa liên kết trực tiếp với refund unmatched");
        }
        return recipient;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.substring(0, Math.min(300, message.length()));
    }

    private String appendRequestHistory(String history, String requestId) {
        if (!hasText(requestId)) return history;
        String updated = hasText(history) ? history + "," + requestId : requestId;
        return updated.length() <= 1000
                ? updated
                : updated.substring(updated.length() - 1000);
    }

    private <T> T requiresNew(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    private record SubmissionClaim(
            PaymentRefund refund,
            PaymentRefundResponse immediateResponse) {
    }

    private record RetryOutcome(
            PaymentRefundResponse response,
            boolean submitToProvider) {
    }

    private void syncLegacyPaymentStateIfPresent(PaymentTransaction payment) {
        if (payment != null) syncLegacyPaymentState(payment);
    }

    private boolean isManualFallbackAvailable(PaymentRefund refund) {
        if (refund == null) return false;
        if (refund.getManualFallbackOpenedAtUtc() != null) return true;
        if (refund.getStatus() == RefundStatus.READY_FOR_MANUAL_TRANSFER
                && refund.getManualFallbackAvailableAtUtc() == null) {
            // Compatibility for a row created before V29.
            return true;
        }
        return refund.getManualFallbackAvailableAtUtc() != null
                && !Instant.now().isBefore(refund.getManualFallbackAvailableAtUtc());
    }

    private boolean sameEvent(PaymentProviderEvent left, PaymentProviderEvent right) {
        return left != null && right != null && left.getId() != null
                && left.getId().equals(right.getId());
    }

    private void verifyRecipient(RefundRecipient recipient, String operator) {
        if (recipient == null) return;
        recipient.setStatus(RefundRecipientStatus.VERIFIED);
        recipient.setVerifiedBy(operator);
        recipient.setVerifiedAt(LocalDateTime.now(HOTEL_ZONE));
        recipientRepository.save(recipient);
    }

    /**
     * Điểm chốt duy nhất cho QR tự động, QR fallback và tiền mặt. Việc hoàn
     * tất ledger và chuyển reservation sang trạng thái cuối cùng cùng nằm trong
     * transaction hiện tại.
     */
    private PaymentRefund completeRefund(
            PaymentRefund refund,
            RefundCompletionMethod method,
            String message,
            String auditDetails) {
        long expected = refund.getRequestedAmount() != null
                ? refund.getRequestedAmount() : value(refund.getAmount());
        if (expected <= 0L || value(refund.getAmount()) != expected) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Số tiền hoàn không khớp toàn bộ expected_amount của refund");
        }
        Instant completedAtUtc = Instant.now();
        refund.setCompletionMethod(method);
        refund.setStatus(RefundStatus.SUCCEEDED);
        refund.setActualRefundAmount(expected);
        refund.setCompletedAtUtc(completedAtUtc);
        refund.setCompletedAt(LocalDateTime.ofInstant(completedAtUtc, HOTEL_ZONE));
        refund.setMessage(message);
        refund = refundRepository.save(refund);
        syncLegacyPaymentStateIfPresent(refund.getPaymentTransaction());
        auditRefund(refund, ReservationAuditAction.REFUND, auditDetails);
        finalizeReservationAfterCancellationRefund(refund);
        if (refund.getReservation() != null) {
            eventPublisher.publishEvent(new CheckoutReconciliationChangedEvent(
                    refund.getReservation().getId(),
                    "REFUND_COMPLETED_" + method.name()));
        }
        return refund;
    }

    private void lockRefundAggregate(String refundId) {
        // Resolve scalar IDs first. Loading the refund entity before acquiring
        // the aggregate lock leaves a stale managed @Version instance in the
        // persistence context and can make a concurrent idempotent replay fail
        // with ObjectOptimisticLockingFailureException.
        Long reservationId = refundRepository.findAggregateReservationId(refundId)
                .orElse(null);
        if (reservationId != null) {
            reservationRepository.findByIdForUpdate(reservationId)
                    .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        } else {
            String paymentId = refundRepository.findAggregatePaymentId(refundId)
                    .orElse(null);
            if (paymentId != null) {
                transactionRepository.findByIdForUpdate(paymentId)
                        .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                                "Không tìm thấy payment của refund"));
            }
        }
    }

    /**
     * Single source of truth for the final cancellation transition. Only after
     * every active refund obligation for the reservation is completed do we
     * release inventory and publish the terminal reservation status. The active
     * SePay deployment uses manual QR or cash here; legacy provider refunds also
     * enter this compatibility point after reaching SUCCEEDED.
     * This method joins the refund transaction so ledger completion and
     * reservation finalization commit atomically.
     */
    private void finalizeReservationAfterCancellationRefund(PaymentRefund completedRefund) {
        Reservation reservation = completedRefund.getReservation() != null
                ? completedRefund.getReservation()
                : completedRefund.getPaymentTransaction() != null
                ? completedRefund.getPaymentTransaction().getReservation() : null;
        if (reservation == null || reservation.getId() == null
                || !ReservationCancellationReasonCode.isRefundPending(
                reservation.getCancellationReasonCode())) {
            return;
        }

        List<PaymentRefund> refunds = refundRepository.findByReservationId(reservation.getId());
        String cancellationPrefix = cancellationSourcePrefix(reservation.getId());
        boolean hasCompletedCancellationRefund = refunds.stream()
                .anyMatch(refund -> refund.getStatus() == RefundStatus.SUCCEEDED
                        && hasText(refund.getSourceKey())
                        && refund.getSourceKey().startsWith(cancellationPrefix));
        boolean hasOutstandingRefund = refunds.stream()
                .anyMatch(refund -> RESERVED_STATUSES.contains(refund.getStatus())
                        && refund.getStatus() != RefundStatus.SUCCEEDED);
        if (!hasCompletedCancellationRefund || hasOutstandingRefund) {
            return;
        }
        if (!List.of(ReservationStatus.DRAFT, ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLATION_PENDING).contains(reservation.getStatus())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Trạng thái reservation đã thay đổi; không thể chốt hủy sau hoàn tiền");
        }

        roomHoldRepository.findByReservationIdForUpdate(reservation.getId()).forEach(hold -> {
            if (List.of(HoldStatus.ACTIVE, HoldStatus.CONVERTED).contains(hold.getStatus())) {
                hold.setStatus(HoldStatus.RELEASED);
                roomHoldRepository.save(hold);
            }
        });
        String finalReasonCode = ReservationCancellationReasonCode.finalReasonCode(
                reservation.getCancellationReasonCode());
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setStatusBeforeCancellation(null);
        reservation.setCancellationReasonCode(finalReasonCode);
        reservationRepository.save(reservation);
        String completionEvidence = completedRefund.getChannel() == RefundChannel.MANUAL_BANK_TRANSFER
                && completedRefund.getProofAsset() != null
                ? ", minh chứng media #" + completedRefund.getProofAsset().getId()
                : completedRefund.getChannel() == RefundChannel.CASH_AT_COUNTER
                ? ", staff xác nhận đã giao tiền mặt"
                : hasText(completedRefund.getProviderRefundTxnId())
                ? ", provider transaction " + completedRefund.getProviderRefundTxnId()
                : "";
        reservationAuditService.record(reservation, ReservationAuditAction.CANCEL,
                "Chốt hủy sau khi refund " + completedRefund.getChannel()
                        + " đã hoàn tất; refund " + completedRefund.getId()
                        + completionEvidence);
    }

    private void auditRefund(
            PaymentRefund refund,
            ReservationAuditAction action,
            String details) {
        Reservation reservation = refund.getReservation() != null
                ? refund.getReservation()
                : refund.getPaymentTransaction() != null
                ? refund.getPaymentTransaction().getReservation() : null;
        if (reservation != null) {
            // Keep the reservation link so its detail timeline remains complete,
            // but target the refund itself so the global ADMIN audit screen can
            // filter and investigate one refund without parsing free-form text.
            reservationAuditService.record(
                    reservation,
                    "PAYMENT_REFUND",
                    refund.getId(),
                    action,
                    details,
                    null,
                    null,
                    null,
                    null,
                    null);
        } else {
            reservationAuditService.recordTarget(
                    "PAYMENT_REFUND", refund.getId(), action, details);
        }
    }
}
