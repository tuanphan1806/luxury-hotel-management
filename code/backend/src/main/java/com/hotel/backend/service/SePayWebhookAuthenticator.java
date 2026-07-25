package com.hotel.backend.service;

import com.hotel.backend.config.SePayConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

@Slf4j(topic = "SEPAY-WEBHOOK-AUTH")
@Component
@RequiredArgsConstructor
public class SePayWebhookAuthenticator {

    private final SePayConfig config;

    public boolean verifySignature(byte[] rawBody, String signature, String timestampValue) {
        if (!hasText(config.getWebhookSecret()) || rawBody == null || rawBody.length == 0
                || !hasText(signature) || !hasText(timestampValue)) {
            return false;
        }
        try {
            long timestamp = Long.parseLong(timestampValue);
            long now = Instant.now().getEpochSecond();
            long tolerance = Math.min(3_600L,
                    Math.max(0L, config.getWebhookTimestampToleranceSeconds()));
            if (timestamp < now - tolerance || timestamp > now + tolerance) {
                return false;
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.getWebhookSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            mac.update((timestampValue + ".").getBytes(StandardCharsets.US_ASCII));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exception) {
            log.warn("Không thể xác thực chữ ký webhook SePay: {}", exception.getMessage());
            return false;
        }
    }

    /**
     * HMAC-SHA256 is the recommended webhook contract. API Key remains a
     * supported alternative. When an API Key is configured, never downgrade to
     * HMAC after a bad Authorization header because that would weaken the
     * explicitly selected authentication mode.
     */
    public boolean verifyAuthentication(
            byte[] rawBody,
            String authorization,
            String signature,
            String timestampValue) {
        if (hasText(config.getWebhookApiKey())) {
            if (!hasText(authorization)
                    || !authorization.regionMatches(true, 0, "Apikey ", 0, 7)) {
                return false;
            }
            byte[] expected = config.getWebhookApiKey().trim().getBytes(StandardCharsets.UTF_8);
            byte[] presented = authorization.substring(7).trim().getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, presented);
        }
        return verifySignature(rawBody, signature, timestampValue);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
