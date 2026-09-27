package com.hotel.backend.event;

import com.hotel.backend.service.AuditNotificationOutboxStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;


@Component
@RequiredArgsConstructor
public class GuestBookingCreatedEventListener {

    private final AuditNotificationOutboxStore outboxStore;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void sendGuestBookingConfirmation(GuestBookingCreatedEvent event) {
        outboxStore.enqueueBooking(event);
    }
}
