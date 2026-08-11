package com.hotel.backend.service.chatbot;

import com.hotel.backend.service.BusinessMetricService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j(topic = "GEMINI-CHAT-CLIENT")
public class GoogleGeminiChatClient implements GeminiChatClient {

    private static final String MODEL_PATH = "/v1beta/models/gemini-2.5-flash:generateContent";
    private static final int FAILURE_THRESHOLD = 3;
    private static final Duration CIRCUIT_OPEN_DURATION = Duration.ofSeconds(30);

    private final WebClient client;
    private final String apiKey;
    private final Duration timeout;
    private final Semaphore bulkhead;
    private final BusinessMetricService businessMetricService;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile Instant circuitOpenUntil;

    public GoogleGeminiChatClient(
            WebClient.Builder builder,
            @Value("${gemini.api.key:}") String apiKey,
            @Value("${gemini.api.timeout-seconds:10}") long timeoutSeconds,
            @Value("${gemini.api.max-concurrent-requests:4}") int maxConcurrentRequests,
            BusinessMetricService businessMetricService
    ) {
        this.client = builder.baseUrl("https://generativelanguage.googleapis.com").build();
        this.apiKey = apiKey;
        this.timeout = Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : 10);
        this.bulkhead = new Semaphore(Math.max(1, maxConcurrentRequests));
        this.businessMetricService = businessMetricService;
    }

    @Override
    public GeminiChatResult generate(String prompt) {
        long startedAt = System.nanoTime();
        if (apiKey == null || apiKey.isBlank()) {
            return measuredResult(startedAt,
                    GeminiChatResult.failure(GeminiChatResult.Status.NOT_CONFIGURED));
        }
        Instant now = Instant.now();
        if (circuitOpenUntil != null && now.isBefore(circuitOpenUntil)) {
            return measuredResult(startedAt,
                    GeminiChatResult.failure(GeminiChatResult.Status.UNAVAILABLE));
        }
        if (!bulkhead.tryAcquire()) {
            return measuredResult(startedAt,
                    GeminiChatResult.failure(GeminiChatResult.Status.BUSY));
        }

        try {
            Map<String, Object> request = Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of(
                            "temperature", 0.2,
                            "maxOutputTokens", 700,
                            // Hotel FAQ and booking guidance are bounded tasks. Disabling
                            // Gemini 2.5 Flash's dynamic thinking keeps the output budget
                            // for the customer-facing answer and reduces latency/cost.
                            "thinkingConfig", Map.of("thinkingBudget", 0)
                    )
            );

            GeminiResponse response = client.post()
                    .uri(MODEL_PATH)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(GeminiResponse.class)
                    .block(timeout);

            GeminiCandidate candidate = firstCandidate(response);
            String answer = firstAnswer(candidate);
            if (answer == null || answer.isBlank()) {
                registerFailure();
                return measuredResult(startedAt,
                        GeminiChatResult.failure(GeminiChatResult.Status.INVALID_RESPONSE));
            }
            String finishReason = candidate.finishReason();
            if (finishReason != null && !finishReason.isBlank()
                    && !"STOP".equalsIgnoreCase(finishReason)) {
                log.warn("Gemini returned an incomplete or blocked response, finishReason={}", finishReason);
                registerFailure();
                return measuredResult(startedAt,
                        GeminiChatResult.failure(GeminiChatResult.Status.INVALID_RESPONSE));
            }
            consecutiveFailures.set(0);
            circuitOpenUntil = null;
            String normalized = answer.strip();
            GeminiUsageMetadata usage = response.usageMetadata();
            log.debug(
                    "Gemini response accepted, finishReason={}, promptTokens={}, candidateTokens={}, totalTokens={}",
                    finishReason,
                    usage == null ? null : usage.promptTokenCount(),
                    usage == null ? null : usage.candidatesTokenCount(),
                    usage == null ? null : usage.totalTokenCount()
            );
            return measuredResult(startedAt, GeminiChatResult.success(
                    normalized,
                    finishReason,
                    usage == null ? null : usage.promptTokenCount(),
                    usage == null ? null : usage.candidatesTokenCount(),
                    usage == null ? null : usage.totalTokenCount()
            ));
        } catch (WebClientResponseException exception) {
            registerFailure();
            HttpStatusCode status = exception.getStatusCode();
            log.warn("Gemini request failed with status={}", status.value());
            return measuredResult(startedAt, status.value() == 429
                    ? GeminiChatResult.failure(GeminiChatResult.Status.RATE_LIMITED)
                    : GeminiChatResult.failure(GeminiChatResult.Status.UNAVAILABLE));
        } catch (RuntimeException exception) {
            registerFailure();
            log.warn("Gemini request failed: {}", exception.getClass().getSimpleName());
            return measuredResult(startedAt,
                    GeminiChatResult.failure(GeminiChatResult.Status.UNAVAILABLE));
        } finally {
            bulkhead.release();
        }
    }

    private GeminiChatResult measuredResult(long startedAt, GeminiChatResult result) {
        try {
            String status = businessMetricService.outcomeTag(result == null ? null : result.status());
            businessMetricService.increment(
                    "hotel.chat.provider.calls",
                    "provider", "gemini",
                    "status", status
            );
            businessMetricService.recordDuration(
                    "hotel.chat.provider.duration",
                    startedAt,
                    "provider", "gemini",
                    "status", status
            );
        } catch (RuntimeException metricError) {
            // Observability must never turn an otherwise safe provider result
            // into a customer-facing chatbot failure.
            log.debug("Could not record Gemini metrics: {}", metricError.getClass().getSimpleName());
        }
        return result;
    }

    private GeminiCandidate firstCandidate(GeminiResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            return null;
        }
        return response.candidates().get(0);
    }

    private String firstAnswer(GeminiCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        GeminiContent content = candidate.content();
        if (content == null || content.parts() == null || content.parts().isEmpty()) {
            return null;
        }
        return content.parts().get(0).text();
    }

    private void registerFailure() {
        if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD) {
            circuitOpenUntil = Instant.now().plus(CIRCUIT_OPEN_DURATION);
            consecutiveFailures.set(0);
        }
    }

    private record GeminiResponse(List<GeminiCandidate> candidates, GeminiUsageMetadata usageMetadata) {
    }

    private record GeminiCandidate(GeminiContent content, String finishReason) {
    }

    private record GeminiContent(List<GeminiPart> parts) {
    }

    private record GeminiPart(String text) {
    }

    private record GeminiUsageMetadata(
            Integer promptTokenCount,
            Integer candidatesTokenCount,
            Integer totalTokenCount
    ) {
    }
}
