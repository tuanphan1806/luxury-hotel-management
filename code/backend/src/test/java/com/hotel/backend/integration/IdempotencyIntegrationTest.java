package com.hotel.backend.integration;

import com.hotel.backend.constant.IdempotencyStatus;
import com.hotel.backend.entity.IdempotencyRequest;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.IdempotencyRequestRepository;
import com.hotel.backend.service.IdempotencyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class IdempotencyIntegrationTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyRequestRepository repository;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    void tenConcurrentRetriesExecuteFinancialActionOnlyOnce() throws Exception {
        int requestCount = 10;
        String key = UUID.randomUUID().toString();
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requestCount);
        List<Future<String>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < requestCount; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return execute(key, Map.of("reservationId", 42, "amount", 60000), () -> {
                        executions.incrementAndGet();
                        sleep(200);
                        return "payment-42";
                    });
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<String> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo("payment-42");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(executions).hasValue(1);
        assertThat(repository.findAll())
                .singleElement()
                .extracting(IdempotencyRequest::getStatus)
                .isEqualTo(IdempotencyStatus.COMPLETED);
    }

    @Test
    void domainFailureRollsBackBothSideEffectAndClaimThenAllowsRetry() {
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> execute(key, Map.of("amount", 60000), () -> {
            repository.saveAndFlush(IdempotencyRequest.builder()
                    .requestKey("simulated-financial-side-effect")
                    .operation("TEST_SIDE_EFFECT")
                    .actorScope("TEST:side-effect")
                    .requestHash("0".repeat(64))
                    .status(IdempotencyStatus.PROCESSING)
                    .expiresAtUtc(Instant.now().plusSeconds(60))
                    .build());
            throw new IllegalStateException("simulated failure");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated failure");

        assertThat(repository.count()).isZero();
        assertThat(execute(key, Map.of("amount", 60000), () -> "payment-after-retry"))
                .isEqualTo("payment-after-retry");
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void sameLogicalPayloadWithDifferentObjectKeyOrderReplaysResult() {
        String key = UUID.randomUUID().toString();
        AtomicInteger executions = new AtomicInteger();
        Map<String, Object> firstPayload = new LinkedHashMap<>();
        firstPayload.put("reservationId", 42);
        firstPayload.put("amount", 60000);
        Map<String, Object> reorderedPayload = new LinkedHashMap<>();
        reorderedPayload.put("amount", 60000);
        reorderedPayload.put("reservationId", 42);

        assertThat(execute(key, firstPayload, () -> {
            executions.incrementAndGet();
            return "payment-42";
        })).isEqualTo("payment-42");
        assertThat(execute(key, reorderedPayload, () -> {
            executions.incrementAndGet();
            return "must-not-run";
        })).isEqualTo("payment-42");

        assertThat(executions).hasValue(1);
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejectedWithoutSecondSideEffect() {
        String key = UUID.randomUUID().toString();
        AtomicInteger executions = new AtomicInteger();

        execute(key, Map.of("amount", 60000), () -> {
            executions.incrementAndGet();
            return "payment-42";
        });

        assertThatThrownBy(() -> execute(key, Map.of("amount", 120000), () -> {
            executions.incrementAndGet();
            return "double-charge";
        })).isInstanceOfSatisfying(AppException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
        assertThat(executions).hasValue(1);
    }

    private String execute(String key, Object payload, java.util.function.Supplier<String> action) {
        return idempotencyService.execute(
                key,
                "TEST_FINANCIAL_COMMAND",
                "TEST:customer-1",
                payload,
                "PAYMENT_TRANSACTION",
                action,
                value -> value,
                resourceId -> resourceId);
    }

    private void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while simulating financial work", exception);
        }
    }
}
