package com.hotel.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** HTTPS delivery using locally rendered templates; no SMTP or automatic resend. */
public final class BrevoEmailDeliveryGateway implements EmailDeliveryGateway {
    static final String ENDPOINT = "https://api.brevo.com/v3/smtp/email";
    private final String apiKey;
    private final RestClient client;

    public BrevoEmailDeliveryGateway(String apiKey) {
        this(apiKey, createClient());
    }

    BrevoEmailDeliveryGateway(String apiKey, RestClient client) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("BREVO_API_KEY is required when EMAIL_PROVIDER=brevo");
        }
        this.apiKey = apiKey.trim();
        this.client = client;
    }

    private static RestClient createClient() {
        var http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(20));
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public boolean supportsDynamicTemplates() {
        return false;
    }

    @Override
    public void sendDynamicTemplate(String from, String replyTo, String hotelName,
            String to, String recipientName, String dynamicTemplateId,
            Map<String, Object> dynamicData, String purpose) throws IOException {
        throw new IOException("Brevo delivery requires the local HTML template");
    }

    @Override
    public void sendHtml(String from, String replyTo, String hotelName,
            String to, String subject, HotelEmailTemplateRenderer.RenderedEmail rendered,
            String purpose) throws IOException {
        requireAddress(from);
        requireAddress(to);
        requireAddress(replyTo);
        var payload = Map.of(
                "sender", Map.of("email", from.trim(), "name", hotelName),
                "to", List.of(Map.of("email", to.trim())),
                "replyTo", Map.of("email", replyTo.trim()),
                "subject", subject,
                "htmlContent", rendered.html(),
                "textContent", rendered.plainText());
        try {
            DeliveryReceipt receipt = client.post().uri(ENDPOINT)
                    .header("api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        return new DeliveryReceipt(status,
                                status == 201 ? response.bodyTo(JsonNode.class) : null);
                    });
            if (receipt == null || receipt.status() != 201) {
                // Never include provider bodies, recipients or tokens in diagnostics.
                throw new IOException("Brevo rejected email with HTTP "
                        + (receipt == null ? "unknown" : receipt.status()));
            }
            JsonNode body = receipt.body();
            if (body == null || !body.path("messageId").isTextual()
                    || body.path("messageId").asText().isBlank()) {
                throw new IOException("Brevo returned no message id");
            }
        } catch (RestClientException exception) {
            // A transport failure may occur after acceptance. Do not retry here.
            throw new IOException("Brevo email transport failed", exception);
        }
    }

    private record DeliveryReceipt(int status, JsonNode body) { }

    private static void requireAddress(String address) throws IOException {
        if (address == null || address.isBlank() || !address.contains("@")
                || address.contains("\r") || address.contains("\n")) {
            throw new IOException("Invalid email address configuration or recipient");
        }
    }
}
