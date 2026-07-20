package com.hotel.backend.config;

import com.hotel.backend.constant.OAuthLoginError;
import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.OAuthLoginProfile;
import com.hotel.backend.dto.response.TokenResponse;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.OAuthLoginException;
import com.hotel.backend.service.AuthCookieService;
import com.hotel.backend.service.AuthenticationService;
import com.hotel.backend.service.OAuthAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthAuthenticationSuccessHandlerTest {

    @Mock
    private OAuthAccountService oauthAccountService;
    @Mock
    private AuthenticationService authenticationService;
    @Mock
    private OAuth2AuthorizedClientService authorizedClientService;

    private OAuthAuthenticationSuccessHandler handler;

    @BeforeEach
    void setUp() {
        OAuthProperties properties = new OAuthProperties();
        properties.setFrontendCallbackUrl("https://frontend.example/oauth/callback");
        handler = new OAuthAuthenticationSuccessHandler(
                oauthAccountService,
                authenticationService,
                new AuthCookieService(false, 7, "Lax"),
                properties,
                authorizedClientService);
    }

    @Test
    void issuesJwtPairButRedirectsWithStatusOnlyAndRefreshInHttpOnlyCookie() throws Exception {
        OAuth2AuthenticationToken authentication = googleAuthentication();
        User user = activeCustomer();
        when(oauthAccountService.resolveOrCreate(any(OAuthLoginProfile.class))).thenReturn(user);
        when(authenticationService.issueTokens(user)).thenReturn(TokenResponse.builder()
                .accessToken("access.jwt.must-not-be-in-url")
                .refreshToken("refresh.jwt.must-only-be-cookie")
                .build());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession temporaryOAuthSession = (MockHttpSession) request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://frontend.example/oauth/callback?status=success")
                .doesNotContain("access.jwt", "refresh.jwt", "guest@gmail.com", "provider_token");
        assertThat(response.getHeader("Set-Cookie"))
                .contains("refreshToken=refresh.jwt.must-only-be-cookie")
                .contains("HttpOnly")
                .contains("SameSite=Lax");
        ArgumentCaptor<OAuthLoginProfile> profileCaptor = ArgumentCaptor.forClass(OAuthLoginProfile.class);
        verify(oauthAccountService).resolveOrCreate(profileCaptor.capture());
        assertThat(profileCaptor.getValue().providerSubject()).isEqualTo("google-subject");
        verify(authorizedClientService).removeAuthorizedClient("google", "google-subject");
        assertThat(temporaryOAuthSession.isInvalid()).isTrue();
    }

    @Test
    void redirectsAccountConflictUsingSafeCodeAndWithoutIssuingTokens() throws Exception {
        OAuth2AuthenticationToken authentication = googleAuthentication();
        when(oauthAccountService.resolveOrCreate(any(OAuthLoginProfile.class)))
                .thenThrow(new OAuthLoginException(OAuthLoginError.ACCOUNT_CONFLICT));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(
                new MockHttpServletRequest(), response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo(
                "https://frontend.example/oauth/callback?status=error&error=account_conflict");
        assertThat(response.getRedirectedUrl())
                .doesNotContain("guest@gmail.com", "google-subject", "token");
        assertThat(response.getHeader("Set-Cookie"))
                .contains("refreshToken=")
                .contains("Max-Age=0")
                .contains("HttpOnly");
        verify(authenticationService, never()).issueTokens(any(User.class));
    }

    private OAuth2AuthenticationToken googleAuthentication() {
        Map<String, Object> attributes = Map.of(
                "sub", "google-subject",
                "email", "guest@gmail.com",
                "email_verified", true,
                "name", "Google Guest",
                "picture", "https://images.example/avatar.png");
        DefaultOAuth2User principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                attributes,
                "sub");
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
    }

    private User activeCustomer() {
        return User.builder()
                .username("oauth_google_guest")
                .email("guest@gmail.com")
                .fullName("Google Guest")
                .type(UserType.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .emailVerified(true)
                .build();
    }
}
