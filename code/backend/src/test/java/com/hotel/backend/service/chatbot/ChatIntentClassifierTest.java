package com.hotel.backend.service.chatbot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatIntentClassifierTest {

    private final ChatInputPolicy inputPolicy = new ChatInputPolicy();
    private final ChatIntentClassifier classifier = new ChatIntentClassifier(inputPolicy);

    @Test
    void lookupAndCancellationTakePriorityOverCreateKeywords() {
        assertEquals(ChatIntent.PRIVATE_BOOKING_LOOKUP,
                classifier.classify("Tôi đã đặt phòng, tra cứu mã ở đâu?"));
        assertEquals(ChatIntent.RESERVATION_CANCEL_OR_CHANGE,
                classifier.classify("Tôi muốn hủy đặt phòng"));
        assertEquals(ChatIntent.RESERVATION_CANCEL_OR_CHANGE,
                classifier.classify("I need to change my booking"));
    }

    @Test
    void depositPolicyQuestionIsNotAReservationCreationCommand() {
        assertEquals(ChatIntent.HOTEL_FAQ,
                classifier.classify("Đặt phòng có cần cọc không?"));
    }

    @Test
    void numericRoomBookingIsRecognized() {
        assertEquals(ChatIntent.RESERVATION_CREATE,
                classifier.classify("Đặt 1 phòng Deluxe ngày mai"));
    }
}
