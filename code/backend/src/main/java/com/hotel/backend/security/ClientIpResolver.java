package com.hotel.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Resolves a stable client IP for security controls.
 *
 * <p>Forwarded headers are ignored by default because they are controlled by the caller.
 * They are considered only when the direct peer is explicitly configured as a trusted proxy.
 * The resolver then walks the X-Forwarded-For chain from right to left and returns the first
 * non-trusted address.</p>
 */
@Component
public class ClientIpResolver {

    private static final int MAX_FORWARDED_HOPS = 20;

    private final boolean trustForwardedHeaders;
    private final Set<String> trustedProxies;

    public ClientIpResolver(
            @Value("${app.security.trust-forwarded-headers:false}") boolean trustForwardedHeaders,
            @Value("${app.security.trusted-proxies:127.0.0.1,::1}") String trustedProxies) {
        this.trustForwardedHeaders = trustForwardedHeaders;
        this.trustedProxies = parseTrustedProxies(trustedProxies);
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = normalizeIp(request == null ? null : request.getRemoteAddr());
        if (remoteAddress == null) {
            remoteAddress = "unknown";
        }

        if (!trustForwardedHeaders || !trustedProxies.contains(remoteAddress) || request == null) {
            return remoteAddress;
        }

        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddress;
        }

        String[] hops = forwardedFor.split(",");
        if (hops.length > MAX_FORWARDED_HOPS) {
            return remoteAddress;
        }

        for (int index = hops.length - 1; index >= 0; index--) {
            String candidate = normalizeIp(hops[index]);
            if (candidate == null) {
                return remoteAddress;
            }
            if (!trustedProxies.contains(candidate)) {
                return candidate;
            }
        }

        return remoteAddress;
    }

    private Set<String> parseTrustedProxies(String configuredProxies) {
        if (configuredProxies == null || configuredProxies.isBlank()) {
            return Collections.emptySet();
        }

        Set<String> parsed = new LinkedHashSet<>();
        Arrays.stream(configuredProxies.split(","))
                .map(this::normalizeIp)
                .filter(ip -> ip != null)
                .forEach(parsed::add);
        return Collections.unmodifiableSet(parsed);
    }

    private String normalizeIp(String rawValue) {
        if (rawValue == null) {
            return null;
        }

        String value = rawValue.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isBlank() || value.length() > 45 || !isIpLiteral(value)) {
            return null;
        }

        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private boolean isIpLiteral(String value) {
        if (value.indexOf(':') >= 0) {
            return value.matches("[0-9a-fA-F:.]+");
        }
        return value.matches("[0-9.]+");
    }
}
