package com.hotel.backend.config;

import com.hotel.backend.constant.OAuthLoginError;
import com.hotel.backend.service.AuthCookieService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "OAUTH-FAILURE-HANDLER")
public class OAuthAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private final AuthCookieService authCookieService;
    private final OAuthProperties properties;

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        log.warn("OAuth provider authentication failed with type={}", exception.getClass().getSimpleName());
        authCookieService.clearRefreshToken(response);
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        response.sendRedirect(UriComponentsBuilder
                .fromUriString(properties.normalizedFrontendCallbackUrl())
                .replaceQuery(null)
                .queryParam("status", "error")
                .queryParam("error", OAuthLoginError.PROVIDER_ERROR.getCode())
                .build()
                .encode()
                .toUriString());
    }
}
