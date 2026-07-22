package com.hotel.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.hotel.backend.constant.AuditNotificationStatus;
import com.hotel.backend.constant.AuditScope;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.response.ReservationAuditLogResponse;
import com.hotel.backend.entity.AuditNotificationOutbox;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.ReservationAuditLog;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.AuditNotificationOutboxRepository;
import com.hotel.backend.repository.ReservationAuditLogRepository;
import com.hotel.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Arrays;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReservationAuditService {
    private static final String ALERT_TYPE = "HIGH_RISK_AUDIT";
    private static final Set<ReservationAuditAction> HIDDEN_AUDIT_ACTIONS = Set.of(
            ReservationAuditAction.LOGIN_SUCCESS,
            ReservationAuditAction.LOGOUT,
            ReservationAuditAction.PASSWORD_CHANGED,
            ReservationAuditAction.PASSWORD_RESET_COMPLETED,
            // Opening/printing an immutable invoice is a read-only convenience,
            // not an operation or management mutation worth surfacing here.
            ReservationAuditAction.PRINT_INVOICE);

    private final ReservationAuditLogRepository repository;
    private final AuditNotificationOutboxRepository outboxRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.audit-alert.recipients:}")
    private String configuredAlertRecipients;

    @Transactional
    public ReservationAuditLog record(
            Reservation reservation,
            ReservationAuditAction action,
            String details) {
        return record(reservation, "RESERVATION", String.valueOf(reservation.getId()), action,
                details, null, null, null, null, null, null);
    }

    @Transactional
    public ReservationAuditLog record(
            Reservation reservation,
            String targetType,
            String targetId,
            ReservationAuditAction action,
            String details,
            Map<String, ?> oldValue,
            Map<String, ?> newValue,
            Map<String, ?> detail,
            String correlationId,
            String dedupKey) {
        return record(reservation, targetType, targetId, action, details,
                oldValue, newValue, detail, correlationId, dedupKey, null);
    }

    @Transactional
    public ReservationAuditLog recordSystem(
            Reservation reservation,
            String targetType,
            String targetId,
            ReservationAuditAction action,
            String details,
            Map<String, ?> oldValue,
            Map<String, ?> newValue,
            Map<String, ?> detail,
            String correlationId,
            String dedupKey) {
        return record(reservation, targetType, targetId, action, details,
                oldValue, newValue, detail, correlationId, dedupKey, Actor.system());
    }

    @Transactional
    public ReservationAuditLog recordTarget(
            String targetType,
            String targetId,
            ReservationAuditAction action,
            String details) {
        return record(null, targetType, targetId, action, details,
                null, null, null, null, null, null);
    }

    @Transactional
    public ReservationAuditLog recordTarget(
            String targetType,
            String targetId,
            ReservationAuditAction action,
            String details,
            Map<String, ?> oldValue,
            Map<String, ?> newValue,
            Map<String, ?> detail,
            String correlationId,
            String dedupKey) {
        return record(null, targetType, targetId, action, details,
                oldValue, newValue, detail, correlationId, dedupKey, null);
    }

    @Transactional
    public ReservationAuditLog recordTargetForUser(
            User actorUser,
            String targetType,
            String targetId,
            ReservationAuditAction action,
            String details,
            Map<String, ?> detail) {
        Actor actor = actorUser == null
                ? Actor.system()
                : new Actor(actorUser.getId(), displayName(actorUser), actorUser.getType().name());
        return record(null, targetType, targetId, action, details,
                null, null, detail, null, null, actor);
    }

    private ReservationAuditLog record(
            Reservation reservation,
            String targetType,
            String targetId,
            ReservationAuditAction action,
            String details,
            Map<String, ?> oldValue,
            Map<String, ?> newValue,
            Map<String, ?> detail,
            String correlationId,
            String dedupKey,
            Actor actorOverride) {
        if (hasText(dedupKey) && repository.existsByDedupKey(dedupKey)) {
            return null;
        }

        Actor actor = actorOverride != null ? actorOverride : currentActor();
        Instant occurredAt = Instant.now();
        ReservationAuditLog audit = ReservationAuditLog.builder()
                .reservationId(reservation != null ? reservation.getId() : null)
                .reservationCode(reservation != null ? reservation.getReservationCode() : null)
                .targetType(targetType)
                .targetId(targetId)
                .action(action)
                .actionCode(action.name())
                .actorUserId(actor.userId())
                .actorName(actor.name())
                .actorRole(actor.role())
                .correlationId(correlationId)
                .details(sanitizeDetails(details))
                .oldValueJson(toObjectNode(oldValue))
                .newValueJson(toObjectNode(newValue))
                .detailJson(toObjectNode(detail))
                .riskLevel(action.riskLevel())
                .category(action.category())
                .dedupKey(hasText(dedupKey) ? dedupKey.trim() : null)
                .occurredAtUtc(occurredAt)
                .build();
        try {
            audit = repository.save(audit);
        } catch (DataIntegrityViolationException duplicate) {
            if (hasText(dedupKey) && repository.existsByDedupKey(dedupKey)) {
                return null;
            }
            throw duplicate;
        }
        if (action.requiresAlert()) enqueueAlert(audit);
        return audit;
    }

    private void enqueueAlert(ReservationAuditLog audit) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("auditId", audit.getId());
        payload.put("action", audit.getActionCode());
        payload.put("riskLevel", audit.getRiskLevel().name());
        payload.put("targetType", audit.getTargetType());
        payload.put("targetId", audit.getTargetId());
        putIfText(payload, "reservationCode", audit.getReservationCode());
        putIfText(payload, "actorName", audit.getActorName());
        putIfText(payload, "actorRole", audit.getActorRole());
        putIfText(payload, "correlationId", audit.getCorrelationId());
        putIfText(payload, "details", audit.getDetails());
        payload.put("occurredAtUtc", audit.getOccurredAtUtc().toString());
        Instant now = Instant.now();
        for (String recipient : alertRecipients()) {
            if (outboxRepository.existsByAuditLogIdAndNotificationTypeAndRecipientEmail(
                    audit.getId(), ALERT_TYPE, recipient)) continue;
            outboxRepository.save(AuditNotificationOutbox.builder()
                    .auditLog(audit)
                    .notificationType(ALERT_TYPE)
                    .recipientEmail(recipient)
                    .status(AuditNotificationStatus.PENDING)
                    .payloadJson(payload.deepCopy())
                    .attempts(0)
                    .nextAttemptAtUtc(now)
                    .createdAtUtc(now)
                    .updatedAtUtc(now)
                    .build());
        }
    }

    private List<String> alertRecipients() {
        LinkedHashSet<String> recipients = new LinkedHashSet<>();
        if (hasText(configuredAlertRecipients)) {
            Arrays.stream(configuredAlertRecipients.split(","))
                    .map(String::trim)
                    .filter(this::looksLikeEmail)
                    .forEach(recipients::add);
        }
        if (recipients.isEmpty()) {
            userRepository.findByTypeAndStatus(UserType.ADMIN, UserStatus.ACTIVE).stream()
                    .map(User::getEmail)
                    .filter(this::looksLikeEmail)
                    .map(String::trim)
                    .forEach(recipients::add);
        }
        return recipients.stream().toList();
    }

    private boolean looksLikeEmail(String value) {
        return hasText(value) && value.length() <= 255 && value.contains("@");
    }

    @Transactional(readOnly = true)
    public List<ReservationAuditLogResponse> getByReservation(Long reservationId) {
        return repository.findByReservationIdOrderByOccurredAtUtcDescIdDesc(reservationId).stream()
                .filter(log -> log.getAction() == null || !HIDDEN_AUDIT_ACTIONS.contains(log.getAction()))
                .map(ReservationAuditLogResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<ReservationAuditLogResponse> search(
            String targetType,
            String targetId,
            String actor,
            String actorRole,
            ReservationAuditAction action,
            com.hotel.backend.constant.AuditCategory category,
            AuditScope scope,
            com.hotel.backend.constant.AuditRiskLevel riskLevel,
            Instant from,
            Instant to,
            int page,
            int size) {
        Specification<ReservationAuditLog> specification =
                (root, query, builder) -> builder.conjunction();
        // Audit pages are for operation/management mutations. Historical
        // routine authentication and read-only invoice rows remain append-only
        // in the database but are intentionally excluded from dashboard queries.
        specification = specification.and((root, query, builder) ->
                builder.not(root.get("action").in(HIDDEN_AUDIT_ACTIONS)));
        if (hasText(targetType)) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("targetType"), targetType.trim().toUpperCase()));
        }
        if (hasText(targetId)) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("targetId"), targetId.trim()));
        }
        if (hasText(actor)) {
            String pattern = "%" + actor.trim().toLowerCase() + "%";
            specification = specification.and((root, query, builder) ->
                    builder.like(builder.lower(root.get("actorName")), pattern));
        }
        if (hasText(actorRole)) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("actorRole"), actorRole.trim().toUpperCase()));
        }
        if (action != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("action"), action));
        }
        if (category != null) {
            List<ReservationAuditAction> actionsInCategory = Arrays.stream(ReservationAuditAction.values())
                    .filter(candidate -> candidate.category() == category)
                    .toList();
            specification = specification.and((root, query, builder) ->
                    root.get("action").in(actionsInCategory));
        }
        if (scope != null) {
            List<ReservationAuditAction> actionsInScope = Arrays.stream(ReservationAuditAction.values())
                    .filter(candidate -> candidate.scope() == scope)
                    .toList();
            specification = specification.and((root, query, builder) ->
                    root.get("action").in(actionsInScope));
        }
        if (riskLevel != null) {
            List<ReservationAuditAction> actionsAtRiskLevel = Arrays.stream(ReservationAuditAction.values())
                    .filter(candidate -> candidate.riskLevel() == riskLevel)
                    .toList();
            specification = specification.and((root, query, builder) ->
                    root.get("action").in(actionsAtRiskLevel));
        }
        if (from != null) {
            specification = specification.and((root, query, builder) ->
                    builder.greaterThanOrEqualTo(root.get("occurredAtUtc"), from));
        }
        if (to != null) {
            specification = specification.and((root, query, builder) ->
                    builder.lessThan(root.get("occurredAtUtc"), to));
        }
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), 100),
                Sort.by(Sort.Order.desc("occurredAtUtc"), Sort.Order.desc("id")));
        return repository.findAll(specification, pageable).map(ReservationAuditLogResponse::from);
    }

    @Transactional(readOnly = true)
    public List<String> findActors(String query) {
        Pageable limit = PageRequest.of(0, 20);
        return hasText(query)
                ? repository.searchActorNames(query.trim(), limit)
                : repository.findActorNames(limit);
    }

    private Actor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        User user = authentication != null && authentication.getPrincipal() instanceof User current
                ? current : null;
        return user != null
                ? new Actor(user.getId(), displayName(user), user.getType().name())
                : Actor.system();
    }

    private String displayName(User user) {
        if (hasText(user.getFullName())) return user.getFullName().trim();
        if (hasText(user.getUsername())) return user.getUsername().trim();
        return "Người dùng #" + user.getId();
    }

    private JsonNode toObjectNode(Map<String, ?> value) {
        if (value == null || value.isEmpty()) return null;
        return sanitizeNode(null, objectMapper.valueToTree(value));
    }

    private JsonNode sanitizeNode(String fieldName, JsonNode node) {
        if (node == null || node.isNull()) return node;
        String normalizedField = fieldName == null ? "" : fieldName.toLowerCase();
        if (normalizedField.matches(".*(authorization|password|token|secret).*")) {
            return objectMapper.getNodeFactory().textNode("[REDACTED]");
        }
        if (node.isTextual() && normalizedField.matches(
                ".*(accountnumber|account_number|bankaccount|bank_account|merchantaccount|merchant_account).*")) {
            String raw = node.asText().replaceAll("\\s+", "");
            String suffix = raw.length() <= 4 ? raw : raw.substring(raw.length() - 4);
            return objectMapper.getNodeFactory().textNode("****" + suffix);
        }
        if (node.isObject()) {
            ObjectNode sanitized = objectMapper.createObjectNode();
            node.properties().forEach(entry -> sanitized.set(
                    entry.getKey(), sanitizeNode(entry.getKey(), entry.getValue())));
            return sanitized;
        }
        if (node.isArray()) {
            ArrayNode sanitized = objectMapper.createArrayNode();
            node.forEach(item -> sanitized.add(sanitizeNode(fieldName, item)));
            return sanitized;
        }
        return node.deepCopy();
    }

    private void putIfText(ObjectNode node, String name, String value) {
        if (hasText(value)) node.put(name, value);
    }

    private String sanitizeDetails(String details) {
        if (!hasText(details)) return details;
        String sanitized = details
                .replaceAll("(?i)(authorization|token|secret)\\s*[:=]\\s*[^,;\\s]+", "$1=[REDACTED]")
                .replaceAll("(?<!\\d)\\d{7,19}(?!\\d)", "****");
        return sanitized.length() <= 2000 ? sanitized : sanitized.substring(0, 2000);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record Actor(Long userId, String name, String role) {
        private static Actor system() {
            return new Actor(null, "Hệ thống", "SYSTEM");
        }
    }
}
