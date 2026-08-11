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

    @Test
    void recognizesAnExplicitStaySearchWhenProviderIsNotConfigured() {
        when(geminiChatClient.generate(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(GeminiChatResult.failure(GeminiChatResult.Status.NOT_CONFIGURED));

        ChatSemanticBookingFallback.Result result = fallback.extract(
                "Tôi cần chỗ ở cho 2 người ngày mai lúc 14h",
                List.of(),
                "vi"
        ).orElseThrow();

        assertEquals(ChatSemanticBookingFallback.Kind.BOOKING, result.kind());
        assertTrue(result.clarification().isBlank());
    }

    @Test
    void asksForIntentWhenAStayTimeQuestionIsAmbiguousAndProviderIsUnavailable() {
        when(geminiChatClient.generate(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(GeminiChatResult.failure(GeminiChatResult.Status.UNAVAILABLE));

        ChatSemanticBookingFallback.Result result = fallback.extract(
                "Check-in lúc 14h có được không?",
                List.of(),
                "vi"
        ).orElseThrow();

        assertEquals(ChatSemanticBookingFallback.Kind.CLARIFY, result.kind());
        assertTrue(result.clarification().contains("kiểm tra phòng trống"));
        assertTrue(result.clarification().contains("chính sách"));
    }

    @Test
    void treatsDetailsAsBookingWhenAssistantJustRequestedThem() {
        when(geminiChatClient.generate(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(GeminiChatResult.failure(GeminiChatResult.Status.NOT_CONFIGURED));
        ChatTurnRequest assistant = new ChatTurnRequest();
        assistant.setRole("assistant");
        assistant.setContent("Mình có thể gợi ý hạng phòng phù hợp. Bạn cho mình biết số khách, "
                + "ngày/giờ nhận và trả phòng, cùng ưu tiên chính nhé.");

        ChatSemanticBookingFallback.Result result = fallback.extract(
                "2 người, nhận 14h ngày mai và trả 10h ngày kia, ưu tiên yên tĩnh",
                List.of(assistant),
                "vi"
        ).orElseThrow();

        assertEquals(ChatSemanticBookingFallback.Kind.BOOKING, result.kind());
    }

    @Test
    void doesNotReuseAnOlderBookingPromptAfterTheConversationMovedOn() {
        when(geminiChatClient.generate(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(GeminiChatResult.failure(GeminiChatResult.Status.NOT_CONFIGURED));
        ChatTurnRequest bookingPrompt = new ChatTurnRequest();
        bookingPrompt.setRole("assistant");
        bookingPrompt.setContent("Bạn cho mình biết số khách và ngày/giờ nhận và trả phòng nhé.");
        ChatTurnRequest laterUser = new ChatTurnRequest();
        laterUser.setRole("user");
        laterUser.setContent("Khách sạn có cho nhận phòng sớm không?");
        ChatTurnRequest laterAssistant = new ChatTurnRequest();
        laterAssistant.setRole("assistant");
        laterAssistant.setContent("Việc nhận phòng sớm phụ thuộc tình trạng phòng thực tế.");

        ChatSemanticBookingFallback.Result result = fallback.extract(
                "Check-in lúc 14h có được không?",
                List.of(bookingPrompt, laterUser, laterAssistant),
                "vi"
        ).orElseThrow();

        assertEquals(ChatSemanticBookingFallback.Kind.CLARIFY, result.kind());
    }
}
