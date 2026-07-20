package com.hotel.backend.controller;

import com.hotel.backend.dto.request.ChatRequest;
import com.hotel.backend.dto.response.ChatResponse;
import com.hotel.backend.service.ChatBotService;
import com.hotel.backend.service.AuthRateLimitService;
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

    @Operation(summary = "Chat with bot", description = "API send a question to the hotel chatbot and get an answer")
    @PostMapping
    public ChatResponse chat(
            @Valid @RequestBody ChatRequest request,
            HttpServletRequest httpRequest
    ) {

        String question = request == null ? null : request.getQuestion();

        String clientIp = clientIpResolver.resolve(httpRequest);
        authRateLimitService.check("chat-ip:" + clientIp, 30, Duration.ofMinutes(1));

        String conversationKey = request != null
                && request.getConversationId() != null
                && !request.getConversationId().isBlank()
                ? sha256(request.getConversationId().trim())
                : "anonymous";
        String clientKey = "chat:" + clientIp + ":" + conversationKey;

        return chatBotService.askWithAction(question, clientKey);
    }
}
