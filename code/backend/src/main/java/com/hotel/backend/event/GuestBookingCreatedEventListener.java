package com.hotel.backend.event;

import com.hotel.backend.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "GUEST-BOOKING-LISTENER")
public class GuestBookingCreatedEventListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendGuestBookingConfirmation(GuestBookingCreatedEvent event) {
        try {
            emailService.sendGuestBookingConfirmation(
                    event.email(),
                    event.reservationId(),
                    event.guestToken());
        } catch (IOException | RuntimeException ex) {
            log.error("Failed to send guest booking email after commit: reservationId={}, email={}, error={}",
                    event.reservationId(), event.email(), ex.getMessage());
        }
    }
}
