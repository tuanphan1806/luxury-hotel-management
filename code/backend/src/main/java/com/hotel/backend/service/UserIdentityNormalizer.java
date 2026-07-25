package com.hotel.backend.service;

import java.util.Locale;

/**
 * Canonical normalization rules used by the existing user-management flow.
 */
public final class UserIdentityNormalizer {

    private UserIdentityNormalizer() {
    }

    public static String username(String username) {
        return username == null ? "" : username.trim();
    }

    public static String email(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
