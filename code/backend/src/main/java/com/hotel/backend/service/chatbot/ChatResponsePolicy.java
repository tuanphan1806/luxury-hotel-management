package com.hotel.backend.service.chatbot;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

@Component
public class ChatResponsePolicy {

    private static final int MAX_ANSWER_LENGTH = 4000;

    public Optional<String> sanitize(String answer) {
        if (answer == null || answer.isBlank()) {
            return Optional.empty();
        }
        String sanitized = answer
                .replace('\u0000', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replaceAll("(?s)```(?:[a-zA-Z0-9_-]+)?\\s*(.*?)\\s*```", "$1")
                .replaceAll("!\\[([^\\]]*)]\\([^)]*\\)", "$1")
                .replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1")
                .replaceAll("(?m)^\\s*(?:-{3,}|_{3,}|\\*{3,})\\s*$", "")
                .replaceAll("(?m)^#{1,6}\\s+", "")
                .replaceAll("(?m)^\\s*>\\s?", "")
                .replaceAll("(?m)^\\s*[-*+]\\s+", "• ")
                .replaceAll("\\*\\*([^*\\r\\n]+)\\*\\*", "$1")
                .replaceAll("__([^_\\r\\n]+)__", "$1")
                .replaceAll("~~([^~\\r\\n]+)~~", "$1")
                .replaceAll("`([^`\\r\\n]+)`", "$1")
                .replaceAll("(?m)[ \\t]+$", "")
                .replaceAll("(?:\\r?\\n){3,}", "\n\n")
                .strip();
        String normalized = sanitized.toLowerCase(Locale.ROOT);
        if (normalized.contains("system prompt:")
                || normalized.contains("developer message:")
                || normalized.contains("gemini.api.key")
                || normalized.contains("x-goog-api-key")) {
            return Optional.empty();
        }
        if (sanitized.length() > MAX_ANSWER_LENGTH) {
            sanitized = truncateNaturally(sanitized);
        }
        return Optional.of(sanitized);
    }

    private String truncateNaturally(String value) {
        int searchFloor = Math.max(0, MAX_ANSWER_LENGTH - 600);
        for (int index = MAX_ANSWER_LENGTH - 1; index >= searchFloor; index--) {
            char current = value.charAt(index);
            if (current == '.' || current == '!' || current == '?' || current == '\n') {
                return value.substring(0, index + 1).stripTrailing();
            }
        }

        int wordBoundary = value.lastIndexOf(' ', MAX_ANSWER_LENGTH - 2);
        if (wordBoundary < searchFloor) {
            wordBoundary = MAX_ANSWER_LENGTH - 2;
        }
        return value.substring(0, wordBoundary).stripTrailing() + "…";
    }
}
