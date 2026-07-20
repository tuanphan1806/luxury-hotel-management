package com.hotel.backend.service;

import com.hotel.backend.event.VNPayRefundRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class VNPayRefundEventListener {

    private final PaymentRefundService refundService;

    @Async("vnpayRefundExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRefundRequested(VNPayRefundRequestedEvent event) {
        try {
            refundService.submitToVNPay(event.refundId());
        } catch (Exception exception) {
            // Reservation/cancellation đã commit. Không được báo rollback giả cho người dùng;
            // yêu cầu vẫn nằm trong queue để đối soát/retry.
            log.error("Không thể xử lý VNPay refund sau commit: refundId={}", event.refundId(), exception);
        }
    }
}
