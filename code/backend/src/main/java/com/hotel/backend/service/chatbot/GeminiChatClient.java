package com.hotel.backend.service.chatbot;

public interface GeminiChatClient {

    GeminiChatResult generate(String prompt);
}
