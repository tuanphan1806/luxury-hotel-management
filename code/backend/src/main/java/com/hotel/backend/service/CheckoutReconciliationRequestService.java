package com.hotel.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotel.backend.constant.CheckoutCorrectionType;
import com.hotel.backend.constant.CheckoutReconciliationRequestStatus;
import com.hotel.backend.constant.CheckoutReconciliationStatus;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.request.CheckoutReconciliationEscalationRequest;
import com.hotel.backend.dto.request.CheckoutReconciliationResolutionRequest;
import com.hotel.backend.dto.request.CheckoutRefundRequest;
import com.hotel.backend.dto.request.ManualPaymentReconciliationRequest;
import com.hotel.backend.dto.response.CheckoutReconciliationRequestResponse;
import com.hotel.backend.dto.response.CheckoutReconciliationResponse;
import com.hotel.backend.entity.CheckoutReconciliationRequest;
import com.hotel.backend.entity.MediaAsset;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.CheckoutReconciliationRequestRepository;
import com.hotel.backend.repository.MediaAssetRepository;
import com.hotel.backend.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CheckoutReconciliationRequestService {
    private final CheckoutReconciliationRequestRepository requestRepository;
    private final ReservationRepository reservationRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final ReservationService reservationService;
    private final SePayService sePayService;
    private final ReservationAuditService auditService;
    private final ObjectMapper objectMapper;

    @Transactional
    public CheckoutReconciliationRequestResponse create(
            Long reservationId,
            CheckoutReconciliationEscalationRequest request,
            String idempotencyKey,
            String actorScope,
            User currentUser) {
        requireOperationsUser(currentUser);
        CheckoutReconciliationRequest existing = requestRepository
                .findByIdempotencyKeyAndActorScope(idempotencyKey, actorScope)
                .orElse(null);
        if (existing != null) return toResponse(existing);

        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        CheckoutReconciliationResponse reconciliation = reservationService
                .getCheckoutReconciliation(reservationId, currentUser);
        if (reconciliation.getStatus() != CheckoutReconciliationStatus.MISMATCH) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ được gửi yêu cầu khi đối soát checkout đang lệch");
        }
        if (requestRepository.existsByReservationIdAndStatus(
                reservationId, CheckoutReconciliationRequestStatus.PENDING)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Reservation đã có một yêu cầu đối soát đang chờ ADMIN xử lý");
        }

        String correlationId = UUID.randomUUID().toString();
        CheckoutReconciliationRequest entity = requestRepository.save(
                CheckoutReconciliationRequest.builder()
                        .reservation(reservation)
                        .status(CheckoutReconciliationRequestStatus.PENDING)
                        .mismatchSnapshotJson(objectMapper.valueToTree(reconciliation))
                        .reasonCode(request.getReasonCode().trim())
                        .reasonNote(request.getNote().trim())
                        .requestedBy(currentUser)
                        .requestedByName(actorName(currentUser))
                        .requestedByRole(currentUser.getType().name())
                        .idempotencyKey(idempotencyKey)
                        .actorScope(actorScope)
                        .correlationId(correlationId)
                        .createdAtUtc(Instant.now())
                        .build());
        auditService.record(
                reservation,
                "CHECKOUT_RECONCILIATION_REQUEST",
                String.valueOf(entity.getId()),
                ReservationAuditAction.CHECKOUT_RECONCILIATION_REQUESTED,
                "Yêu cầu ADMIN xử lý sai lệch đối soát checkout",
                null,
                Map.of("status", CheckoutReconciliationRequestStatus.PENDING.name()),
                Map.of(
                        "reasonCode", request.getReasonCode().trim(),
                        "note", request.getNote().trim(),
                        "requiredAmount", reconciliation.getRequiredAmount(),
                        "acceptedAmount", reconciliation.getAcceptedAmount(),
                        "deltaAmount", reconciliation.getDeltaAmount(),
                        "blockingReasons", reconciliation.getBlockingReasons()),
                correlationId,
                "CHECKOUT_RECONCILIATION_REQUESTED:" + entity.getId());
        return toResponse(entity);
    }

    @Transactional(readOnly = true)
    public CheckoutReconciliationRequestResponse get(Long id) {
        return toResponse(requireRequest(id));
    }

    @Transactional(readOnly = true)
    public Page<CheckoutReconciliationRequestResponse> list(
            CheckoutReconciliationRequestStatus status,
            Pageable pageable) {
        if (status == null) {
            return requestRepository.findAll(pageable).map(this::toResponse);
        }
        return requestRepository.findByStatusOrderByCreatedAtUtcAsc(status, pageable)
                .map(this::toResponse);
    }

    @Transactional
    public CheckoutReconciliationRequestResponse resolve(
            Long id,
            CheckoutReconciliationResolutionRequest resolution,
            User admin) {
        requireAdmin(admin);
        CheckoutReconciliationRequest entity = requestRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy yêu cầu đối soát"));
        if (entity.getStatus() != CheckoutReconciliationRequestStatus.PENDING) {
            return toResponse(entity);
        }

        Reservation reservation = reservationRepository
                .findByIdForUpdate(entity.getReservation().getId())
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        MediaAsset evidence = resolution.getEvidenceAssetId() == null
                ? null
                : mediaAssetRepository.findById(resolution.getEvidenceAssetId())
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy minh chứng đã chọn"));

        entity.setResolvedBy(admin);
        entity.setResolvedByName(actorName(admin));
        entity.setResolvedByRole(admin.getType().name());
        entity.setResolutionReasonCode(resolution.getReasonCode().trim());
        entity.setResolutionNote(resolution.getNote().trim());
        entity.setEvidenceAsset(evidence);
        entity.setResolvedAtUtc(Instant.now());

        if (!resolution.isApprove()) {
            entity.setStatus(CheckoutReconciliationRequestStatus.REJECTED);
            entity = requestRepository.save(entity);
            auditService.record(
                    reservation,
                    "CHECKOUT_RECONCILIATION_REQUEST",
                    String.valueOf(entity.getId()),
                    ReservationAuditAction.CHECKOUT_RECONCILIATION_REJECTED,
                    "ADMIN từ chối yêu cầu xử lý sai lệch checkout",
                    Map.of("status", CheckoutReconciliationRequestStatus.PENDING.name()),
                    Map.of("status", CheckoutReconciliationRequestStatus.REJECTED.name()),
                    Map.of(
                            "reasonCode", resolution.getReasonCode().trim(),
                            "note", resolution.getNote().trim()),
                    entity.getCorrelationId(),
                    "CHECKOUT_RECONCILIATION_REJECTED:" + entity.getId());
            return toResponse(entity);
        }

        CheckoutCorrectionType correctionType = resolution.getCorrectionType();
        if (correctionType == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Phải chọn loại điều chỉnh khi phê duyệt");
        }
        ObjectNode correctionDetail = objectMapper.createObjectNode();
        correctionDetail.put("correctionType", correctionType.name());
        switch (correctionType) {
            case FEE_CORRECTION -> applyFeeCorrection(
                    reservation.getId(), resolution, correctionDetail);
            case LINK_EXISTING_PAYMENT -> applyPaymentLink(
                    resolution, admin, correctionDetail);
        }

        CheckoutReconciliationResponse after = reservationService
                .getCheckoutReconciliation(reservation.getId(), admin);
        if (after.getStatus() != CheckoutReconciliationStatus.MATCHED) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Điều chỉnh chưa làm đối soát khớp; yêu cầu vẫn phải giữ PENDING");
        }
        correctionDetail.set("reconciliationAfter", objectMapper.valueToTree(after));
        entity.setCorrectionType(correctionType);
        entity.setCorrectionDetailJson(correctionDetail);
        entity.setStatus(CheckoutReconciliationRequestStatus.APPROVED);
        entity = requestRepository.save(entity);

        auditService.record(
                reservation,
                "CHECKOUT_RECONCILIATION_REQUEST",
                String.valueOf(entity.getId()),
                ReservationAuditAction.CHECKOUT_RECONCILIATION_OVERRIDDEN,
                "ADMIN phê duyệt điều chỉnh; đối soát đã khớp, chưa thực hiện checkout",
                Map.of(
                        "requestStatus", CheckoutReconciliationRequestStatus.PENDING.name(),
                        "reconciliation", CheckoutReconciliationStatus.MISMATCH.name()),
                Map.of(
                        "requestStatus", CheckoutReconciliationRequestStatus.APPROVED.name(),
                        "reconciliation", after.getStatus().name()),
                Map.of(
                        "correctionType", correctionType.name(),
                        "reasonCode", resolution.getReasonCode().trim(),
                        "note", resolution.getNote().trim(),
                        "checkoutTriggered", false),
                entity.getCorrelationId(),
                "CHECKOUT_RECONCILIATION_OVERRIDDEN:" + entity.getId());
        return toResponse(entity);
    }

    private void applyFeeCorrection(
            Long reservationId,
            CheckoutReconciliationResolutionRequest resolution,
            ObjectNode detail) {
        if (resolution.getCorrectedAdditionalFee() == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Phải nhập phụ phí mới cho FEE_CORRECTION");
        }
        CheckoutRefundRequest request = new CheckoutRefundRequest();
        request.setAdditionalFee(resolution.getCorrectedAdditionalFee());
        request.setReasonCode(resolution.getReasonCode().trim());
        request.setReason(resolution.getNote().trim());
        reservationService.updateCheckoutAdditionalFee(reservationId, request);
        detail.put("correctedAdditionalFee", resolution.getCorrectedAdditionalFee());
    }

    private void applyPaymentLink(
            CheckoutReconciliationResolutionRequest resolution,
            User admin,
            ObjectNode detail) {
        if (!hasText(resolution.getPaymentProviderEventId())
                || !hasText(resolution.getPaymentTransactionId())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Phải chọn event SePay và payment cần liên kết");
        }
        ManualPaymentReconciliationRequest request = new ManualPaymentReconciliationRequest();
        request.setPaymentTransactionId(resolution.getPaymentTransactionId().trim());
        request.setReasonCode(resolution.getReasonCode().trim());
        request.setNote(resolution.getNote().trim());
        if (resolution.getEvidenceAssetId() != null) {
            request.setEvidenceReference("media-asset:" + resolution.getEvidenceAssetId());
        }
        sePayService.manuallyReconcileReviewEvent(
                resolution.getPaymentProviderEventId().trim(), request, admin);
        detail.put("paymentProviderEventId", resolution.getPaymentProviderEventId().trim());
        detail.put("paymentTransactionId", resolution.getPaymentTransactionId().trim());
    }

    private CheckoutReconciliationRequest requireRequest(Long id) {
        return requestRepository.findById(id)
                .orElseThrow(() -> new AppException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy yêu cầu đối soát"));
    }

    private CheckoutReconciliationRequestResponse toResponse(
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

    private void requireOperationsUser(User user) {
        if (user == null || (user.getType() != UserType.STAFF && user.getType() != UserType.ADMIN)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ STAFF/ADMIN được gửi yêu cầu đối soát");
        }
    }

    private void requireAdmin(User user) {
        if (user == null || user.getType() != UserType.ADMIN) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ ADMIN được xử lý yêu cầu đối soát");
        }
    }

    private String actorName(User user) {
        if (hasText(user.getFullName())) return user.getFullName().trim();
        if (hasText(user.getUsername())) return user.getUsername().trim();
        return "user:" + user.getId();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
