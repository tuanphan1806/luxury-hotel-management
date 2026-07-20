package com.hotel.backend.service;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class AuthCookieService {

    private final boolean secure;
    private final long refreshExpiryDays;
    private final String sameSite;

    public AuthCookieService(
            @Value("${app.auth-cookie.secure:true}") boolean secure,
            @Value("${jwt.expiryDay:7}") long refreshExpiryDays,
            @Value("${app.auth-cookie.same-site:Lax}") String sameSite) {
        this.secure = secure;
        this.refreshExpiryDays = refreshExpiryDays;
        this.sameSite = normalizeSameSite(sameSite);
        if ("None".equals(this.sameSite) && !secure) {
            throw new IllegalArgumentException("SameSite=None requires a Secure refresh cookie");
        }
    }

    public void setRefreshToken(HttpServletResponse response, String refreshToken) {
        addCookie(response, refreshToken, Duration.ofDays(refreshExpiryDays));
    }

    public void clearRefreshToken(HttpServletResponse response) {
        addCookie(response, "", Duration.ZERO);
    }

    private void addCookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from("refreshToken", value == null ? "" : value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static String normalizeSameSite(String value) {
        if (value == null) {
            throw new IllegalArgumentException("app.auth-cookie.same-site must not be null");
        }
        return switch (value.trim().toLowerCase()) {
            case "lax" -> "Lax";
            case "strict" -> "Strict";
            case "none" -> "None";
            default -> throw new IllegalArgumentException(
                    "app.auth-cookie.same-site must be Lax, Strict, or None");
        };
    }
}
