package com.hotel.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.oauth")
public class OAuthProperties {

    private String backendBaseUrl = "http://localhost:8080";
    private String frontendCallbackUrl = "http://localhost:3000/oauth/callback";
    private ProviderCredentials google = new ProviderCredentials();
    private ProviderCredentials facebook = new ProviderCredentials();

    public List<String> configuredProviders() {
        List<String> providers = new ArrayList<>(2);
        if (google.isConfigured()) {
            providers.add("google");
        }
        if (facebook.isConfigured()) {
            providers.add("facebook");
        }
        return List.copyOf(providers);
    }

    public boolean isConfigured(String registrationId) {
        if (registrationId == null) {
            return false;
        }
        return switch (registrationId.trim().toLowerCase(Locale.ROOT)) {
            case "google" -> google.isConfigured();
            case "facebook" -> facebook.isConfigured();
            default -> false;
        };
    }

    public String normalizedBackendBaseUrl() {
        String normalized = stripTrailingSlash(backendBaseUrl);
        return normalized.isEmpty() ? "http://localhost:8080" : normalized;
    }

    public String normalizedFrontendCallbackUrl() {
        String normalized = stripTrailingSlash(frontendCallbackUrl);
        return normalized.isEmpty() ? "http://localhost:3000/oauth/callback" : normalized;
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replaceAll("/+$", "");
    }

    @Getter
    @Setter
    public static class ProviderCredentials {
        private String clientId = "";
        private String clientSecret = "";

        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
    }
}
