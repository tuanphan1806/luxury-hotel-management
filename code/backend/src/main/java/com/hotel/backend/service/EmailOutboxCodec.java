package com.hotel.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Uses the existing protected AES-GCM key; snapshots include guest capability links. */
@Component
@RequiredArgsConstructor
public class EmailOutboxCodec {
    private static final String DOMAIN = "hotel-email-outbox:v1:";
    private final RefundDataCipher cipher;
    private final ObjectMapper mapper;

    public String encode(EmailService.QueuedEmail message) {
        try { return cipher.encrypt(DOMAIN + mapper.writeValueAsString(message)); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Cannot encode email snapshot"); }
    }

    public EmailService.QueuedEmail decode(String encrypted) {
        String value = cipher.decrypt(encrypted);
        if (!value.startsWith(DOMAIN)) throw new IllegalStateException("Invalid email snapshot domain");
        try { return mapper.readValue(value.substring(DOMAIN.length()), EmailService.QueuedEmail.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid email snapshot"); }
    }
}
