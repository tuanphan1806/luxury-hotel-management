package com.hotel.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorsConfigTest {

    @Test
    void readsAndTrimsExplicitAllowedOrigins() {
        CorsConfig config = new CorsConfig("https://hotel.example, https://admin.example");
        UrlBasedCorsConfigurationSource source = config.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rooms");

        CorsConfiguration cors = source.getCorsConfiguration(request);

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedOrigins())
                .containsExactly("https://hotel.example", "https://admin.example");
        assertThat(cors.getAllowCredentials()).isTrue();
    }

    @Test
    void rejectsWildcardWhenCredentialedRequestsAreEnabled() {
        assertThatThrownBy(() -> new CorsConfig("*"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit origins");
    }

    @Test
    void localDefaultAllowsBothHostnameAndNumericLoopback() {
        CorsConfig config = new CorsConfig(CorsConfig.DEFAULT_ALLOWED_ORIGINS);
        CorsConfiguration cors = config.corsConfigurationSource()
                .getCorsConfiguration(new MockHttpServletRequest("OPTIONS", "/api/chat"));

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedOrigins()).contains(
                "http://localhost:3000",
                "http://127.0.0.1:3000",
                "http://localhost:3001",
                "http://127.0.0.1:3001"
        );
    }
}
