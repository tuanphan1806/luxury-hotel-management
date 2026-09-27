package com.hotel.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.constant.AuditNotificationStatus;
import com.hotel.backend.repository.AuditNotificationOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BrevoDeliveryEventService {
    private static final String PREFIX = "hotel-email-key:";
    private static final Map<String, String> STATES = Map.of(
            "request", "ACCEPTED", "delivered", "DELIVERED", "deferred", "DEFERRED",
            "soft_bounce", "SOFT_BOUNCE", "hard_bounce", "HARD_BOUNCE", "invalid_email", "INVALID_EMAIL",
            "blocked", "BLOCKED", "spam", "SPAM", "error", "ERROR");
    private static final Set<String> TERMINAL_FAILURE = Set.of("HARD_BOUNCE", "INVALID_EMAIL", "BLOCKED", "SPAM", "ERROR");
    private final AuditNotificationOutboxRepository repository;

    @Transactional
    public void accept(JsonNode event) {
        String state = STATES.get(event.path("event").asText());
        String custom = event.path("X-Mailin-custom").asText();
        // Account-wide Brevo hooks also contain messages not sent by this outbox.
        if (state == null || !custom.startsWith(PREFIX)) return;
        String key = custom.substring(PREFIX.length());
        if (!key.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("Invalid delivery key");
        String messageId = normalizeId(event.path("message-id").asText());
        if (messageId.isBlank() || messageId.length() > 512 || !event.path("ts_event").canConvertToLong()) {
            throw new IllegalArgumentException("Invalid receipt");
        }
        long seconds = event.path("ts_event").longValue();
        if (seconds <= 0 || seconds > Instant.now().plusSeconds(300).getEpochSecond()) {
            throw new IllegalArgumentException("Invalid event time");
        }
        Instant occurred = Instant.ofEpochSecond(seconds);
        var row = repository.findByDeliveryKeyForUpdate(key).orElse(null);
        if (row == null || !row.getRecipientEmail().equalsIgnoreCase(event.path("email").asText())
                || row.getDeliveryScope() == null || !row.getDeliveryScope().startsWith("brevo:")) return;
        if (row.getFirstAttemptAtUtc() == null || occurred.isBefore(row.getFirstAttemptAtUtc().minusSeconds(60))) return;
        if (row.getProviderMessageId() != null && !normalizeId(row.getProviderMessageId()).equals(messageId)) return;
        if (row.getDeliveryEventAtUtc() != null) {
            if (occurred.isBefore(row.getDeliveryEventAtUtc())) return;
            if (occurred.equals(row.getDeliveryEventAtUtc()) && rank(state) <= rank(row.getDeliveryStatus())) return;
        }
        if (row.getDeliveryStatus() != null && TERMINAL_FAILURE.contains(row.getDeliveryStatus())
                && !TERMINAL_FAILURE.contains(state)) return;
        if ("DELIVERED".equals(row.getDeliveryStatus()) && rank(state) < rank("DELIVERED")) return;
        if ("ACCEPTED".equals(state) && rank(row.getDeliveryStatus()) > rank(state)) return;
        row.setProviderMessageId(messageId);
        row.setDeliveryStatus(state);
        row.setDeliveryEventAtUtc(occurred);
        row.setStatus(AuditNotificationStatus.SENT); // provider accepted, even if final delivery bounced
        if (row.getSentAtUtc() == null) row.setSentAtUtc(occurred);
        row.setAcceptanceUncertain(false);
        row.setLastError(TERMINAL_FAILURE.contains(state) ? "Provider delivery outcome: " + state : null);
        row.setUpdatedAtUtc(Instant.now());
        repository.save(row);
    }

    private static int rank(String state) {
        if (state == null) return 0;
        if (TERMINAL_FAILURE.contains(state)) return 4;
        return switch (state) { case "DELIVERED" -> 3; case "DEFERRED", "SOFT_BOUNCE" -> 2; default -> 1; };
    }

    private static String normalizeId(String id) {
        String value = id.trim();
        return value.startsWith("<") && value.endsWith(">") ? value.substring(1, value.length() - 1) : value;
    }
}
