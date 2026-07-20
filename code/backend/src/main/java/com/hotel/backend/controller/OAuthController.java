package com.hotel.backend.controller;

import com.hotel.backend.config.OAuthProperties;
import com.hotel.backend.constant.OAuthLoginError;
import com.hotel.backend.dto.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/auth/oauth")
@RequiredArgsConstructor
public class OAuthController {

    private final OAuthProperties properties;

    @GetMapping("/providers")
    public ApiResponse<List<String>> getConfiguredProviders() {
        return ApiResponse.success(properties.configuredProviders());
    }

    @GetMapping("/authorize/{provider}")
    public void authorize(
            @PathVariable String provider,
            HttpServletResponse response) throws IOException {
        String registrationId = provider == null
                ? ""
                : provider.trim().toLowerCase(Locale.ROOT);
        if (!properties.isConfigured(registrationId)) {
            response.sendRedirect(frontendErrorRedirect(OAuthLoginError.OAUTH_NOT_CONFIGURED));
            return;
        }
        response.sendRedirect(properties.normalizedBackendBaseUrl()
                + "/oauth2/authorization/" + registrationId);
    }

    private String frontendErrorRedirect(OAuthLoginError error) {
        return UriComponentsBuilder
                .fromUriString(properties.normalizedFrontendCallbackUrl())
                .replaceQuery(null)
                .queryParam("status", "error")
                .queryParam("error", error.getCode())
                .build()
                .encode()
                .toUriString();
    }
}
