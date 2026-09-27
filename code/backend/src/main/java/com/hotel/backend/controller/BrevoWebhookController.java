package com.hotel.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.service.BrevoDeliveryEventService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequiredArgsConstructor
public class BrevoWebhookController {
    private final ObjectMapper mapper;
    private final BrevoDeliveryEventService events;
    @Value("${app.email.brevo-webhook-secret:}") private String secret;

    @PostMapping("/api/email/brevo/webhook")
    public ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        if (secret == null || secret.length() < 32) return ResponseEntity.notFound().build();
        String supplied = request.getHeader("X-Brevo-Webhook-Token");
        if (supplied == null) {
            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) supplied = authorization.substring(7);
        }
        if (supplied == null || supplied.length() > 512 || !MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        // Authenticate before reading a bounded payload; never log headers or email bodies.
        byte[] body = request.getInputStream().readNBytes(32769);
        if (body.length > 32768) return ResponseEntity.status(413).build();
        try {
            var payload = mapper.readTree(body);
            if (payload == null || !payload.isObject()) return ResponseEntity.badRequest().build();
            events.accept(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException exception) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok().build();
    }
}
