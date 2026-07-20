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
@Slf4j(topic = "USER-REGISTERED-LISTENER")
public class UserRegisteredEventListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendVerificationEmail(UserRegisteredEvent event) {
        try {
            emailService.emailVerification(event.userId());
        } catch (IOException | RuntimeException ex) {
            log.error("Failed to send verification email after register commit: userId={}, error={}",
                    event.userId(), ex.getMessage());
        }
    }
}
