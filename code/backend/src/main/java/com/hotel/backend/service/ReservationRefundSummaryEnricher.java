package com.hotel.backend.service;

import com.hotel.backend.constant.PaymentStatus;
import com.hotel.backend.constant.RefundChannel;
import com.hotel.backend.constant.RefundDestinationStatus;
import com.hotel.backend.constant.RefundRecipientStatus;
import com.hotel.backend.constant.RefundRoute;
import com.hotel.backend.constant.RefundStatus;
import com.hotel.backend.constant.ReservationStatus;
import com.hotel.backend.dto.response.ReservationRefundResponse;
import com.hotel.backend.dto.response.ReservationResponse;
import com.hotel.backend.entity.PaymentRefund;
import com.hotel.backend.entity.PaymentTransaction;
import com.hotel.backend.entity.RefundRecipient;
import com.hotel.backend.repository.PaymentRefundRepository;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.RefundRecipientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ReservationRefundSummaryEnricher {

    private final PaymentRefundRepository refundRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final RefundRecipientRepository recipientRepository;

    public ReservationResponse apply(ReservationResponse response) {
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
        boolean hasManualBank = refunds.stream().anyMatch(
                refund -> refund.getChannel() == RefundChannel.MANUAL_BANK_TRANSFER);
        boolean hasCash = refunds.stream().anyMatch(
                refund -> refund.getChannel() == RefundChannel.CASH_AT_COUNTER);
        response.setRefundRoute(hasManualBank && hasCash
                ? RefundRoute.MIXED
                : hasManualBank
                ? RefundRoute.MANUAL_BANK_TRANSFER
                : hasCash
                ? RefundRoute.CASH_AT_COUNTER
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
}
