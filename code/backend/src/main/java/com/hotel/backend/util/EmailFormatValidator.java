package com.hotel.backend.util;

/**
 * Bounded, linear-time email format validation for optional operational forms.
 *
 * <p>This intentionally keeps the existing lightweight contract (one @, at
 * least one dot in the domain and no whitespace) without evaluating an
 * attacker-controlled regular expression.</p>
 */
public final class EmailFormatValidator {

    private static final int MAX_EMAIL_LENGTH = 254;

    private EmailFormatValidator() {
    }

    public static boolean isValidOptional(String value) {
        if (value == null || value.trim().isEmpty()) {
            return true;
        }
        return isValid(value.trim());
    }

    public static boolean isValid(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_EMAIL_LENGTH) {
            return false;
        }

        int atIndex = -1;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character) || Character.isSpaceChar(character)) {
                return false;
            }
            if (character == '@') {
                if (atIndex >= 0) {
                    return false;
                }
                atIndex = index;
            }
        }

        if (atIndex <= 0 || atIndex >= value.length() - 1) {
            return false;
        }

        int lastDomainDot = value.lastIndexOf('.');
        return lastDomainDot > atIndex + 1 && lastDomainDot < value.length() - 1;
    }
}
