package com.hotel.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthCookieServiceTest {

    @Test
    void supportsSecureCrossSiteCookieForGeneratedDeploymentDomains() {
        AuthCookieService service = new AuthCookieService(true, 7, "none");
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.setRefreshToken(response, "refresh-token");

        assertThat(response.getHeader("Set-Cookie"))
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=None");
    }

    @Test
    void refusesInsecureSameSiteNoneCookie() {
        assertThatThrownBy(() -> new AuthCookieService(false, 7, "None"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a Secure refresh cookie");
    }

    @Test
    void refusesUnknownSameSitePolicy() {
        assertThatThrownBy(() -> new AuthCookieService(true, 7, "cross-site"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Lax, Strict, or None");
    }
}
