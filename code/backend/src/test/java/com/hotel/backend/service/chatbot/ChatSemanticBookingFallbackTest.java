package com.hotel.backend.service.chatbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.dto.request.ChatTurnRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSemanticBookingFallbackTest {

    private final GeminiChatClient geminiChatClient = mock(GeminiChatClient.class);
    private final ChatInputPolicy inputPolicy = new ChatInputPolicy();
    private final ChatSemanticBookingFallback fallback = new ChatSemanticBookingFallback(
            geminiChatClient,
            inputPolicy,
            new ChatPrivacyRedactor(),
            new ObjectMapper(),
            true
    );

    @Test
    void onlyAttemptsLikelyBookingMessagesMissedByTheDeterministicClassifier() {
        assertTrue(fallback.shouldAttempt(
                "Tôi cần chỗ ở cho 2 người ngày mai lúc 14h",
                ChatIntent.OUT_OF_SCOPE
        ));
        assertTrue(fallback.shouldAttempt(
                "Tìm giúp chỗ ở cho hai người cuối tuần tới",
                ChatIntent.OUT_OF_SCOPE
        ));
        assertFalse(fallback.shouldAttempt("Khách sạn có hồ bơi không?", ChatIntent.HOTEL_FAQ));
        assertFalse(fallback.shouldAttempt("Đặt 1 phòng ngày mai", ChatIntent.RESERVATION_CREATE));
    }

    @Test
    void classifiesBookingWithoutAllowingTheProviderToRewriteFacts() {
        when(geminiChatClient.generate(org.mockito.ArgumentMatchers.anyString())).thenReturn(
                GeminiChatResult.success("""
                        {"kind":"BOOKING","clarification":""}
                        """)
        );
        ChatTurnRequest history = new ChatTurnRequest();
        history.setRole("user");
        history.setContent("Email của tôi là guest@example.com");

        ChatSemanticBookingFallback.Result result = fallback.extract(
                "Tôi cần chỗ ở cho 2 người ngày mai lúc 14h",
                List.of(history),
                "vi"
        ).orElseThrow();

        assertEquals(ChatSemanticBookingFallback.Kind.BOOKING, result.kind());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(geminiChatClient).generate(prompt.capture());
        assertTrue(prompt.getValue().contains("[email]"));
        assertFalse(prompt.getValue().contains("guest@example.com"));
        assertFalse(prompt.getValue().contains("canonicalQuestion"));
    }

    @Test
    void rejectsMalformedStructuredOutputWithoutChangingTheDeterministicFlow() {
        when(geminiChatClient.generate(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(GeminiChatResult.success("not-json"));

        assertTrue(fallback.extract("ở 2 người ngày mai", List.of(), "vi").isEmpty());
    }

    @Test
    void rejectsUnexpectedRewriteFieldsInsteadOfTrustingProviderFacts() {
        when(geminiChatClient.generate(org.mockito.ArgumentMatchers.anyString())).thenReturn(
                GeminiChatResult.success("""
                        {"kind":"BOOKING","canonicalQuestion":"Đặt 1 phòng Deluxe cho 3 khách ngày mai","clarification":""}
                        """)
        );

        assertTrue(fallback.extract("Tôi cần chỗ ở cho 2 khách ngày mai", List.of(), "vi").isEmpty());
    }
}
