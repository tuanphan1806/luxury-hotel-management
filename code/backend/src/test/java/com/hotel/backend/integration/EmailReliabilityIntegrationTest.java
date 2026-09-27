package com.hotel.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.entity.*;
import com.hotel.backend.event.GuestBookingCreatedEvent;
import com.hotel.backend.repository.*;
import com.hotel.backend.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.email.brevo-webhook-secret=test-only-webhook-secret-32-characters-long")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailReliabilityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired CustomerProfileRepository customers;
    @Autowired ReservationRepository reservations;
    @Autowired RoomTypeRepository roomTypes;
    @Autowired AuditNotificationOutboxRepository outbox;
    @Autowired AuditNotificationOutboxStore store;
    @Autowired EmailOutboxCodec codec;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean(name = "verificationEmailDeliveryGateway") EmailDeliveryGateway verification;
    @MockitoBean(name = "transactionalEmailDeliveryGateway") EmailDeliveryGateway transactional;

    @Test void resetFailureHasUnknownAddressResponseAndRollsBackToken() throws Exception {
        User user = account(UserStatus.ACTIVE);
        user.setPasswordResetTokenHash("previous-reset-hash");
        user.setPasswordResetExpiresAt(LocalDateTime.now().plusHours(1).withNano(0));
        users.saveAndFlush(user);
        doThrow(new EmailDeliveryException(EmailDeliveryException.Kind.PERMANENT, "provider rejected"))
                .when(transactional).sendHtml(any(), any(), any(), any(), any(), any(), eq("password_reset"));
        String known = request("/auth/forgot-password", user.getEmail());
        String unknown = request("/auth/forgot-password", UUID.randomUUID() + "@example.test");
        var knownBody = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(known);
        var unknownBody = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(unknown);
        knownBody.remove("timestamp");
        unknownBody.remove("timestamp");
        assertThat(knownBody).isEqualTo(unknownBody);
        User restored = users.findById(user.getId()).orElseThrow();
        assertThat(restored.getPasswordResetTokenHash()).isEqualTo("previous-reset-hash");
        assertThat(restored.getPasswordResetExpiresAt()).isEqualTo(user.getPasswordResetExpiresAt());
    }

    @Test void verificationFailureHasUnknownAddressResponseAndPreservesExistingCode() throws Exception {
        User user = account(UserStatus.PENDING_VERIFICATION);
        user.setVerificationCode("previous-verification-hash");
        user.setVerificationExpiresAt(LocalDateTime.now().plusHours(1).withNano(0));
        users.saveAndFlush(user);
        doThrow(new EmailDeliveryException(EmailDeliveryException.Kind.AMBIGUOUS, "provider timeout"))
                .when(verification).sendHtml(any(), any(), any(), any(), any(), any(), eq("verification"));
        assertThat(mapper.readTree(request("/auth/resend-verification", user.getEmail())))
                .isEqualTo(mapper.readTree(request("/auth/resend-verification", UUID.randomUUID() + "@example.test")));
        User restored = users.findById(user.getId()).orElseThrow();
        assertThat(restored.getVerificationCode()).isEqualTo("previous-verification-hash");
        assertThat(restored.getVerificationExpiresAt()).isEqualTo(user.getVerificationExpiresAt());
    }

    @Test void bookingCommitQueuesExactlyOneEncryptedSnapshotWithoutCallingProvider() throws Exception {
        var tx = new TransactionTemplate(transactions);
        long id = tx.execute(status -> {
            Reservation reservation = reservation();
            var event = new GuestBookingCreatedEvent(reservation.getId(), "guest@example.test", "raw-capability-token");
            events.publishEvent(event);
            events.publishEvent(event);
            assertThat(outbox.existsByReservationId(reservation.getId())).isFalse();
            return reservation.getId();
        });
        var rows = outbox.findAll().stream().filter(row -> Long.valueOf(id).equals(row.getReservationId())).toList();
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.getEncryptedMessage()).doesNotContain("raw-capability-token");
        assertThat(row.getPayloadJson().toString()).doesNotContain("token");
        assertThat(codec.decode(row.getEncryptedMessage()).html()).contains("raw-capability-token");
        verifyNoInteractions(transactional, verification);
    }

    @Test void rolledBackBookingDoesNotLeaveAnEmailAndEnqueueRequiresTransaction() {
        long[] id = new long[1];
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            id[0] = reservation().getId();
            events.publishEvent(new GuestBookingCreatedEvent(id[0], "guest@example.test", "rollback-token"));
            status.setRollbackOnly();
        });
        assertThat(reservations.existsById(id[0])).isFalse();
        assertThat(outbox.existsByReservationId(id[0])).isFalse();
        assertThatThrownBy(() -> store.enqueueBooking(new GuestBookingCreatedEvent(1L, "guest@example.test", "token")))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test void ciphertextTamperingCannotProduceAnEmail() {
        var message = new EmailService.QueuedEmail("a", "b", "c", "d", "e", "secret", "html", "test");
        String encrypted = codec.encode(message);
        assertThat(codec.decode(encrypted)).isEqualTo(message);
        assertThat(codec.encode(message)).isNotEqualTo(encrypted);
        int at = encrypted.length() / 2;
        String tampered = encrypted.substring(0, at) + (encrypted.charAt(at) == 'A' ? "B" : "A") + encrypted.substring(at + 1);
        assertThatThrownBy(() -> codec.decode(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test void webhookRequiresItsOwnSecretAndRejectsOversizedOrMalformedPayloads() throws Exception {
        mvc.perform(post("/api/email/brevo/webhook").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/email/brevo/webhook").header("X-Brevo-Webhook-Token", "wrong").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/email/brevo/webhook")
                        .header("X-Brevo-Webhook-Token", "test-only-webhook-secret-32-characters-long").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/email/brevo/webhook")
                        .header("Authorization", "Bearer test-only-webhook-secret-32-characters-long").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/email/brevo/webhook")
                        .header("X-Brevo-Webhook-Token", "test-only-webhook-secret-32-characters-long").content("invalid-json"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/email/brevo/webhook")
                        .header("X-Brevo-Webhook-Token", "test-only-webhook-secret-32-characters-long").content("x".repeat(32769)))
                .andExpect(status().isPayloadTooLarge());
    }

    private User account(UserStatus status) {
        String suffix = UUID.randomUUID().toString();
        return users.saveAndFlush(User.builder().fullName("Email test").username("email-" + suffix)
                .email(suffix + "@example.test").status(status).emailVerified(status == UserStatus.ACTIVE).build());
    }

    private Reservation reservation() {
        CustomerProfile customer = customers.saveAndFlush(CustomerProfile.builder().fullName("Email guest").build());
        var booking = Reservation.builder().reservationCode("EMAIL-" + UUID.randomUUID())
                .customerProfile(customer).checkIn(LocalDateTime.now().plusDays(1)).checkOut(LocalDateTime.now().plusDays(2))
                .totalAmount(BigDecimal.valueOf(100000)).build();
        var roomType = roomTypes.saveAndFlush(RoomType.builder().code("EMAIL" + UUID.randomUUID().toString().substring(0, 8))
                .typeName("Email test room").maxGuests(2).build());
        booking.getRoomTypes().add(ReservationRoomType.builder().reservation(booking).roomType(roomType)
                .quantity(1).roomPrice(BigDecimal.valueOf(100000)).subtotal(BigDecimal.valueOf(100000)).build());
        return reservations.saveAndFlush(booking);
    }

    private String request(String path, String email) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("email", email))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
