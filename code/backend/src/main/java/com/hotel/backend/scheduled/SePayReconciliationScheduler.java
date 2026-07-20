package com.hotel.backend.scheduled;

import com.hotel.backend.config.SePayConfig;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.dto.response.SePayApiTransaction;
import com.hotel.backend.service.ReconciliationStateService;
import com.hotel.backend.service.PaymentProviderEventService;
import com.hotel.backend.service.SePayApiClient;
import com.hotel.backend.service.SePayService;
import com.hotel.backend.service.BusinessMetricService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.Instant;
import java.util.Comparator;

@Slf4j(topic = "SEPAY-RECONCILIATION")
@Component
@RequiredArgsConstructor
public class SePayReconciliationScheduler {

    private final SePayConfig config;
    private final SePayApiClient apiClient;
    private final SePayService sePayService;
    private final ReconciliationStateService stateService;
    private final PaymentProviderEventService providerEventService;
    private final BusinessMetricService businessMetrics;

    @Scheduled(fixedDelayString = "${sepay.reconciliation-interval-ms:30000}")
    public void reconcileRecentIncomingTransactions() {
        if (!config.isReconciliationEnabled() || !hasText(config.getApiAccessToken())
                || !hasText(config.getApiBaseUrl())) {
            return;
        }
        String merchantAccountId = sePayService.configuredMerchantAccountId();
        try {
            Instant fromUtc = stateService.queryFromUtc(
                    PaymentProvider.SEPAY, merchantAccountId);
            Instant dueRetryFromUtc = providerEventService
                    .earliestDueRetryOccurredAt(PaymentProvider.SEPAY)
                    .map(occurredAt -> occurredAt.minusSeconds(
                            Math.max(0, config.getReconciliationOverlapMinutes()) * 60L))
                    .orElse(null);
            if (dueRetryFromUtc != null && dueRetryFromUtc.isBefore(fromUtc)) {
                fromUtc = dueRetryFromUtc;
            }
            List<SePayApiTransaction> transactions = apiClient.listTransactions(config, fromUtc)
                    .stream()
                    .sorted(Comparator.comparing(
                            sePayService::providerOccurredAtUtc,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            for (SePayApiTransaction transaction : transactions) {
                try {
                    sePayService.reconcile(transaction);
                    businessMetrics.increment(
                            "hotel.sepay.reconciliation.transactions", "result", "durable");
                    stateService.recordDurableEvent(
                            PaymentProvider.SEPAY,
                            merchantAccountId,
                            transaction != null ? transaction.id() : null,
                            sePayService.providerOccurredAtUtc(transaction));
                } catch (RuntimeException exception) {
                    if (sePayService.hasDurableEvent(transaction)) {
                        stateService.recordDurableEvent(
                                PaymentProvider.SEPAY,
                                merchantAccountId,
                                transaction != null ? transaction.id() : null,
                                sePayService.providerOccurredAtUtc(transaction));
                    } else {
                        throw exception;
                    }
                    log.error("Không thể đối soát một giao dịch SePay id={}: {}",
                            transaction != null ? transaction.id() : null, exception.getMessage());
                }
            }
            stateService.recordRunSuccess(PaymentProvider.SEPAY, merchantAccountId);
            businessMetrics.increment(
                    "hotel.scheduler.runs", "job", "sepay_reconciliation");
        } catch (RuntimeException exception) {
            stateService.recordRunFailure(PaymentProvider.SEPAY, merchantAccountId, exception);
            businessMetrics.increment(
                    "hotel.scheduler.failures", "job", "sepay_reconciliation");
            log.warn("Đối soát SePay tạm thời thất bại: {}", exception.getMessage());
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
