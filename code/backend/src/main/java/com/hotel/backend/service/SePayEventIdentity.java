package com.hotel.backend.service;

import com.hotel.backend.config.SePayConfig;
import com.hotel.backend.constant.PaymentProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.HexFormat;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class SePayEventIdentity {

    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter PROVIDER_LOCAL_DATE_TIME =
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd HH:mm:ss")
                    .optionalStart()
                    .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
                    .optionalEnd()
                    .toFormatter();

    private final SePayConfig config;

    public String merchantAccountId(String accountNumber) {
        if (hasText(config.getApiBankAccountId())) {
            return config.getApiBankAccountId().trim();
        }
        String normalized = normalizeAccount(accountNumber);
        return "acct:" + sha256(normalized).substring(0, 32);
    }

    /** Canonical order required by the provider-event contract. */
    public String canonicalDedupKey(
            String merchantAccountId,
            String providerReference,
            String providerEventId,
            String providerTxnId,
            String payloadHash,
            String transferType,
            Long receivedAmount,
            String providerOccurredAt,
            String normalizedContent) {
        String namespace;
        String identity;
        if (hasText(providerEventId) && !providerEventId.startsWith("missing-")) {
            namespace = "event";
            identity = providerEventId.trim();
        } else if (hasText(providerTxnId)) {
            namespace = "txn";
            identity = providerTxnId.trim();
        } else {
            namespace = "payload";
            identity = sha256(String.join("|",
                    value(merchantAccountId),
                    value(transferType).trim().toLowerCase(Locale.ROOT),
                    String.valueOf(receivedAmount != null ? receivedAmount : 0L),
                    normalizeProviderDate(providerOccurredAt),
                    value(normalizedContent).trim().replaceAll("\\s+", " ")
                            .toUpperCase(Locale.ROOT)));
        }
        return namespace + ":" + sha256(String.join("|",
                PaymentProvider.SEPAY.name(),
                value(merchantAccountId),
                identity));
    }

    public String stableProviderReference(
            String providerReference,
            String accountNumber,
            String transferType,
            Long amount,
            Long accumulated,
            String transactionDate,
            String content) {
        if (hasText(providerReference)) {
            // SePay does not guarantee that a bank reference is globally unique.
            // Scope it with the receiving account and the transaction attributes
            // that are stable across webhook and API v2 reconciliation.
            String scopedReference = sha256(String.join("|",
                    normalizeAccount(accountNumber),
                    providerReference.trim().toUpperCase(Locale.ROOT),
                    value(transferType).trim().toLowerCase(Locale.ROOT),
                    String.valueOf(amount != null ? amount : 0L),
                    normalizeProviderDate(transactionDate)));
            return "SEPAY-REF-" + scopedReference.substring(0, 48);
        }
        String fingerprint = sha256(String.join("|",
                normalizeAccount(accountNumber),
                String.valueOf(amount != null ? amount : 0L),
                String.valueOf(accumulated != null ? accumulated : 0L),
                normalizeProviderDate(transactionDate),
                value(content).trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT)));
        return "SEPAY-FP-" + fingerprint.substring(0, 48);
    }

    public Instant parseProviderOccurredAt(String rawValue) {
        if (!hasText(rawValue)) return null;
        String trimmed = rawValue.trim();
        try {
            return Instant.parse(trimmed);
        } catch (Exception ignored) {
            // Continue with provider formats below.
        }
        try {
            return OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toInstant();
        } catch (Exception ignored) {
            // Continue with SePay local bank time below.
        }
        try {
            String localValue = trimmed.replace('T', ' ');
            return LocalDateTime.parse(localValue, PROVIDER_LOCAL_DATE_TIME)
                    .atZone(HOTEL_ZONE)
                    .toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    public String normalizeAccount(String value) {
        return value(value).replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    public String sha256(String value) {
        return sha256(value(value).getBytes(StandardCharsets.UTF_8));
    }

    public String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value != null ? value : new byte[0]));
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể tạo hash sự kiện SePay", exception);
        }
    }

    private String normalizeProviderDate(String value) {
        String normalized = value(value).trim().replace('T', ' ');
        return normalized.length() > 19 ? normalized.substring(0, 19) : normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String value(String value) {
        return value != null ? value : "";
    }
}
