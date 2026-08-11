package com.hotel.backend.service.chatbot;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatPrivacyRedactorTest {

    private final ChatPrivacyRedactor redactor = new ChatPrivacyRedactor();

    @Test
    void removesCommonPersonalAndBookingIdentifiersBeforeAiPrompt() {
        String redacted = redactor.redact(
                "Email guest@example.com, phone 0387736436, booking RES-SECRET-123; "
                        + "CCCD: 012345678901; card 4111-1111-1111-1111; "
                        + "Họ tên: Nguyễn Văn A; địa chỉ: 12 Trần Phú, Hà Nội\n"
        );

        assertFalse(redacted.contains("guest@example.com"));
        assertFalse(redacted.contains("0387736436"));
        assertFalse(redacted.contains("RES-SECRET-123"));
        assertFalse(redacted.contains("012345678901"));
        assertFalse(redacted.contains("4111-1111-1111-1111"));
        assertFalse(redacted.contains("Nguyễn Văn A"));
        assertFalse(redacted.contains("12 Trần Phú"));
        assertTrue(redacted.contains("[email]"));
        assertTrue(redacted.contains("[booking-code]"));
        assertTrue(redacted.contains("[identity-document]"));
        assertTrue(redacted.contains("[payment-number]"));
        assertTrue(redacted.contains("[name]"));
        assertTrue(redacted.contains("[address]"));
    }

    @Test
    void redactsMultipleEmailsAndIdentityDocumentsWithoutRegexBacktrackingRisk() {
        String redacted = assertTimeout(Duration.ofSeconds(2), () -> redactor.redact(
                "a".repeat(100_000)
                        + " first.person+stay@example-hotel.com; "
                        + "CCCD: 012345678901; passport AB123456; "
                        + "second@example.vn"
        ));

        assertFalse(redacted.contains("first.person+stay@example-hotel.com"));
        assertFalse(redacted.contains("012345678901"));
        assertFalse(redacted.contains("AB123456"));
        assertFalse(redacted.contains("second@example.vn"));
        assertTrue(redacted.contains("[email]"));
        assertTrue(redacted.contains("[identity-document]"));
    }
}
