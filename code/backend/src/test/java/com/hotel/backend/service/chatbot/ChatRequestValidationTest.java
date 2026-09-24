package com.hotel.backend.service.chatbot;

import com.hotel.backend.dto.request.ChatBookingStateRequest;
import com.hotel.backend.dto.request.ChatRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ChatRequestValidationTest {
    @Test
    void rejectsNullHistoryTurnBeforeItReachesThePublicChatService() {
        ChatRequest request = new ChatRequest();
        request.setQuestion("What about the second room?");
        request.setHistory(Collections.singletonList(null));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(request).isEmpty());
        }
    }

    @Test
    void rejectsNullRoomLinesAndPendingRoomIdsInClientSuppliedBookingState() {
        ChatBookingStateRequest state = new ChatBookingStateRequest();
        ChatRequest request = new ChatRequest();
        request.setQuestion("Add one room");
        request.setBookingState(state);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            state.setRoomTypes(Collections.singletonList(null));
            assertFalse(factory.getValidator().validate(request).isEmpty());
            state.setRoomTypes(Collections.emptyList());
            state.setPendingRoomTypeIds(Collections.singletonList(null));
            assertFalse(factory.getValidator().validate(request).isEmpty());
        }
    }
}
