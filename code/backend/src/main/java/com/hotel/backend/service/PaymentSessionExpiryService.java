package com.hotel.backend.service;

import com.hotel.backend.config.SePayConfig;
import com.hotel.backend.constant.HoldStatus;
import com.hotel.backend.constant.PaymentPurpose;
import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.RoomHold;
import com.hotel.backend.dto.request.ManualRoomHoldReleaseRequest;
import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.repository.RoomHoldRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentSessionExpiryService {

    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SePayConfig sePayConfig;
    private final ReservationRepository reservationRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final RoomHoldRepository roomHoldRepository;
    private final ReservationAuditService auditService;

    @Transactional
    public boolean timeout(String transactionId) {
        return transition(transactionId, Transition.TIMEOUT);
    }

    @Transactional
    public boolean abandon(String transactionId) {
        return transition(transactionId, Transition.ABANDON);
    }

    /** Cancel a reservation that never opened a payment session. */
    @Transactional
    public boolean expirePrePaymentReservation(Long reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElse(null);
        if (reservation == null || reservation.getStatus() != ReservationStatus.PAYMENT_PENDING) {
            return false;
        }
        LocalDateTime now = now();
        boolean hasPayment = !transactionRepository.findByReservationId(reservationId).stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.PENDING
                        || payment.getStatus() == PaymentStatus.SUCCESS)
                .toList().isEmpty();
        if (hasPayment) return false;
        boolean activeHold = roomHoldRepository.findByReservationIdForUpdate(reservationId).stream()
                .anyMatch(hold -> hold.getStatus() == HoldStatus.ACTIVE
                        && hold.getExpiresAt() != null && hold.getExpiresAt().isAfter(now));
        if (activeHold) return false;
        ReservationStatus oldStatus = reservation.getStatus();
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancellationReasonCode("PRE_PAYMENT_SESSION_EXPIRED");
        reservation.setCancellationReason("Phiên đặt phòng chưa mở thanh toán đã hết hạn");
        reservation.setLastActivityAt(now);
        reservationRepository.save(reservation);
        String correlationId = UUID.randomUUID().toString();
        auditService.recordSystem(reservation, "RESERVATION", String.valueOf(reservationId),
                ReservationAuditAction.RESERVATION_AUTO_CANCELLED,
                "PRE_PAYMENT_SESSION_EXPIRED",
                Map.of("status", oldStatus.name()),
                Map.of("status", ReservationStatus.CANCELLED.name()),
                Map.of("reasonCode", "PRE_PAYMENT_SESSION_EXPIRED"),
                correlationId,
                "RESERVATION_AUTO_CANCELLED:" + reservationId + ":PRE_PAYMENT_SESSION_EXPIRED");
        return true;
    }

    /**
     * Operator exception for an unpaid deposit aggregate. Payment, active
     * holds and reservation are closed together so the ledger cannot remain
     * PENDING after inventory has been released.
     */
    @Transactional
    public ReservationResponse releaseRoomHoldsManually(
            Long reservationId,
            ManualRoomHoldReleaseRequest request) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        if (reservation.getStatus() != ReservationStatus.PAYMENT_PENDING) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ được giải phóng tay RoomHold của reservation PAYMENT_PENDING");
        }
        List<PaymentTransaction> payments = transactionRepository.findByReservationId(reservationId);
        if (payments.stream().anyMatch(payment -> payment.getPurpose() == PaymentPurpose.DEPOSIT
                && payment.getStatus() == PaymentStatus.SUCCESS)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Không được giải phóng RoomHold sau khi tiền cọc đã được ghi nhận");
        }
        List<PaymentTransaction> pending = transactionRepository
                .findByReservationPurposeStatusForUpdate(
                        reservationId, PaymentPurpose.DEPOSIT, PaymentStatus.PENDING);
        List<RoomHold> holds = roomHoldRepository.findByReservationIdForUpdate(reservationId);
        if (holds.stream().anyMatch(hold -> hold.getStatus() == HoldStatus.CONVERTED)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Không được giải phóng tay RoomHold CONVERTED");
        }
        List<RoomHold> active = holds.stream()
                .filter(hold -> hold.getStatus() == HoldStatus.ACTIVE)
                .toList();
        if (active.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Reservation không còn RoomHold ACTIVE để giải phóng");
        }

        LocalDateTime now = now();
        pending.forEach(payment -> {
            payment.setStatus(PaymentStatus.CANCELLED);
            payment.setMessage("Nhân viên giải phóng RoomHold: " + request.getReasonCode().trim());
        });
        transactionRepository.saveAll(pending);
        active.forEach(hold -> {
            hold.setStatus(HoldStatus.RELEASED);
            hold.setExpiresAt(now);
        });
        roomHoldRepository.saveAll(active);
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancellationReasonCode(request.getReasonCode().trim());
        reservation.setCancellationReason(request.getNote().trim());
        reservation.setLastActivityAt(now);
        reservationRepository.save(reservation);

        String correlationId = UUID.randomUUID().toString();
        for (RoomHold hold : active) {
            auditService.record(reservation, "ROOM_HOLD", String.valueOf(hold.getId()),
                    ReservationAuditAction.ROOM_HOLD_RELEASED_MANUALLY,
                    "Giải phóng RoomHold trước hạn: " + request.getNote().trim(),
                    Map.of("status", HoldStatus.ACTIVE.name()),
                    Map.of("status", HoldStatus.RELEASED.name()),
                    Map.of(
                            "reasonCode", request.getReasonCode().trim(),
                            "note", request.getNote().trim(),
                            "cancelledPaymentCount", pending.size()),
                    correlationId,
                    "ROOM_HOLD_RELEASED_MANUALLY:" + hold.getId());
        }
        return ReservationResponse.from(reservation);
    }

    /**
     * Compatibility entry point for an expired hold candidate. The aggregate
     * service still locks Reservation -> Payment -> RoomHold and owns every
     * state transition; the scheduler never mutates a hold directly.
     */
    @Transactional
    public boolean timeoutDepositReservation(Long reservationId) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElse(null);
        if (reservation == null || reservation.getStatus() != ReservationStatus.PAYMENT_PENDING) {
            return false;
        }
        List<PaymentTransaction> pending = transactionRepository
                .findByReservationPurposeStatusForUpdate(
                        reservationId, PaymentPurpose.DEPOSIT, PaymentStatus.PENDING);
        LocalDateTime now = now();
        if (pending.isEmpty()) {
            return cleanupExpiredOrphanHolds(reservation, now);
        }
        PaymentTransaction expired = pending.stream()
                .filter(payment -> isExpired(payment, now))
                .findFirst()
                .orElse(null);
        if (expired == null) return false;
        return applyTransition(reservation, expired, Transition.TIMEOUT, now);
    }

    private boolean cleanupExpiredOrphanHolds(Reservation reservation, LocalDateTime now) {
        List<RoomHold> holds = roomHoldRepository.findByReservationIdForUpdate(reservation.getId());
        List<RoomHold> active = holds.stream()
                .filter(hold -> hold.getStatus() == HoldStatus.ACTIVE)
                .toList();
        if (active.isEmpty() || active.stream().anyMatch(hold -> hold.getExpiresAt().isAfter(now))) {
            return false;
        }
        String correlationId = UUID.randomUUID().toString();
        active.forEach(hold -> hold.setStatus(HoldStatus.EXPIRED));
        roomHoldRepository.saveAll(active);
        ReservationStatus oldReservationStatus = reservation.getStatus();
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancellationReasonCode("ORPHANED_HOLD_EXPIRED");
        reservation.setCancellationReason("Phiên giữ phòng không còn giao dịch thanh toán hợp lệ");
        reservation.setLastActivityAt(now);
        reservationRepository.save(reservation);
        for (RoomHold hold : active) {
            auditService.recordSystem(reservation, "ROOM_HOLD", String.valueOf(hold.getId()),
                    ReservationAuditAction.ROOM_HOLD_AUTO_EXPIRED,
                    "RoomHold orphan quá hạn",
                    Map.of("status", HoldStatus.ACTIVE.name()),
                    Map.of("status", HoldStatus.EXPIRED.name()),
                    Map.of("reasonCode", "ORPHANED_HOLD_EXPIRED"),
                    correlationId,
                    "ROOM_HOLD_AUTO_EXPIRED:" + hold.getId());
        }
        auditService.recordSystem(reservation, "RESERVATION", String.valueOf(reservation.getId()),
                ReservationAuditAction.RESERVATION_AUTO_CANCELLED,
                "ORPHANED_HOLD_EXPIRED",
                Map.of("status", oldReservationStatus.name()),
                Map.of("status", ReservationStatus.CANCELLED.name()),
                Map.of("reasonCode", "ORPHANED_HOLD_EXPIRED"),
                correlationId,
                "RESERVATION_AUTO_CANCELLED:" + reservation.getId() + ":ORPHANED_HOLD_EXPIRED");
        return true;
    }

    private boolean transition(String transactionId, Transition transition) {
        PaymentTransaction snapshot = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy giao dịch thanh toán"));
        Reservation reservation = reservationRepository
                .findByIdForUpdate(snapshot.getReservation().getId())
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));
        PaymentTransaction payment = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy giao dịch thanh toán"));
        if (payment.getStatus() != PaymentStatus.PENDING) return false;

        LocalDateTime now = now();
        if (transition == Transition.TIMEOUT && !isExpired(payment, now)) return false;
        return applyTransition(reservation, payment, transition, now);
    }

    private boolean applyTransition(
            Reservation reservation,
            PaymentTransaction payment,
            Transition transition,
            LocalDateTime now) {
        String correlationId = UUID.randomUUID().toString();
        payment.setStatus(PaymentStatus.CANCELLED);
        if (payment.getExpiresAt() == null) payment.setExpiresAt(now);
        if (payment.getExpiresAtUtc() == null) {
            payment.setExpiresAtUtc(payment.getExpiresAt().atZone(HOTEL_ZONE).toInstant());
        }
        payment.setMessage(transition == Transition.TIMEOUT
                ? "Phiên thanh toán đã hết hạn"
                : "Khách đã rời trang QR trước khi thanh toán");
        transactionRepository.save(payment);

        if (payment.getPurpose() != PaymentPurpose.DEPOSIT
                || reservation.getStatus() != ReservationStatus.PAYMENT_PENDING) {
            reservation.setLastActivityAt(now);
            reservationRepository.save(reservation);
            return true;
        }

        List<RoomHold> holds = roomHoldRepository.findByReservationIdForUpdate(reservation.getId());
        HoldStatus targetHoldStatus = transition == Transition.TIMEOUT
                ? HoldStatus.EXPIRED : HoldStatus.RELEASED;
        List<RoomHold> changedHolds = holds.stream()
                .filter(hold -> hold.getStatus() == HoldStatus.ACTIVE)
                .toList();
        changedHolds.forEach(hold -> {
                    hold.setStatus(targetHoldStatus);
                    if (transition == Transition.ABANDON) hold.setExpiresAt(now);
                });
        roomHoldRepository.saveAll(holds);

        String reasonCode = transition == Transition.TIMEOUT
                ? "PAYMENT_SESSION_EXPIRED" : "PAYMENT_ABANDONED";
        ReservationStatus oldReservationStatus = reservation.getStatus();
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancellationReasonCode(reasonCode);
        reservation.setCancellationReason(transition == Transition.TIMEOUT
                ? "Phiên thanh toán đặt cọc đã hết hạn"
                : "Khách rời trang QR trước khi thanh toán đặt cọc");
        reservation.setLastActivityAt(now);
        reservationRepository.save(reservation);
        if (transition == Transition.TIMEOUT) {
            for (RoomHold hold : changedHolds) {
                auditService.recordSystem(reservation, "ROOM_HOLD", String.valueOf(hold.getId()),
                        ReservationAuditAction.ROOM_HOLD_AUTO_EXPIRED,
                        reasonCode + ": " + payment.getTxnRef(),
                        Map.of("status", HoldStatus.ACTIVE.name()),
                        Map.of("status", HoldStatus.EXPIRED.name()),
                        Map.of("paymentId", payment.getId(), "reasonCode", reasonCode),
                        correlationId,
                        "ROOM_HOLD_AUTO_EXPIRED:" + hold.getId());
            }
            auditService.recordSystem(reservation, "RESERVATION",
                    String.valueOf(reservation.getId()),
                    ReservationAuditAction.RESERVATION_AUTO_CANCELLED,
                    reasonCode + ": " + payment.getTxnRef(),
                    Map.of("status", oldReservationStatus.name()),
                    Map.of("status", ReservationStatus.CANCELLED.name()),
                    Map.of("paymentId", payment.getId(), "reasonCode", reasonCode),
                    correlationId,
                    "RESERVATION_AUTO_CANCELLED:" + reservation.getId() + ":" + payment.getId());
        } else {
            auditService.record(reservation, "RESERVATION", String.valueOf(reservation.getId()),
                    ReservationAuditAction.PAYMENT_ABANDONED,
                    reasonCode + ": " + payment.getTxnRef(),
                    Map.of("status", oldReservationStatus.name()),
                    Map.of("status", ReservationStatus.CANCELLED.name()),
                    Map.of("paymentId", payment.getId(), "reasonCode", reasonCode),
                    correlationId,
                    null);
        }
        return true;
    }

    private boolean isExpired(PaymentTransaction payment, LocalDateTime now) {
        if (payment.getExpiresAtUtc() != null) {
            return !payment.getExpiresAtUtc().isAfter(Instant.now());
        }
        if (payment.getExpiresAt() != null) {
            return !payment.getExpiresAt().isAfter(now);
        }
        int timeoutMinutes = Math.max(1, sePayConfig.getPaymentTimeoutMinutes());
        return payment.getCreatedAt() != null
                && !payment.getCreatedAt().isAfter(now.minusMinutes(timeoutMinutes));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(HOTEL_ZONE);
    }

    private enum Transition {
        TIMEOUT,
        ABANDON
    }
}
