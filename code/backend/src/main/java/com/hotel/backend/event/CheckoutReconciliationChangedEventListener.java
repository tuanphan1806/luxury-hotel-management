package com.hotel.backend.event;

import com.hotel.backend.service.CheckoutReconciliationRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "CHECKOUT-RECONCILIATION-LISTENER")
public class CheckoutReconciliationChangedEventListener {

    private final CheckoutReconciliationRequestService requestService;

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void resolvePendingRequestWhenMatched(CheckoutReconciliationChangedEvent event) {
        if (event == null || event.reservationId() == null) return;
        try {
            requestService.resolvePendingAutomatically(
                    event.reservationId(), event.source());
        } catch (RuntimeException exception) {
            // The financial operation already committed. Failure to refresh the
            // exception queue must never report a false rollback or permit
            // checkout; the PENDING request remains visible for ADMIN review.
            log.error(
                    "Không thể tự đóng yêu cầu đối soát: reservationId={}, source={}",
                    event.reservationId(), event.source(), exception);
        }
    }
}
