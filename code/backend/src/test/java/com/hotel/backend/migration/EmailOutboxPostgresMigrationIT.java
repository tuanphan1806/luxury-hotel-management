package com.hotel.backend.migration;

import com.hotel.backend.entity.*;
import com.hotel.backend.event.GuestBookingCreatedEvent;
import com.hotel.backend.repository.*;
import com.hotel.backend.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.hikari.maximum-pool-size=12"})
@ActiveProfiles("test")
class EmailOutboxPostgresMigrationIT {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("email_outbox_test").withUsername("hotel").withPassword("hotel");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
    @Autowired AuditNotificationOutboxStore store;
    @Autowired AuditNotificationOutboxRepository outbox;
    @Autowired CustomerProfileRepository customers;
    @Autowired ReservationRepository reservations;
    @Autowired RoomTypeRepository roomTypes;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean(name = "transactionalEmailDeliveryGateway") EmailDeliveryGateway gateway;

    @Test void concurrentWorkersClaimBookingOnceAndDatabaseEnforcesUniqueness() throws Exception {
        when(gateway.idempotencyScope()).thenReturn("brevo:test-only");
        long reservationId = new TransactionTemplate(transactions).execute(status -> {
            var customer = customers.saveAndFlush(CustomerProfile.builder().fullName("Test guest").build());
            var booking = Reservation.builder().reservationCode("MAIL-" + UUID.randomUUID())
                    .customerProfile(customer).checkIn(LocalDateTime.now().plusDays(2)).checkOut(LocalDateTime.now().plusDays(3))
                    .totalAmount(BigDecimal.valueOf(100000)).build();
            var roomType = roomTypes.saveAndFlush(RoomType.builder().code("EMAIL" + UUID.randomUUID().toString().substring(0, 8))
                    .typeName("Email test room").maxGuests(2).build());
            booking.getRoomTypes().add(ReservationRoomType.builder().reservation(booking).roomType(roomType)
                    .quantity(1).roomPrice(BigDecimal.valueOf(100000)).subtotal(BigDecimal.valueOf(100000)).build());
            reservations.saveAndFlush(booking);
            var event = new GuestBookingCreatedEvent(booking.getId(), "guest@example.test", "test-only-token");
            events.publishEvent(event);
            events.publishEvent(event);
            return booking.getId();
        });
        var rows = outbox.findAll().stream().filter(row -> Long.valueOf(reservationId).equals(row.getReservationId())).toList();
        assertThat(rows).hasSize(1);
        long id = rows.get(0).getId();
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        List<Future<AuditNotificationOutboxStore.Delivery>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return store.claim(id);
            }));
            start.countDown();
            List<AuditNotificationOutboxStore.Delivery> claimed = new ArrayList<>();
            for (var future : futures) {
                var result = future.get(20, TimeUnit.SECONDS);
                if (result != null) claimed.add(result);
            }
            assertThat(claimed).hasSize(1);
            store.markSent(claimed.get(0), new EmailDeliveryGateway.Receipt("postgres-provider-id", false));
            assertThat(outbox.findById(id).orElseThrow().getProviderMessageId()).isEqualTo("postgres-provider-id");
        } finally { pool.shutdownNow(); }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.prepareStatement("""
                INSERT INTO audit_notification_outbox (reservation_id, notification_type, recipient_email, payload_json)
                VALUES (?, 'BOOKING_CONFIRMATION', 'different@example.test', '{}'::jsonb)
                """)) {
            statement.setLong(1, reservationId);
            assertThatThrownBy(statement::executeUpdate).isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("uk_booking_email_delivery");
        }
        verify(gateway, never()).sendHtml(any(), any(), any(), any(), any(), any(), any());
    }
}
