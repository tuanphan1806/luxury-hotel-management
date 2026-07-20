package com.hotel.backend.scheduled;

import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.service.PaymentSessionExpiryService;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.service.BusinessMetricService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j(topic = "PAYMENT_MAINTENANCE_SCHEDULER")
@Component
@RequiredArgsConstructor
public class PaymentMaintenanceScheduler {

    private static final int LEGACY_PENDING_TIMEOUT_MINUTES = 5;
    private static final int REFUND_PENDING_ALERT_HOURS = 48;

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentSessionExpiryService paymentSessionExpiryService;
    private final ReservationRepository reservationRepository;
    private final BusinessMetricService businessMetrics;

    @Scheduled(
            fixedDelayString = "${app.maintenance.payment-interval-ms:300000}",
            initialDelayString = "${app.maintenance.startup-delay-ms:60000}")
    public void expirePrePaymentReservations() {
        LocalDateTime now = LocalDateTime.now();
        int minutes = Math.max(1, prePaymentSessionMinutes());
        LocalDateTime cutoff = now.minusMinutes(minutes);
        int expired = 0;
        for (Long reservationId : reservationRepository.findStalePrePaymentSessionIds(cutoff, now)) {
            try {
                if (paymentSessionExpiryService.expirePrePaymentReservation(reservationId)) {
                    expired++;
                    businessMetrics.increment(
                            "hotel.payment.session.timeout", "type", "pre_payment");
                }
            } catch (RuntimeException exception) {
                businessMetrics.increment(
                        "hotel.scheduler.failures", "job", "pre_payment_expiry");
                log.error("Cannot expire pre-payment reservationId={}: {}",
                        reservationId, exception.getMessage());
            }
        }
        if (expired > 0) log.info("Expired {} stale pre-payment reservations", expired);
        businessMetrics.increment("hotel.scheduler.runs", "job", "pre_payment_expiry");
    }

    @org.springframework.beans.factory.annotation.Value(
            "${app.reservation.pre-payment-session-minutes:30}")
    private int prePaymentSessionMinutes;

    private int prePaymentSessionMinutes() {
        return prePaymentSessionMinutes;
    }

    @Scheduled(
            fixedDelayString = "${app.maintenance.payment-interval-ms:300000}",
            initialDelayString = "${app.maintenance.startup-delay-ms:60000}")
    public void expirePendingTransactions() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime legacyCutoff = now.minusMinutes(LEGACY_PENDING_TIMEOUT_MINUTES);
        List<String> candidates = paymentTransactionRepository.findExpiredPendingIds(
                PaymentStatus.PENDING, now, legacyCutoff);
        int expired = 0;
        for (String transactionId : candidates) {
            try {
                if (paymentSessionExpiryService.timeout(transactionId)) {
                    expired++;
                    businessMetrics.increment(
                            "hotel.payment.session.timeout", "type", "payment_transaction");
                }
            } catch (RuntimeException exception) {
                businessMetrics.increment(
                        "hotel.scheduler.failures", "job", "payment_expiry");
                log.error("Cannot expire payment session transactionId={}: {}",
                        transactionId, exception.getMessage());
            }
        }

        if (expired > 0) {
            log.info("Marked {} stale pending payment transactions as CANCELLED", expired);
        }
        businessMetrics.increment("hotel.scheduler.runs", "job", "payment_expiry");
    }

    @Scheduled(cron = "0 0 8 * * *")
    @Transactional(readOnly = true)
    public void flagStaleRefunds() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(REFUND_PENDING_ALERT_HOURS);
        List<PaymentTransaction> staleRefunds =
                paymentTransactionRepository.findStaleRefundPendingTransactions(
                        PaymentStatus.REFUND_PENDING,
                        cutoff);

        for (PaymentTransaction transaction : staleRefunds) {
            log.warn("Refund pending for more than {} hours: transactionId={}, txnRef={}, reservationId={}",
                    REFUND_PENDING_ALERT_HOURS,
                    transaction.getId(),
                    transaction.getTxnRef(),
                    transaction.getReservation().getId());
        }
        businessMetrics.increment("hotel.scheduler.runs", "job", "stale_refund_scan");
    }
}
