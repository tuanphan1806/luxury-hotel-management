package com.hotel.backend.service.chatbot;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class ChatPrivacyRedactor {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"
    );
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?84|0)[1-9](?:[ .-]?\\d){8,10}(?!\\d)"
    );
    private static final Pattern BOOKING_CODE = Pattern.compile(
            "(?i)\\b(?:RES|BOOKING|REFUND)-[A-Z0-9-]{4,}\\b"
    );
    private static final Pattern IDENTITY_DOCUMENT = Pattern.compile(
            "(?iu)\\b(?:CCCD|CMND|CMT|passport|hộ chiếu|ho chieu|identity card)"
                    + "\\s*[:#-]?\\s*[A-Z0-9]{5,20}\\b"
    );
    private static final Pattern PAYMENT_CARD_OR_ACCOUNT = Pattern.compile(
            "(?<!\\d)(?:\\d[ .-]?){12,19}(?!\\d)"
    );
    private static final Pattern LABELED_NAME = Pattern.compile(
            "(?iu)\\b(?:họ tên|ho ten|full name|tên tài khoản|ten tai khoan)"
                    + "\\s*[:=-]\\s*[\\p{L}][\\p{L} .'-]{1,80}(?=;|,|\\n|$)"
    );
    private static final Pattern LABELED_ADDRESS = Pattern.compile(
            "(?iu)(?:địa chỉ|dia chi|address)\\s*[:=-]\\s*[^;\\r\\n]{3,160}"
    );
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)\\d{9,19}(?!\\d)");

    public String redact(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String redacted = EMAIL.matcher(text).replaceAll("[email]");
        redacted = BOOKING_CODE.matcher(redacted).replaceAll("[booking-code]");
        redacted = IDENTITY_DOCUMENT.matcher(redacted).replaceAll("[identity-document]");
        redacted = PAYMENT_CARD_OR_ACCOUNT.matcher(redacted).replaceAll("[payment-number]");
        redacted = PHONE.matcher(redacted).replaceAll("[phone]");
        redacted = LABELED_NAME.matcher(redacted).replaceAll("[name]");
        redacted = LABELED_ADDRESS.matcher(redacted).replaceAll("[address]");
        return LONG_NUMBER.matcher(redacted).replaceAll("[sensitive-number]");
    }
}
