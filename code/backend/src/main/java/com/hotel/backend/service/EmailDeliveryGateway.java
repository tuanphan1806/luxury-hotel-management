package com.hotel.backend.service;

import java.io.IOException;
import java.util.Map;

/**
 * Provider-neutral boundary for delivering already composed email content.
 */
public interface EmailDeliveryGateway {

    record Receipt(String messageId, boolean deduplicated) { }

    default Receipt sendTrackedHtml(String from, String replyTo, String hotelName,
            String to, String subject, HotelEmailTemplateRenderer.RenderedEmail rendered,
            String purpose, String idempotencyKey) throws IOException {
        sendHtml(from, replyTo, hotelName, to, subject, rendered, purpose);
        return new Receipt(null, false);
    }

    default boolean supportsIdempotency() { return false; }

    /** Null means retries after uncertain acceptance require manual review. */
    default String idempotencyScope() { return null; }

    default boolean supportsDynamicTemplates() {
        return true;
    }

    void sendDynamicTemplate(
            String from,
            String replyTo,
            String hotelName,
            String to,
            String recipientName,
            String dynamicTemplateId,
            Map<String, Object> dynamicData,
            String purpose) throws IOException;

    void sendHtml(
            String from,
            String replyTo,
            String hotelName,
            String to,
            String subject,
            HotelEmailTemplateRenderer.RenderedEmail rendered,
            String purpose) throws IOException;
}
