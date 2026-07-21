package com.hotel.backend.controller;

import com.hotel.backend.config.OAuthProperties;
import com.hotel.backend.constant.OAuthLoginError;
import com.hotel.backend.dto.request.OAuthExchangeRequest;
import com.hotel.backend.dto.request.OAuthProfileCompletionRequest;
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.dto.response.TokenResponse;
import com.hotel.backend.security.ClientIpResolver;
import com.hotel.backend.service.AuthCookieService;
import com.hotel.backend.service.AuthRateLimitService;
import com.hotel.backend.service.OAuthLoginTicketService;
import com.hotel.backend.service.OAuthProfileCompletionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static com.hotel.backend.util.SecurityTokenHasher.sha256;

@RestController
@RequestMapping("/auth/oauth")
@RequiredArgsConstructor
public class OAuthController {

    private final OAuthProperties properties;
    private final OAuthLoginTicketService loginTicketService;
    private final OAuthProfileCompletionService profileCompletionService;
    private final AuthCookieService authCookieService;
    private final AuthRateLimitService authRateLimitService;
    private final ClientIpResolver clientIpResolver;

    @GetMapping("/providers")
    public ApiResponse<List<String>> getConfiguredProviders() {
        return ApiResponse.success(properties.configuredProviders());
    }

    @PostMapping("/exchange")
    public ResponseEntity<TokenResponse> exchange(
            @RequestBody @Valid OAuthExchangeRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        String clientIp = clientIpResolver.resolve(httpRequest);
        authRateLimitService.check("oauth-exchange-ip:" + clientIp, 30, Duration.ofMinutes(15));

        TokenResponse tokens = loginTicketService.exchange(request.ticket());
        authCookieService.setRefreshToken(response, tokens.getRefreshToken());
        return ResponseEntity.ok(TokenResponse.builder()
                .accessToken(tokens.getAccessToken())
                .build());
    }

    @PostMapping("/complete-profile")
    public ApiResponse<Void> completeProfile(
            @RequestBody @Valid OAuthProfileCompletionRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = clientIpResolver.resolve(httpRequest);
        authRateLimitService.check("oauth-profile-ip:" + clientIp, 20, Duration.ofMinutes(15));
        authRateLimitService.check(
                "oauth-profile-ticket:" + sha256(request.ticket().trim()),
                5,
                Duration.ofMinutes(15));
        profileCompletionService.complete(request.ticket(), request.email());
        return ApiResponse.success("Đã gửi liên kết xác minh tới email của bạn");
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
