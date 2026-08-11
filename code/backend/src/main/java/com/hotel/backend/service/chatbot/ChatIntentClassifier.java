package com.hotel.backend.service.chatbot;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class ChatIntentClassifier {

    private static final Pattern BOOKING_COMMAND = Pattern.compile(
            "\\b(?:dat|book)\\s+(?:\\d{1,2}\\s+)?(?:[\\p{L}\\p{N}-]+\\s+){0,3}(?:phong|room)\\b|"
                    + "\\b(?:muon|can|want|need)\\s+(?:dat|book)\\b|"
                    + "\\bmake\\s+(?:a\\s+)?reservation\\b"
    );

    private static final List<String> LOOKUP_PHRASES = List.of(
            "tra cuu", "ma dat phong", "booking code", "reservation code",
            "don cua toi", "my booking", "my reservation", "toi da dat phong",
            "kiem tra don", "trang thai don"
    );

    private static final List<String> CANCEL_OR_CHANGE_PHRASES = List.of(
            "huy dat phong", "huy phong", "cancel booking", "cancel reservation",
            "cancel my booking", "cancel my reservation",
            "doi dat phong", "doi lich", "doi ngay", "change booking", "modify booking",
            "change my booking", "change my reservation", "modify my booking", "modify my reservation"
    );

    private static final List<String> INFORMATIONAL_BOOKING_PHRASES = List.of(
            "co can coc", "can dat coc", "dat coc bao nhieu", "booking policy",
            "cancellation policy", "chinh sach dat phong", "chinh sach huy"
    );

    private final ChatInputPolicy inputPolicy;

    public ChatIntentClassifier(ChatInputPolicy inputPolicy) {
        this.inputPolicy = inputPolicy;
    }

    public ChatIntent classify(String question) {
        String normalized = inputPolicy.normalizeForMatching(question == null ? "" : question);
        if (inputPolicy.isGreeting(question)) {
            return ChatIntent.GREETING;
        }
        // A change/cancellation sentence commonly contains "my booking".
        // The requested mutation is more specific than a generic lookup, so
        // it must win before the overlapping lookup vocabulary is evaluated.
        if (containsAny(normalized, CANCEL_OR_CHANGE_PHRASES)) {
            return ChatIntent.RESERVATION_CANCEL_OR_CHANGE;
        }
        if (containsAny(normalized, LOOKUP_PHRASES)) {
            return ChatIntent.PRIVATE_BOOKING_LOOKUP;
        }
        if (!containsAny(normalized, INFORMATIONAL_BOOKING_PHRASES)
                && BOOKING_COMMAND.matcher(normalized).find()) {
            return ChatIntent.RESERVATION_CREATE;
        }
        if (isAvailabilityQuestion(normalized)) {
            return ChatIntent.AVAILABILITY;
        }
        return inputPolicy.isHotelRelated(question) ? ChatIntent.HOTEL_FAQ : ChatIntent.OUT_OF_SCOPE;
    }

    public boolean isReservationCreation(String question) {
        return classify(question) == ChatIntent.RESERVATION_CREATE;
    }

    public boolean isAvailabilityQuestion(String question) {
        String normalized = inputPolicy.normalizeForMatching(question == null ? "" : question);
        boolean availabilityPhrase = normalized.contains("con phong")
                || normalized.contains("phong trong")
                || normalized.contains("con trong")
                || normalized.contains("available")
                || normalized.contains("availability")
                || BOOKING_COMMAND.matcher(normalized).find();
        boolean stayTimePhrase = normalized.contains("ngay")
                || normalized.contains("hom nay")
                || normalized.contains("toi nay")
                || normalized.contains("dem nay")
                || normalized.contains("ngay mai")
                || normalized.contains("today")
                || normalized.contains("tonight")
                || normalized.contains("tomorrow")
                || normalized.contains(" tu ")
                || normalized.startsWith("tu ")
                || normalized.contains(" den ")
                || normalized.contains(" from ")
                || normalized.startsWith("from ")
                || normalized.contains(" to ")
                || Pattern.compile("\\b\\d{1,4}[/-]\\d{1,2}(?:[/-]\\d{1,4})?\\b")
                .matcher(normalized)
                .find();
        return availabilityPhrase && stayTimePhrase;
    }

    private boolean containsAny(String normalized, List<String> phrases) {
        return phrases.stream().anyMatch(normalized::contains);
    }
}
