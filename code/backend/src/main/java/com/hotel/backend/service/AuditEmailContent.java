package com.hotel.backend.service;

import com.fasterxml.jackson.databind.JsonNode;

final class AuditEmailContent {
    private AuditEmailContent() {}
    static String subject(JsonNode payload) {
        return "[Luxury Hotel] Cảnh báo thao tác rủi ro " + text(payload, "action", "UNKNOWN");
    }

    static String body(JsonNode payload) {
        return """
                Hệ thống ghi nhận một thao tác cần kiểm tra.

                Hành động: %s
                Mức rủi ro: %s
                Đối tượng: %s %s
                Reservation: %s
                Người thao tác: %s (%s)
                Thời gian UTC: %s
                Correlation ID: %s
                Chi tiết: %s

                Mở trang Audit Log trong dashboard để xem dữ liệu đã được định dạng.
                """.formatted(
                text(payload, "action", "UNKNOWN"),
                text(payload, "riskLevel", "HIGH"),
                text(payload, "targetType", "UNKNOWN"),
                text(payload, "targetId", ""),
                text(payload, "reservationCode", "Không có"),
                text(payload, "actorName", "Hệ thống"),
                text(payload, "actorRole", "SYSTEM"),
                text(payload, "occurredAtUtc", ""),
                text(payload, "correlationId", "Không có"),
                text(payload, "details", "Không có"));
    }

    static String text(JsonNode payload, String field, String fallback) {
        if (payload == null || !payload.hasNonNull(field)) return fallback;
        String value = payload.path(field).asText();
        return value == null || value.isBlank() ? fallback : value;
    }
}
