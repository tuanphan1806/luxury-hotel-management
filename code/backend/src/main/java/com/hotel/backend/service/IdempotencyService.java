package com.hotel.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final int MAX_CLAIM_ATTEMPTS = 10;

    private final IdempotencyTransactionExecutor transactionExecutor;
    private final ObjectMapper objectMapper;
    private final BusinessMetricService businessMetrics;

    public <T> T execute(
            String requestKey,
            String operation,
            String actorScope,
            Object requestPayload,
            String resourceType,
            Supplier<T> action,
            Function<T, String> resourceId,
            Function<String, T> replay) {
        validate(requestKey, operation, actorScope);
        String normalizedKey = requestKey.trim();
        String requestHash = hash(requestPayload);
        for (int attempt = 1; attempt <= MAX_CLAIM_ATTEMPTS; attempt++) {
            try {
                return transactionExecutor.execute(
                        normalizedKey,
                        operation,
                        actorScope,
                        requestHash,
                        resourceType,
                        action,
                        resourceId,
                        replay);
            } catch (IdempotencyStore.ClaimConflictException race) {
                businessMetrics.increment(
                        "hotel.idempotency.claim.conflicts",
                        "operation", businessMetrics.outcomeTag(operation));
                // A concurrent request won the unique key. Its transaction has
                // resolved before the database reports the collision, so retry
                // in a new transaction and replay the committed resource.
                if (attempt == MAX_CLAIM_ATTEMPTS) {
                    throw new AppException(ErrorCode.DUPLICATE_RESOURCE,
                            "Yêu cầu cùng idempotency key đang được xử lý");
                }
            }
        }
        throw new IllegalStateException("Unreachable idempotency execution state");
    }

    public String actorScope(User user, String guestToken) {
        if (user != null && user.getId() != null) {
            return user.getType().name() + ":" + user.getId();
        }
        if (guestToken != null && !guestToken.isBlank()) {
            return "GUEST_SESSION:" + sha256(guestToken).substring(0, 32);
        }
        throw new AppException(ErrorCode.INVALID_REQUEST,
                "Không xác định được actor scope cho idempotency");
    }

    /**
     * Creates a stable pre-reservation guest capability from the client key.
     * Only the derived SHA-256 value is stored on the reservation; the original
     * key remains scoped to the idempotency ledger.
     */
    public String guestReservationToken(String requestKey) {
        validateKey(requestKey);
        return sha256("guest-reservation:" + requestKey.trim());
    }

    private void validate(String key, String operation, String actorScope) {
        validateKey(key);
        if (operation == null || operation.isBlank()
                || actorScope == null || actorScope.isBlank()) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Idempotency-Key hoặc actor scope không hợp lệ");
        }
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Idempotency-Key không hợp lệ");
        }
    }

    private String hash(Object payload) {
        try {
            JsonNode canonicalPayload = canonicalize(objectMapper.valueToTree(payload));
            return sha256(objectMapper.writeValueAsString(canonicalPayload));
        } catch (Exception exception) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Không thể chuẩn hóa payload idempotency");
        }
    }

    /** Sort object keys recursively while preserving array order. */
    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            node.forEach(item -> canonical.add(canonicalize(item)));
            return canonical;
        }
        ObjectNode canonical = objectMapper.createObjectNode();
        ArrayList<String> fieldNames = new ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);
        Collections.sort(fieldNames);
        fieldNames.forEach(name -> canonical.set(name, canonicalize(node.get(name))));
        return canonical;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
