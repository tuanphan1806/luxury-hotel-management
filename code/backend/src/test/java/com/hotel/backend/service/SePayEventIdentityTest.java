package com.hotel.backend.service;

import com.hotel.backend.config.SePayConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SePayEventIdentityTest {

    private SePayConfig config;
    private SePayEventIdentity identity;

    @BeforeEach
    void setUp() {
        config = new SePayConfig();
        identity = new SePayEventIdentity(config);
    }

    @Test
    void configuredBankAccountIdOverridesHashedAccountIdentity() {
        String hashed = identity.merchantAccountId("1000 4712 857");
        assertTrue(hashed.startsWith("acct:"));

        config.setApiBankAccountId(" bank-account-uuid ");
        assertEquals("bank-account-uuid", identity.merchantAccountId("different-account"));
    }

    @Test
    void canonicalDedupKeyKeepsEventThenTransactionThenPayloadPrecedence() {
        String eventKey = identity.canonicalDedupKey(
                "merchant", "reference", "event-1", "transaction-1", "payload",
                "in", 50_000L, "2026-07-23 22:00:00", "LP1");
        String transactionKey = identity.canonicalDedupKey(
                "merchant", "reference", "missing-payload", "transaction-1", "payload",
                "in", 50_000L, "2026-07-23 22:00:00", "LP1");
        String payloadKey = identity.canonicalDedupKey(
                "merchant", "reference", "missing-payload", null, "payload",
                "in", 50_000L, "2026-07-23 22:00:00", "LP1");

        assertTrue(eventKey.startsWith("event:"));
        assertTrue(transactionKey.startsWith("txn:"));
        assertTrue(payloadKey.startsWith("payload:"));
        assertNotEquals(eventKey, transactionKey);
        assertNotEquals(transactionKey, payloadKey);
    }

    @Test
    void stableReferenceIsScopedAndFingerprintFallbackIsDeterministic() {
        String scoped = identity.stableProviderReference(
                "BANK-REF-1", "10004712857", "in", 50_000L, 50_000L,
                "2026-07-23T22:00:00", "LP1");
        String sameScoped = identity.stableProviderReference(
                "BANK-REF-1", "1000 4712 857", "in", 50_000L, 0L,
                "2026-07-23 22:00:00.999", "different content");
        String fingerprint = identity.stableProviderReference(
                null, "10004712857", "in", 50_000L, 50_000L,
                "2026-07-23 22:00:00", "  lp1  ");

        assertEquals(scoped, sameScoped);
        assertTrue(scoped.startsWith("SEPAY-REF-"));
        assertTrue(fingerprint.startsWith("SEPAY-FP-"));
    }

    @Test
    void providerTimeParserPreservesUtcOffsetAndHotelLocalFormats() {
        assertEquals(Instant.parse("2026-07-23T15:00:00Z"),
                identity.parseProviderOccurredAt("2026-07-23T15:00:00Z"));
        assertEquals(Instant.parse("2026-07-23T15:00:00Z"),
                identity.parseProviderOccurredAt("2026-07-23T22:00:00+07:00"));
        assertEquals(Instant.parse("2026-07-23T15:00:00Z"),
                identity.parseProviderOccurredAt("2026-07-23 22:00:00"));
        assertNull(identity.parseProviderOccurredAt("not-a-provider-time"));
    }
}
