package com.hotel.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.constant.AuditCategory;
import com.hotel.backend.constant.AuditRiskLevel;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.entity.AuditNotificationOutbox;
import com.hotel.backend.entity.ReservationAuditLog;
import com.hotel.backend.entity.User;
import com.hotel.backend.dto.response.ReservationAuditLogResponse;
import com.hotel.backend.repository.AuditNotificationOutboxRepository;
import com.hotel.backend.repository.ReservationAuditLogRepository;
import com.hotel.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationAuditServiceTest {
    @Mock ReservationAuditLogRepository auditRepository;
    @Mock AuditNotificationOutboxRepository outboxRepository;
    @Mock UserRepository userRepository;

    private ReservationAuditService service;

    @BeforeEach
    void setUp() {
        service = new ReservationAuditService(
                auditRepository, outboxRepository, userRepository, new ObjectMapper());
        ReflectionTestUtils.setField(service, "configuredAlertRecipients", "ops@example.com");
        User admin = User.builder()
                .fullName("Admin audit")
                .username("admin-audit")
                .email("admin@example.com")
                .type(UserType.ADMIN)
                .status(UserStatus.ACTIVE)
                .build();
        admin.setId(7L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        admin, null, admin.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void highRiskAuditCapturesActorMasksSensitiveDataAndCreatesOneOutboxRow() {
        stubAuditSave();
        when(auditRepository.existsByDedupKey("role-change:7")).thenReturn(false);
        when(outboxRepository.existsByAuditLogIdAndNotificationTypeAndRecipientEmail(
                91L, "HIGH_RISK_AUDIT", "ops@example.com")).thenReturn(false);

        ReservationAuditLog saved = service.recordTarget(
                "USER", "7", ReservationAuditAction.USER_ROLE_CHANGED,
                "Role changed; token=do-not-store; account 012345678901",
                Map.of("role", "STAFF"),
                Map.of("role", "ADMIN"),
                Map.of(
                        "accountNumber", "012345678901",
                        "authorizationToken", "very-secret"),
                "correlation-7", "role-change:7");

        assertEquals(7L, saved.getActorUserId());
        assertEquals("Admin audit", saved.getActorName());
        assertEquals("ADMIN", saved.getActorRole());
        assertFalse(saved.getDetails().contains("do-not-store"));
        assertFalse(saved.getDetails().contains("012345678901"));
        assertEquals("****8901", saved.getDetailJson().path("accountNumber").asText());
        assertEquals("[REDACTED]", saved.getDetailJson().path("authorizationToken").asText());

        ArgumentCaptor<AuditNotificationOutbox> outbox =
                ArgumentCaptor.forClass(AuditNotificationOutbox.class);
        verify(outboxRepository, times(1)).save(outbox.capture());
        assertEquals(91L, outbox.getValue().getAuditLog().getId());
        assertEquals("ops@example.com", outbox.getValue().getRecipientEmail());
        assertTrue(outbox.getValue().getPayloadJson().path("details").asText()
                .contains("[REDACTED]"));
    }

    @Test
    void schedulerDedupKeyDoesNotCreateSecondAuditOrAlert() {
        stubAuditSave();
        when(auditRepository.existsByDedupKey("scheduler:hold:11"))
                .thenReturn(false, true);
        when(outboxRepository.existsByAuditLogIdAndNotificationTypeAndRecipientEmail(
                91L, "HIGH_RISK_AUDIT", "ops@example.com")).thenReturn(false);

        ReservationAuditLog first = service.recordSystem(
                null, "ROOM_HOLD", "11",
                ReservationAuditAction.ROOM_HOLD_RELEASED_MANUALLY,
                "test", null, null, null, "corr", "scheduler:hold:11");
        ReservationAuditLog replay = service.recordSystem(
                null, "ROOM_HOLD", "11",
                ReservationAuditAction.ROOM_HOLD_RELEASED_MANUALLY,
                "test", null, null, null, "corr", "scheduler:hold:11");

        assertEquals("SYSTEM", first.getActorRole());
        assertNull(replay);
        verify(auditRepository, times(1)).save(any(ReservationAuditLog.class));
        verify(outboxRepository, times(1)).save(any(AuditNotificationOutbox.class));
    }

    @Test
    void responseDerivesCanonicalCategoryAndRiskWithoutRewritingLegacyAuditRow() {
        ReservationAuditLog legacyRow = ReservationAuditLog.builder()
                .action(ReservationAuditAction.PAYMENT_MARKED_PAID_MANUALLY)
                .category(AuditCategory.BUSINESS)
                .riskLevel(AuditRiskLevel.NORMAL)
                .build();

        ReservationAuditLogResponse response = ReservationAuditLogResponse.from(legacyRow);

        assertEquals(AuditCategory.PAYMENT, response.getCategory());
        assertEquals(AuditRiskLevel.HIGH, response.getRiskLevel());
        assertEquals(AuditCategory.BUSINESS, legacyRow.getCategory());
        assertEquals(AuditRiskLevel.NORMAL, legacyRow.getRiskLevel());
    }

    @Test
    void actorFilterWithoutKeywordUsesPostgresSafeQuery() {
        when(auditRepository.findActorNames(any(Pageable.class)))
                .thenReturn(List.of("Admin audit", "SYSTEM"));

        assertEquals(List.of("Admin audit", "SYSTEM"), service.findActors(null));

        verify(auditRepository).findActorNames(any(Pageable.class));
    }

    private void stubAuditSave() {
        when(auditRepository.save(any(ReservationAuditLog.class))).thenAnswer(invocation -> {
            ReservationAuditLog log = invocation.getArgument(0);
            log.setId(91L);
            return log;
        });
    }
}
