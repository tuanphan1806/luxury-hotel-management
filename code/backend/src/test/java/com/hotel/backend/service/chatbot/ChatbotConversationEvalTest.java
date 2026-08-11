package com.hotel.backend.service.chatbot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class ChatbotConversationEvalTest {

    @Test
    void deterministicIntentSemanticFallbackAndInjectionGatesMatchTheCuratedEvalSet() throws Exception {
        ChatInputPolicy inputPolicy = new ChatInputPolicy();
        ChatIntentClassifier classifier = new ChatIntentClassifier(inputPolicy);
        ChatSemanticBookingFallback semanticFallback = new ChatSemanticBookingFallback(
                mock(GeminiChatClient.class),
                inputPolicy,
                new ChatPrivacyRedactor(),
                new ObjectMapper(),
                true
        );

        InputStream input = getClass().getResourceAsStream("/chatbot/conversation-eval.json");
        assertNotNull(input);
        List<EvalCase> cases = new ObjectMapper().readValue(input, new TypeReference<>() { });

        for (EvalCase evalCase : cases) {
            ChatIntent intent = classifier.classify(evalCase.question());
            assertEquals(ChatIntent.valueOf(evalCase.expectedIntent()), intent, evalCase.id());
            assertEquals(evalCase.hotelRelated(), inputPolicy.isHotelRelated(evalCase.question()), evalCase.id());
            assertEquals(
                    evalCase.semanticCandidate(),
                    semanticFallback.shouldAttempt(evalCase.question(), intent),
                    evalCase.id()
            );
            assertEquals(
                    evalCase.promptInjection(),
                    inputPolicy.looksLikePromptInjection(evalCase.question()),
                    evalCase.id()
            );
        }
    }

    private record EvalCase(
            String id,
            String question,
            String expectedIntent,
            boolean hotelRelated,
            boolean semanticCandidate,
            boolean promptInjection
    ) {
    }
}
