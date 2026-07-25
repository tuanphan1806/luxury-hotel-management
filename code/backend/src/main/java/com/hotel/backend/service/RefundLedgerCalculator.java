package com.hotel.backend.service;

import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.constant.RefundSourceType;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.entity.PaymentRefund;
import com.hotel.backend.entity.PaymentTransaction;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
public class RefundLedgerCalculator {

    public RefundSummary summarize(
            List<PaymentRefund> refunds,
            Collection<RefundStatus> reservedStatuses) {
        long completedAmount = refunds.stream()
                .filter(refund -> refund.getStatus() == RefundStatus.SUCCEEDED)
                .mapToLong(refund -> value(refund.getAmount()))
                .sum();
        long outstandingAmount = refunds.stream()
                .filter(refund -> reservedStatuses.contains(refund.getStatus()))
                .filter(refund -> refund.getStatus() != RefundStatus.SUCCEEDED)
                .mapToLong(refund -> value(refund.getAmount()))
                .sum();
        PaymentRefund latest = refunds.stream()
                .max(Comparator.comparing(PaymentRefund::getUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        return new RefundSummary(
                completedAmount,
                outstandingAmount,
                latest != null ? latest.getChannel() : null,
                latest != null ? latest.getStatus() : null);
    }

    public long getNetPaidAmount(
            List<PaymentTransaction> payments,
            List<PaymentRefund> refunds,
            Collection<RefundStatus> netDeductedStatuses) {
        long gross = payments.stream()
                .mapToLong(payment -> payment.getAcceptedAmount() != null
                        ? payment.getAcceptedAmount() : value(payment.getAmount()))
                .sum();
        long effectiveRefunds = refunds.stream()
                .filter(refund -> netDeductedStatuses.contains(refund.getStatus()))
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
                                    && Objects.equals(
                                    refund.getPaymentTransaction().getId(), payment.getId()))
                            .mapToLong(refund -> value(refund.getAmount()))
                            .sum();
                    return Math.max(0L, value(payment.getRefundAmount()) - recorded);
                })
                .sum();
        return Math.max(0L, gross - effectiveRefunds - legacyCompletedGap);
    }

    public long getOutstandingReservedRefundAmount(
            List<PaymentRefund> refunds,
            Collection<RefundStatus> reservedStatuses) {
        return refunds.stream()
                .filter(refund -> reservedStatuses.contains(refund.getStatus()))
                .filter(refund -> refund.getStatus() != RefundStatus.SUCCEEDED)
                .mapToLong(refund -> value(refund.getAmount()))
                .sum();
    }

    private long value(Long value) {
        return value != null ? value : 0L;
    }

    public record RefundSummary(
            long completedAmount,
            long outstandingAmount,
            RefundChannel latestChannel,
            RefundStatus latestStatus) {}
}
