package com.hotel.backend.service;

import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import com.sendgrid.helpers.mail.objects.Personalization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Map;

/**
 * SendGrid SDK adapter. Email use cases no longer construct provider-specific
 * request objects directly.
 */
@RequiredArgsConstructor
@Slf4j(topic = "SENDGRID-EMAIL-DELIVERY")
public final class SendGridEmailDeliveryGateway implements EmailDeliveryGateway {

    private final SendGrid client;

    @Override
    public void sendDynamicTemplate(
            String from,
            String replyTo,
            String hotelName,
            String to,
            String recipientName,
            String dynamicTemplateId,
            Map<String, Object> dynamicData,
            String purpose) throws IOException {
        requireEmailAddress(from, "from-email");
        requireEmailAddress(to, "recipient");

        Mail mail = new Mail();
        mail.setFrom(new Email(from.trim(), hotelName));
        mail.setReplyTo(new Email(replyTo, hotelName));
        mail.setTemplateId(dynamicTemplateId.trim());

        Personalization personalization = new Personalization();
        personalization.addTo(new Email(to.trim(), recipientName));
        dynamicData.forEach(personalization::addDynamicTemplateData);
        mail.addPersonalization(personalization);

        requireAccepted(execute(mail), purpose);
    }

    @Override
    public void sendHtml(
            String from,
            String replyTo,
            String hotelName,
            String to,
            String subject,
            HotelEmailTemplateRenderer.RenderedEmail rendered,
            String purpose) throws IOException {
        requireEmailAddress(from, "from-email");
        requireEmailAddress(to, "recipient");

        Mail mail = new Mail();
        mail.setFrom(new Email(from.trim(), hotelName));
        mail.setReplyTo(new Email(replyTo, hotelName));
        mail.setSubject(subject);
        Personalization personalization = new Personalization();
        personalization.addTo(new Email(to.trim()));
        mail.addPersonalization(personalization);
        mail.addContent(new Content("text/plain", rendered.plainText()));
        mail.addContent(new Content("text/html", rendered.html()));

        requireAccepted(execute(mail), purpose);
    }

    private Response execute(Mail mail) throws IOException {
        Request request = new Request();
        request.setMethod(Method.POST);
        request.setEndpoint("mail/send");
        request.setBody(mail.build());
        return client.api(request);
    }

    private void requireAccepted(Response response, String purpose) throws IOException {
        int status = response == null ? 0 : response.getStatusCode();
        if (status >= 200 && status < 300) {
            log.info("SendGrid accepted email purpose={} status={}", purpose, status);
            return;
        }
        String diagnostic = response == null || response.getBody() == null
                ? ""
                : response.getBody().replaceAll("[\\r\\n]+", " ");
        if (diagnostic.length() > 300) diagnostic = diagnostic.substring(0, 300);
        log.warn("SendGrid rejected email purpose={} status={} response={}", purpose, status, diagnostic);
        throw new IOException("SendGrid rejected " + purpose + " email with status " + status);
    }

    private void requireEmailAddress(String value, String property) {
        if (value == null || value.isBlank() || !value.contains("@")) {
            throw new AppException(
                    ErrorCode.EMAIL_DELIVERY_FAILED,
                    "Cấu hình email không hợp lệ: " + property);
        }
    }
}
