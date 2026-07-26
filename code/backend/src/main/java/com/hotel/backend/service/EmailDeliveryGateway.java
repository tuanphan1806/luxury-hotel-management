package com.hotel.backend.service;

import java.io.IOException;
import java.util.Map;

/**
 * Provider-neutral boundary for delivering already composed email content.
 */
public interface EmailDeliveryGateway {

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
