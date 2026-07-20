package com.hotel.backend.config;

import com.hotel.backend.constant.OAuthLoginError;
import com.hotel.backend.constant.OAuthProvider;
import com.hotel.backend.dto.OAuthLoginProfile;
import com.hotel.backend.dto.response.TokenResponse;
import com.hotel.backend.exception.OAuthLoginException;
import com.hotel.backend.service.AuthCookieService;
import com.hotel.backend.service.AuthenticationService;
import com.hotel.backend.service.OAuthAccountService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "OAUTH-SUCCESS-HANDLER")
public class OAuthAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final OAuthAccountService oauthAccountService;
    private final AuthenticationService authenticationService;
    private final AuthCookieService authCookieService;
    private final OAuthProperties properties;
    private final OAuth2AuthorizedClientService authorizedClientService;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        String status = "success";
        String error = null;
        OAuth2AuthenticationToken oauthToken = authentication instanceof OAuth2AuthenticationToken token
                ? token
                : null;

        try {
            if (oauthToken == null) {
                throw new OAuthLoginException(OAuthLoginError.PROVIDER_ERROR);
            }
            OAuthLoginProfile profile = toProfile(oauthToken);
            var user = oauthAccountService.resolveOrCreate(profile);
            TokenResponse tokens = authenticationService.issueTokens(user);
            authCookieService.setRefreshToken(response, tokens.getRefreshToken());
        } catch (OAuthLoginException exception) {
            status = "error";
            error = exception.getError().getCode();
            authCookieService.clearRefreshToken(response);
        } catch (RuntimeException exception) {
            status = "error";
            error = OAuthLoginError.PROVIDER_ERROR.getCode();
            authCookieService.clearRefreshToken(response);
            log.error("OAuth callback failed with type={}", exception.getClass().getSimpleName());
            log.debug("OAuth callback failure detail", exception);
        } finally {
            removeProviderToken(oauthToken);
            clearTemporarySession(request);
        }

        response.sendRedirect(buildFrontendRedirect(status, error));
    }

    private OAuthLoginProfile toProfile(OAuth2AuthenticationToken authentication) {
        OAuthProvider provider;
        try {
            provider = OAuthProvider.valueOf(
                    authentication.getAuthorizedClientRegistrationId().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new OAuthLoginException(OAuthLoginError.PROVIDER_ERROR);
        }

        Map<String, Object> attributes = authentication.getPrincipal().getAttributes();
        String subject = asString(attributes.get(provider == OAuthProvider.GOOGLE ? "sub" : "id"));
        if (subject == null) {
            subject = authentication.getPrincipal().getName();
        }

        return new OAuthLoginProfile(
                provider,
                subject,
                asString(attributes.get("email")),
                asBoolean(attributes.get("email_verified")),
                asString(attributes.get("hd")),
                asString(attributes.get("name")),
                extractPicture(attributes.get("picture"))
        );
    }

    private String extractPicture(Object value) {
        if (value instanceof String picture) {
            return picture;
        }
        if (value instanceof Map<?, ?> picture) {
            Object data = picture.get("data");
            if (data instanceof Map<?, ?> dataMap) {
                return asString(dataMap.get("url"));
            }
        }
        return null;
    }

    private boolean asBoolean(Object value) {
        return value instanceof Boolean bool
                ? bool
                : value != null && Boolean.parseBoolean(value.toString());
    }

    private String asString(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString().trim();
    }

    private void removeProviderToken(OAuth2AuthenticationToken authentication) {
        if (authentication == null) {
            return;
        }
        try {
            authorizedClientService.removeAuthorizedClient(
                    authentication.getAuthorizedClientRegistrationId(),
                    authentication.getName());
        } catch (RuntimeException exception) {
            log.warn("Could not remove temporary OAuth authorized client for provider={}",
                    authentication.getAuthorizedClientRegistrationId());
        }
    }

    private void clearTemporarySession(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    private String buildFrontendRedirect(String status, String error) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(properties.normalizedFrontendCallbackUrl())
                .replaceQuery(null)
                .queryParam("status", status);
        if (error != null) {
            builder.queryParam("error", error);
        }
        return builder.build().encode().toUriString();
    }
}
