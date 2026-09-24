package com.hotel.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.constant.PaymentProvider;
import com.hotel.backend.constant.PaymentProviderEventStatus;
import com.hotel.backend.dto.response.SePayApiTransaction;
import com.hotel.backend.repository.PaymentProviderEventRepository;
import com.hotel.backend.repository.PaymentRefundRepository;
import com.hotel.backend.repository.PaymentTransactionRepository;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.service.SePayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises real security filters, raw-body authentication and durable ingestion.
 * No test-level transaction: REQUIRES_NEW event writes must actually commit.
 * This dedicated in-memory database is dropped when this context is closed.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sepay-webhook-integration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.seed.master-data-enabled=false",
        "app.seed.demo-users-enabled=false",
        "sepay.merchant-bank-account=012345678901",
        "sepay.webhook-api-key=",
        "sepay.webhook-secret=local-sepay-route-test-only",
        "sepay.webhook-timestamp-tolerance-seconds=300"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SePayWebhookIntegrationTest {
    private static final String ROUTE = "/api/payments/sepay/webhook";
    private static final String SECRET = "local-sepay-route-test-only";
    private static final String ACCOUNT = "012345678901";
    private static final AtomicLong IDS = new AtomicLong(8_900_000);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PaymentProviderEventRepository events;
    @Autowired PaymentTransactionRepository payments;
    @Autowired PaymentRefundRepository refunds;
    @Autowired ReservationRepository reservations;
    @Autowired SePayService sePayService;

    @Test
    void unsignedRequestIsRejectedBeforeAnyFinancialWrite() throws Exception {
        Counts before = counts();
        mvc.perform(post(ROUTE).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        assertEquals(before, counts());
    }

    @Test
    void tamperedRawBodyCannotReuseValidSignature() throws Exception {
        Counts before = counts();
        byte[] body = mapper.writeValueAsBytes(transfer(ACCOUNT));
        String timestamp = timestamp();
        // Even semantically equivalent JSON must have a signature over its exact bytes.
        byte[] tampered = (new String(body, StandardCharsets.UTF_8) + " ")
                .getBytes(StandardCharsets.UTF_8);
        mvc.perform(post(ROUTE).contentType("application/json").content(tampered)
                        .header("X-SePay-Timestamp", timestamp)
                        .header("X-SePay-Signature", sign(body, timestamp)))
                .andExpect(status().isUnauthorized());
        assertEquals(before, counts());
    }

    @Test
    void expiredSignedRequestIsRejectedBeforeIngestion() throws Exception {
        Counts before = counts();
        signed(mapper.writeValueAsBytes(transfer(ACCOUNT)),
                Long.toString(Instant.now().minusSeconds(600).getEpochSecond()))
                .andExpect(status().isUnauthorized());
        assertEquals(before, counts());
    }

    @Test
    void validSignatureCannotBypassMerchantAccountValidation() throws Exception {
        Counts before = counts();
        signed(mapper.writeValueAsBytes(transfer("999999999999")), timestamp())
                .andExpect(status().isBadRequest());
        assertEquals(before, counts());
    }

    @Test
    void signedProviderConnectivityProbeDoesNotCreateFinancialRecords() throws Exception {
        Counts before = counts();
        Map<String, Object> probe = transfer("0000000000");
        probe.put("id", 0);
        probe.put("gateway", "SePay");
        probe.put("code", "SEPAYTEST");
        probe.put("content", "SEPAY TEST WEBHOOK");
        probe.put("description", "SePay test webhook delivery");
        probe.put("referenceCode", "TEST-CONTROLLED-CONNECTIVITY");
        signed(mapper.writeValueAsBytes(probe), timestamp())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertEquals(before, counts());
    }

    @Test
    void signedUnmatchedTransferAndDuplicatePersistExactlyOneReviewEvent() throws Exception {
        Counts before = counts();
        Map<String, Object> transfer = transfer(ACCOUNT);
        byte[] body = mapper.writeValueAsBytes(transfer);
        signed(body, timestamp()).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        var first = events.findByProviderAndProviderEventId(
                PaymentProvider.SEPAY, transfer.get("id").toString()).orElseThrow();
        assertEquals(PaymentProviderEventStatus.REVIEW_REQUIRED, first.getStatus());
        assertEquals(10_000L, first.getAmount());
        assertNotNull(first.getProviderOccurredAtUtc());
        assertNull(first.getPaymentTransaction());

        signed(body, timestamp()).andExpect(status().isOk());
        var replayed = events.findById(first.getId()).orElseThrow();
        assertEquals(first.getProcessingAttempts(), replayed.getProcessingAttempts());
        assertEquals(first.getVersion(), replayed.getVersion());
        assertEquals(new Counts(before.events() + 1, before.payments(),
                before.refunds(), before.reservations()), counts());
    }

    @Test
    void webhookThenReconciliationWithDifferentProviderIdDoesNotDuplicateReceipt() throws Exception {
        Counts before = counts();
        Map<String, Object> transfer = transfer(ACCOUNT);
        signed(mapper.writeValueAsBytes(transfer), timestamp()).andExpect(status().isOk());
        sePayService.reconcile(new SePayApiTransaction(
                "api-" + transfer.get("id"), transfer.get("transactionDate").toString(),
                ACCOUNT, "in", BigDecimal.valueOf(10_000), BigDecimal.ZERO,
                BigDecimal.valueOf(10_000), transfer.get("content").toString(),
                transfer.get("referenceCode").toString(), transfer.get("code").toString(), "NCB"));
        assertEquals(new Counts(before.events() + 1, before.payments(),
                before.refunds(), before.reservations()), counts());
        assertEquals(PaymentProviderEventStatus.REVIEW_REQUIRED,
                events.findByProviderAndProviderEventId(PaymentProvider.SEPAY,
                        transfer.get("id").toString()).orElseThrow().getStatus());
    }

    private Map<String, Object> transfer(String account) {
        long id = IDS.incrementAndGet();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("gateway", "NCB");
        body.put("transactionDate", LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        body.put("accountNumber", account);
        body.put("code", "LPTEST" + id);
        body.put("content", "LPTEST" + id);
        body.put("transferType", "in");
        body.put("transferAmount", 10_000);
        body.put("accumulated", 10_000);
        body.put("referenceCode", "LOCAL-ROUTE-TEST-" + id);
        return body;
    }

    private ResultActions signed(byte[] body, String timestamp) throws Exception {
        return mvc.perform(post(ROUTE).contentType("application/json").content(body)
                .header("X-SePay-Timestamp", timestamp)
                .header("X-SePay-Signature", sign(body, timestamp)));
    }

    private String timestamp() {
        return Long.toString(Instant.now().getEpochSecond());
    }

    private String sign(byte[] body, String timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private Counts counts() {
        return new Counts(events.count(), payments.count(), refunds.count(), reservations.count());
    }

    private record Counts(long events, long payments, long refunds, long reservations) {}
}
