package com.hotel.backend.service.chatbot;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure input policy extracted from the chatbot orchestration.
 *
 * <p>The constants and matching rules are intentionally kept byte-for-byte
 * equivalent to the previous {@code ChatBotService} implementation.</p>
 */
public final class ChatInputPolicy {

    private static final int MAX_QUESTION_LENGTH = 500;

    private static final List<String> HOTEL_KEYWORDS = List.of(
            "khach san", "phong", "dat phong", "gia", "tien", "thanh toan",
            "nhan phong", "tra phong", "check in", "check out", "tien ich",
            "dich vu", "wifi", "ho boi", "nha hang", "bua sang", "an sang",
            "buffet", "giuong", "tang", "gallery", "hinh anh", "anh",
            "dia chi", "lien he", "le tan", "don dep", "hanh ly", "xe dua don",
            "dua don", "san bay", "parking", "dau xe", "vat nuoi", "tre em",
            "nguoi lon", "phu thu", "huy phong", "doi lich", "con trong",
            "trong khong", "co khong", "may gio", "view", "ban cong", "bon tam",
            "may lanh", "dieu hoa", "mini bar", "laundry", "giat ui", "spa",
            "gym", "fitness", "bar", "cafe", "ca phe", "danh gia", "rating",
            "review", "sao", "dep khong", "gan bien", "gan trung tam",
            "gia dinh", "nguoi khuyet tat", "khong hut thuoc", "hut thuoc",
            "yen tinh", "thang may", "dat coc", "hoan tien", "hoa don", "vat",
            "the tin dung", "chuyen khoan", "tien mat", "sepay", "vietqr", "an toan",
            "chinh sach", "giay to", "can cuoc", "ho chieu", "cong tac",
            "cuoi tuan", "phuong tien cong cong", "bien", "trung tam thanh pho",
            "reservation", "booking", "room", "facility", "hotel", "breakfast",
            "restaurant", "pool", "airport", "available", "availability"
    );

    private static final List<String> HOTEL_QUESTION_PHRASES = List.of(
            "o day co", "ben minh co", "khach san co", "cho minh hoi",
            "toi muon hoi", "minh muon hoi", "co con", "con khong"
    );

    private static final List<Pattern> HOTEL_KEYWORD_PATTERNS = phrasePatterns(HOTEL_KEYWORDS);
    private static final List<Pattern> HOTEL_QUESTION_PATTERNS = phrasePatterns(HOTEL_QUESTION_PHRASES);

    private static final List<String> GREETING_KEYWORDS = List.of(
            "xin chao", "chao", "hello", "hi", "hey", "alo"
    );

    private static final List<String> PROMPT_INJECTION_PATTERNS = List.of(
            "bo qua", "ignore", "previous instruction", "system prompt",
            "developer message", "jailbreak", "khong gioi han", "dong vai",
            "roleplay", "prompt injection", "tra loi bat ky", "khong can tuan thu"
    );

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Chuẩn hóa input để tránh control character, prompt quá dài và whitespace bất thường.
     */
    public String sanitizeQuestion(String question) {
        if (question == null) {
            return "";
        }

        String sanitized = question
                .replace('\u0000', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", " ");

        sanitized = WHITESPACE.matcher(sanitized).replaceAll(" ").trim();

        if (sanitized.length() > MAX_QUESTION_LENGTH) {
            sanitized = sanitized.substring(0, MAX_QUESTION_LENGTH).trim();
        }

        return sanitized;
    }

    public boolean looksLikePromptInjection(String text) {
        String normalized = normalizeForMatching(text);
        return PROMPT_INJECTION_PATTERNS.stream().anyMatch(normalized::contains);
    }

    public boolean isGreeting(String text) {
        String normalized = normalizeForMatching(text);
        return normalized.length() <= 40
                && GREETING_KEYWORDS.stream().anyMatch(greeting ->
                        normalized.equals(greeting) || normalized.startsWith(greeting + " ")
                );
    }

    public boolean isHotelRelated(String text) {
        String normalized = normalizeForMatching(text);
        return HOTEL_KEYWORD_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(normalized).find())
                || HOTEL_QUESTION_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }

    public String normalizeForMatching(String text) {
        String withoutAccents = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .replace('Đ', 'D');
        return WHITESPACE.matcher(withoutAccents.toLowerCase(Locale.ROOT))
                .replaceAll(" ")
                .trim();
    }

    private static List<Pattern> phrasePatterns(List<String> phrases) {
        return phrases.stream()
                .map(phrase -> Pattern.compile(
                        "(?<![\\p{L}\\p{N}])" + Pattern.quote(phrase) + "(?![\\p{L}\\p{N}])"))
                .toList();
    }
}
