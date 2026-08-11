package com.hotel.backend.service.chatbot;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class ChatPrivacyRedactor {

    private static final List<String> IDENTITY_LABELS = List.of(
            "cccd", "cmnd", "cmt", "passport", "hộ chiếu", "ho chieu", "identity card"
    );
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?84|0)[1-9](?:[ .-]?\\d){8,10}(?!\\d)"
    );
    private static final Pattern BOOKING_CODE = Pattern.compile(
            "(?i)\\b(?:RES|BOOKING|REFUND)-[A-Z0-9-]{4,}\\b"
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
        String redacted = redactEmails(text);
        redacted = BOOKING_CODE.matcher(redacted).replaceAll("[booking-code]");
        redacted = redactIdentityDocuments(redacted);
        redacted = PAYMENT_CARD_OR_ACCOUNT.matcher(redacted).replaceAll("[payment-number]");
        redacted = PHONE.matcher(redacted).replaceAll("[phone]");
        redacted = LABELED_NAME.matcher(redacted).replaceAll("[name]");
        redacted = LABELED_ADDRESS.matcher(redacted).replaceAll("[address]");
        return LONG_NUMBER.matcher(redacted).replaceAll("[sensitive-number]");
    }

    private String redactEmails(String text) {
        StringBuilder result = new StringBuilder(text);
        int scanFrom = 0;
        while (scanFrom < result.length()) {
            int atIndex = result.indexOf("@", scanFrom);
            if (atIndex < 0) {
                break;
            }

            int start = atIndex;
            while (start > 0 && isEmailLocalCharacter(result.charAt(start - 1))) {
                start--;
            }
            int end = atIndex + 1;
            while (end < result.length() && isEmailDomainCharacter(result.charAt(end))) {
                end++;
            }

            if (isValidEmailCandidate(result, start, atIndex, end)) {
                result.replace(start, end, "[email]");
                scanFrom = start + "[email]".length();
            } else {
                scanFrom = atIndex + 1;
            }
        }
        return result.toString();
    }

    private boolean isValidEmailCandidate(StringBuilder value, int start, int atIndex, int end) {
        int localLength = atIndex - start;
        int domainLength = end - atIndex - 1;
        if (localLength < 1 || localLength > 64 || domainLength < 4 || domainLength > 253) {
            return false;
        }
        int lastDot = -1;
        for (int index = atIndex + 1; index < end; index++) {
            if (value.charAt(index) == '.') {
                lastDot = index;
            }
        }
        if (lastDot <= atIndex + 1 || lastDot >= end - 2 || end - lastDot - 1 > 63) {
            return false;
        }
        for (int index = lastDot + 1; index < end; index++) {
            if (!Character.isLetter(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private boolean isEmailLocalCharacter(char value) {
        return Character.isLetterOrDigit(value)
                || value == '.' || value == '_' || value == '%' || value == '+' || value == '-';
    }

    private boolean isEmailDomainCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '.' || value == '-';
    }

    private String redactIdentityDocuments(String text) {
        StringBuilder result = new StringBuilder(text);
        int scanFrom = 0;
        while (scanFrom < result.length()) {
            String lowerCase = result.toString().toLowerCase(Locale.ROOT);
            int labelStart = -1;
            int labelLength = 0;
            for (String label : IDENTITY_LABELS) {
                int candidate = lowerCase.indexOf(label, scanFrom);
                while (candidate >= 0 && !hasWordBoundaries(lowerCase, candidate, label.length())) {
                    candidate = lowerCase.indexOf(label, candidate + 1);
                }
                if (candidate >= 0 && (labelStart < 0 || candidate < labelStart)) {
                    labelStart = candidate;
                    labelLength = label.length();
                }
            }
            if (labelStart < 0) {
                break;
            }

            int valueStart = labelStart + labelLength;
            while (valueStart < result.length() && Character.isWhitespace(result.charAt(valueStart))) {
                valueStart++;
            }
            if (valueStart < result.length()
                    && (result.charAt(valueStart) == ':'
                    || result.charAt(valueStart) == '#'
                    || result.charAt(valueStart) == '-')) {
                valueStart++;
            }
            while (valueStart < result.length() && Character.isWhitespace(result.charAt(valueStart))) {
                valueStart++;
            }

            int valueEnd = valueStart;
            while (valueEnd < result.length()
                    && valueEnd - valueStart < 20
                    && Character.isLetterOrDigit(result.charAt(valueEnd))) {
                valueEnd++;
            }
            if (valueEnd - valueStart >= 5
                    && (valueEnd == result.length() || !Character.isLetterOrDigit(result.charAt(valueEnd)))) {
                result.replace(labelStart, valueEnd, "[identity-document]");
                scanFrom = labelStart + "[identity-document]".length();
            } else {
                scanFrom = labelStart + labelLength;
            }
        }
        return result.toString();
    }

    private boolean hasWordBoundaries(String text, int start, int length) {
        int end = start + length;
        return (start == 0 || !Character.isLetterOrDigit(text.charAt(start - 1)))
                && (end == text.length() || !Character.isLetterOrDigit(text.charAt(end)));
    }
}
