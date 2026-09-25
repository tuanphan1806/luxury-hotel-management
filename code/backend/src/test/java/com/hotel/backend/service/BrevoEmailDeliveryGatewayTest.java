package com.hotel.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class BrevoEmailDeliveryGatewayTest {
    private MockRestServiceServer server;
    private BrevoEmailDeliveryGateway gateway;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new BrevoEmailDeliveryGateway("test-only-key", builder.build());
    }

    @Test
    void deliversUtf8HtmlAndPlainTextWithoutSendgridTemplateIds() throws Exception {
        server.expect(requestTo(BrevoEmailDeliveryGateway.ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "test-only-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sender.email").value("sender@example.com"))
                .andExpect(jsonPath("$.sender.name").value("Luxury Hotel"))
                .andExpect(jsonPath("$.to[0].email").value("guest@example.com"))
                .andExpect(jsonPath("$.replyTo.email").value("support@example.com"))
                .andExpect(jsonPath("$.subject").value("Xác thực tài khoản"))
                .andExpect(jsonPath("$.htmlContent").value("<p>Xin chào</p>"))
                .andExpect(jsonPath("$.textContent").value("Xin chào"))
                .andExpect(jsonPath("$.templateId").doesNotExist())
                .andRespond(withStatus(HttpStatusCode.valueOf(201))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"messageId\":\"accepted-test-message\"}"));
        send();
        assertThat(gateway.supportsDynamicTemplates()).isFalse();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500})
    void rejectsProviderFailuresWithoutRetryOrLeakingResponse(int status) {
        server.expect(requestTo(BrevoEmailDeliveryGateway.ENDPOINT))
                .andRespond(withStatus(HttpStatusCode.valueOf(status))
                        .body("sensitive-provider-body"));
        assertThatThrownBy(this::send).isInstanceOf(IOException.class)
                .hasMessageNotContaining("sensitive-provider-body")
                .hasMessageNotContaining("test-only-key");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"messageId\":\"\"}", "{\"messageId\":17}", "invalid-json"})
    void requiresAValidAcceptanceReceipt(String body) {
        server.expect(requestTo(BrevoEmailDeliveryGateway.ENDPOINT))
                .andRespond(withStatus(HttpStatusCode.valueOf(201))
                        .contentType(MediaType.APPLICATION_JSON).body(body));
        assertThatThrownBy(this::send).isInstanceOf(IOException.class);
        server.verify();
    }

    @Test
    void failsClosedOnTimeoutWithoutAnAutomaticResend() {
        server.expect(requestTo(BrevoEmailDeliveryGateway.ENDPOINT))
                .andRespond(withException(new java.net.SocketTimeoutException("test timeout")));
        assertThatThrownBy(this::send).isInstanceOf(IOException.class);
        server.verify();
    }

    @Test
    void refusesMissingApiKey() {
        assertThatThrownBy(() -> new BrevoEmailDeliveryGateway(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BREVO_API_KEY");
    }

    private void send() throws IOException {
        gateway.sendHtml("sender@example.com", "support@example.com", "Luxury Hotel",
                "guest@example.com", "Xác thực tài khoản",
                new HotelEmailTemplateRenderer.RenderedEmail("Xin chào", "<p>Xin chào</p>"), "verification");
    }
}
