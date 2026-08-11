package com.hotel.backend.service.chatbot;

import com.hotel.backend.service.BusinessMetricService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleGeminiChatClientTest {

    @Test
    void missingApiKeyReturnsExplicitStatusWithoutNetworkCall() {
        BusinessMetricService metrics = metrics();
        GoogleGeminiChatClient client = new GoogleGeminiChatClient(
                WebClient.builder(), "", 2, 1, metrics
        );

        assertEquals(GeminiChatResult.Status.NOT_CONFIGURED, client.generate("prompt").status());
        verify(metrics).increment(
                "hotel.chat.provider.calls",
                "provider", "gemini",
                "status", "not_configured"
        );
        verify(metrics).recordDuration(
                org.mockito.ArgumentMatchers.eq("hotel.chat.provider.duration"),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq("provider"),
                org.mockito.ArgumentMatchers.eq("gemini"),
                org.mockito.ArgumentMatchers.eq("status"),
                org.mockito.ArgumentMatchers.eq("not_configured")
        );
    }

    @Test
    void apiKeyIsSentAsHeaderAndNeverAsQueryParameter() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]},\"finishReason\":\"STOP\"}],"
                            + "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":2,\"totalTokenCount\":12}}")
                    .build());
        });
        GoogleGeminiChatClient client = new GoogleGeminiChatClient(
                builder, "secret-key", 2, 1, metrics());

        GeminiChatResult result = client.generate("prompt");

        assertEquals(GeminiChatResult.Status.SUCCESS, result.status());
        assertEquals("Hello", result.answer());
        assertEquals("STOP", result.finishReason());
        assertEquals(12, result.totalTokenCount());
        assertEquals("secret-key", captured.get().headers().getFirst("x-goog-api-key"));
        assertFalse(captured.get().url().getQuery() != null
                && captured.get().url().getQuery().contains("secret-key"));
    }

    @Test
    void incompleteProviderResponseIsRejectedInsteadOfShowingAPartialSentence() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Partial\"}]},\"finishReason\":\"MAX_TOKENS\"}]}")
                        .build()
        ));
        GoogleGeminiChatClient client = new GoogleGeminiChatClient(
                builder, "secret-key", 2, 1, metrics());

        assertEquals(GeminiChatResult.Status.INVALID_RESPONSE, client.generate("prompt").status());
    }

    private BusinessMetricService metrics() {
        BusinessMetricService metrics = mock(BusinessMetricService.class);
        when(metrics.outcomeTag(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            Object value = invocation.getArgument(0);
            return value == null ? "unknown" : value.toString().toLowerCase(java.util.Locale.ROOT);
        });
        return metrics;
    }
}
