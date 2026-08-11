package com.hotel.backend.service.chatbot;

public record GeminiChatResult(
        Status status,
        String answer,
        String finishReason,
        Integer promptTokenCount,
        Integer candidateTokenCount,
        Integer totalTokenCount
) {

    public enum Status {
        SUCCESS,
        NOT_CONFIGURED,
        RATE_LIMITED,
        BUSY,
        UNAVAILABLE,
        INVALID_RESPONSE
    }

    public static GeminiChatResult success(String answer) {
        return success(answer, null, null, null, null);
    }

    public static GeminiChatResult success(
            String answer,
            String finishReason,
            Integer promptTokenCount,
            Integer candidateTokenCount,
            Integer totalTokenCount
    ) {
        return new GeminiChatResult(
                Status.SUCCESS,
                answer,
                finishReason,
                promptTokenCount,
                candidateTokenCount,
                totalTokenCount
        );
    }

    public static GeminiChatResult failure(Status status) {
        return new GeminiChatResult(status, null, null, null, null, null);
    }
}
