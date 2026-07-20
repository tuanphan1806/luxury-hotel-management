package com.hotel.backend.migration;

import com.hotel.backend.constant.CleaningStatus;
import com.hotel.backend.constant.RoomStatus;
import com.hotel.backend.entity.Room;
import com.hotel.backend.entity.RoomType;
import com.hotel.backend.repository.IdempotencyRequestRepository;
import com.hotel.backend.repository.InvalidatedTokenRepository;
import com.hotel.backend.repository.RoomRepository;
import com.hotel.backend.repository.RoomTypeRepository;
import com.hotel.backend.service.IdempotencyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Date;
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

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.hikari.maximum-pool-size=12"
})
@ActiveProfiles("test")
class IdempotencyPostgresMigrationIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("hotelmanagement_idempotency")
            .withUsername("hotel")
            .withPassword("hotel");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyRequestRepository repository;

    @Autowired
    private InvalidatedTokenRepository invalidatedTokenRepository;

    @Autowired
    private RoomTypeRepository roomTypeRepository;

    @Autowired
    private RoomRepository roomRepository;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    void tenConcurrentRetriesExecuteOnlyOnceOnPostgres16() throws Exception {
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
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return idempotencyService.execute(
                            key,
                            "POSTGRES_FINANCIAL_COMMAND",
                            "TEST:customer-1",
                            Map.of("reservationId", 42, "amount", 60000),
                            "PAYMENT_TRANSACTION",
                            () -> {
                                executions.incrementAndGet();
                                sleep(250);
                                return "postgres-payment-42";
                            },
                            value -> value,
                            resourceId -> resourceId);
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<String> future : futures) {
                assertThat(future.get(20, TimeUnit.SECONDS))
                        .isEqualTo("postgres-payment-42");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(executions).hasValue(1);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @Transactional
    void nativeInsertAndEnumSearchQueriesRunOnPostgres16() {
        String token = "postgres-native-" + UUID.randomUUID();
        assertThat(invalidatedTokenRepository.insertInvalidatedToken(
                token,
                new Date(System.currentTimeMillis() + 60_000),
                "TEST"))
                .isEqualTo(1);
        assertThat(invalidatedTokenRepository.findByToken(token)).isPresent();

        RoomType roomType = roomTypeRepository.saveAndFlush(RoomType.builder()
                .typeName("PostgreSQL Deluxe")
                .maxGuests(2)
                .build());
        Room room = roomRepository.saveAndFlush(Room.builder()
                .roomName("PG-" + UUID.randomUUID().toString().substring(0, 8))
                .roomType(roomType)
                .status(RoomStatus.MAINTENANCE)
                .cleaningStatus(CleaningStatus.DIRTY)
                .sellable(true)
                .build());

        assertThat(roomRepository.searchByKeyword("%maintenance%", PageRequest.of(0, 10)))
                .extracting(Room::getId)
                .contains(room.getId());
        assertThat(roomRepository.searchByKeyword("%dirty%", PageRequest.of(0, 10)))
                .extracting(Room::getId)
                .contains(room.getId());
        assertThat(roomRepository.search(null, RoomStatus.MAINTENANCE, CleaningStatus.DIRTY))
                .extracting(Room::getId)
                .contains(room.getId());
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
