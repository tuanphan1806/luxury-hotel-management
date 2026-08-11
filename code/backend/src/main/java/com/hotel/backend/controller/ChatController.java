package com.hotel.backend.controller;

import com.hotel.backend.dto.request.ChatRequest;
import com.hotel.backend.dto.response.ChatResponse;
import com.hotel.backend.service.ChatBotService;
import com.hotel.backend.service.AuthRateLimitService;
import com.hotel.backend.service.BusinessMetricService;
import com.hotel.backend.security.ClientIpResolver;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

import static com.hotel.backend.util.SecurityTokenHasher.sha256;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j(topic = "CHAT-CONTROLLER")
public class ChatController {

    private final ChatBotService chatBotService;
    private final AuthRateLimitService authRateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final BusinessMetricService businessMetricService;

    @Operation(summary = "Chat with bot", description = "API send a question to the hotel chatbot and get an answer")
    @PostMapping
    public ChatResponse chat(
            @Valid @RequestBody ChatRequest request,
            HttpServletRequest httpRequest
    ) {

        long startedAt = System.nanoTime();
        String locale = request != null && "en".equalsIgnoreCase(request.getLocale()) ? "en" : "vi";
        String outcome = "error";
        businessMetricService.increment("hotel.chat.requests", "locale", locale);
        try {
            String clientIp = clientIpResolver.resolve(httpRequest);
            authRateLimitService.check("chat-ip:" + clientIp, 30, Duration.ofMinutes(1));

            String conversationKey = request != null
                    && request.getConversationId() != null
                    && !request.getConversationId().isBlank()
                    ? sha256(request.getConversationId().trim())
                    : "anonymous";
            // The service uses this value only for correlation-safe logs. Keep
            // the raw address in the in-memory abuse guard, but never forward it
            // into application log messages.
            String clientKey = "chat:" + sha256(clientIp) + ":" + conversationKey;

            authRateLimitService.check(
                    "chat-conversation:" + clientIp + ":" + conversationKey,
                    10,
                    Duration.ofMinutes(1)
            );

            ChatResponse response = chatBotService.askWithAction(request, clientKey);
            outcome = "success";
            businessMetricService.increment(
                    "hotel.chat.responses",
                    "action",
                    response == null || response.getAction() == null || response.getAction().isBlank()
                            ? "answer_only"
                            : businessMetricService.outcomeTag(response.getAction())
            );
            return response;
        } finally {
            businessMetricService.recordDuration(
                    "hotel.chat.request.duration",
                    startedAt,
                    "locale", locale,
                    "outcome", outcome
            );
        }
    }
}
