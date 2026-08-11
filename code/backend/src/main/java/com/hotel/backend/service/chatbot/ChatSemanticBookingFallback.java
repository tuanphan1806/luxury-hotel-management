package com.hotel.backend.service.chatbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.hotel.backend.dto.request.ChatTurnRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Narrow AI fallback used only when the deterministic intent parser cannot
 * confidently recognize a likely booking request. It never creates a booking
 * and never returns IDs, prices or availability. The provider may only classify
 * the request or ask a clarification question; the deterministic parser always
 * reads the original user message so provider-generated facts cannot enter the
 * reservation handoff.
 */
@Component
@Slf4j(topic = "CHAT-SEMANTIC-FALLBACK")
public class ChatSemanticBookingFallback {

    private static final Pattern BOOKING_SIGNAL = Pattern.compile(
            ".*\\b(?:phong|room|o|nghi|stay|checkin|checkout|check-in|check-out|"
                    + "khach|nguoi|guest|adult|child|dem|night|ngay|day|gio|hour|"
                    + "den|toi|from|to|arrive|leave)\\b.*"
    );
    private static final Pattern DATE_OR_TIME_SIGNAL = Pattern.compile(
            ".*(?:\\d{1,4}[/:h-]\\d{1,2}|\\b\\d{1,2}\\s*(?:am|pm|h|gio|giờ)\\b|"
                    + "hom nay|ngay mai|ngay kia|toi nay|dem nay|tuan nay|tuan toi|tuan sau|"
                    + "cuoi tuan|thang nay|thang toi|today|tomorrow|tonight|this week|next week|"
                    + "weekend|next month).*"
    );
    private static final Set<String> ALLOWED_RESPONSE_FIELDS = Set.of("kind", "clarification");
    private final GeminiChatClient geminiChatClient;
    private final ChatInputPolicy inputPolicy;
    private final ChatPrivacyRedactor privacyRedactor;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public ChatSemanticBookingFallback(
            GeminiChatClient geminiChatClient,
            ChatInputPolicy inputPolicy,
            ChatPrivacyRedactor privacyRedactor,
            ObjectMapper objectMapper,
            @Value("${chatbot.semantic-booking-fallback-enabled:true}") boolean enabled
    ) {
        this.geminiChatClient = geminiChatClient;
        this.inputPolicy = inputPolicy;
        this.privacyRedactor = privacyRedactor;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    public boolean shouldAttempt(String question, ChatIntent deterministicIntent) {
        if (!enabled || deterministicIntent == ChatIntent.RESERVATION_CREATE
                || deterministicIntent == ChatIntent.AVAILABILITY
                || deterministicIntent == ChatIntent.PRIVATE_BOOKING_LOOKUP
                || deterministicIntent == ChatIntent.RESERVATION_CANCEL_OR_CHANGE
                || deterministicIntent == ChatIntent.GREETING) {
            return false;
        }
        String normalized = inputPolicy.normalizeForMatching(question);
        return normalized.length() >= 6
                && BOOKING_SIGNAL.matcher(normalized).matches()
                && DATE_OR_TIME_SIGNAL.matcher(normalized).matches();
    }

    public Optional<Result> extract(
            String question,
            List<ChatTurnRequest> history,
            String locale
    ) {
        String safeQuestion = privacyRedactor.redact(inputPolicy.sanitizeQuestion(question));
        String safeHistory = buildHistory(history);
        String language = "en".equalsIgnoreCase(locale) ? "English" : "Vietnamese";
        String prompt = """
                You classify possible hotel booking requests for a deterministic parser.

                Return exactly one JSON object and no Markdown:
                {"kind":"BOOKING|CLARIFY|NOT_BOOKING","clarification":"..."}

                Rules:
                - BOOKING only when the user is trying to check availability or begin a hotel booking.
                - Do not extract, normalize, rewrite, copy or infer dates, times, room names, quantities or guest counts.
                - Never invent a missing value. The deterministic parser will read the original user message and ask for missing data.
                - CLARIFY only when the user's wording has two materially different booking interpretations. Ask one short question in %s.
                - NOT_BOOKING for general hotel questions or unrelated content.
                - clarification must be plain text in %s and at most 500 characters.
                - Do not include personal data from the conversation.
                - Today in the hotel timezone Asia/Ho_Chi_Minh is %s. Do not resolve relative dates yourself; preserve words such as today or tomorrow.

                RECENT CONVERSATION (PII REDACTED):
                %s

                CURRENT MESSAGE (PII REDACTED):
                %s
                """.formatted(
                language,
                language,
                LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")),
                safeHistory,
                safeQuestion
        );

        GeminiChatResult generated = geminiChatClient.generate(prompt);
        if (generated == null || generated.status() != GeminiChatResult.Status.SUCCESS
                || generated.answer() == null || generated.answer().isBlank()) {
            return deterministicProviderFallback(safeQuestion, locale);
        }

        try {
            String json = stripCodeFence(generated.answer());
            JsonNode responseNode = objectMapper.readTree(json);
            if (!hasOnlyAllowedFields(responseNode)) {
                return Optional.empty();
            }
            RawResult raw = objectMapper.readValue(json, RawResult.class);
            Kind kind = Kind.valueOf(Optional.ofNullable(raw.kind()).orElse("")
                    .trim().toUpperCase(Locale.ROOT));
            String clarification = inputPolicy.sanitizeQuestion(raw.clarification());
            if (kind == Kind.CLARIFY && clarification.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new Result(kind, clarification));
        } catch (RuntimeException | java.io.IOException exception) {
            log.warn("Ignored invalid structured Gemini response: {}", exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private String buildHistory(List<ChatTurnRequest> history) {
        if (history == null || history.isEmpty()) {
            return "(none)";
        }
        return history.stream()
                .filter(turn -> turn != null && turn.getContent() != null)
                .skip(Math.max(0, history.size() - 6))
                .map(turn -> ("assistant".equals(turn.getRole()) ? "ASSISTANT: " : "USER: ")
                        + privacyRedactor.redact(inputPolicy.sanitizeQuestion(turn.getContent())))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("(none)");
    }

    /**
     * Provider-independent safety net. It only classifies a message that has
     * already passed {@link #shouldAttempt(String, ChatIntent)}; it never
     * extracts or invents reservation facts.
     */
    private Optional<Result> deterministicProviderFallback(String question, String locale) {
        String normalized = inputPolicy.normalizeForMatching(question);
        boolean explicitStaySearch = containsAny(
                normalized,
                "can cho o", "can phong", "tim phong", "muon o", "kiem phong",
                "need somewhere to stay", "looking for a room", "find a room",
                "need a room", "want a room", "check availability"
        ) || (containsAny(normalized, "tim", "find", "looking")
                && containsAny(normalized, "cho o", "phong", "room", "stay"));

        if (explicitStaySearch) {
            return Optional.of(new Result(Kind.BOOKING, ""));
        }

        String clarification = "en".equalsIgnoreCase(locale)
                ? "Would you like to check availability and start a booking for that time, or are you asking about the hotel's check-in/check-out policy?"
                : "Bạn muốn kiểm tra phòng trống và bắt đầu đặt phòng cho thời gian đó, hay đang hỏi về chính sách nhận/trả phòng của khách sạn?";
        return Optional.of(new Result(Kind.CLARIFY, clarification));
    }

    private boolean containsAny(String normalized, String... phrases) {
        for (String phrase : phrases) {
            if (normalized.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private String stripCodeFence(String value) {
        String stripped = value.strip();
        if (stripped.startsWith("```")) {
            stripped = stripped.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .strip();
        }
        return stripped;
    }

    private boolean hasOnlyAllowedFields(JsonNode responseNode) {
        if (responseNode == null || !responseNode.isObject()) {
            return false;
        }
        Iterator<String> fieldNames = responseNode.fieldNames();
        while (fieldNames.hasNext()) {
            if (!ALLOWED_RESPONSE_FIELDS.contains(fieldNames.next())) {
                return false;
            }
        }
        return true;
    }

    public enum Kind {
        BOOKING,
        CLARIFY,
        NOT_BOOKING
    }

    public record Result(Kind kind, String clarification) {
    }

    private record RawResult(String kind, String clarification) {
    }
}
