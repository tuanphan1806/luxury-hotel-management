package com.hotel.backend.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthPropertiesTest {

    @Test
    void reportsOnlyProvidersWithBothClientIdAndSecret() {
        OAuthProperties properties = new OAuthProperties();
        properties.getGoogle().setClientId("google-client");
        properties.getGoogle().setClientSecret("google-secret");
        properties.getFacebook().setClientId("facebook-client-without-secret");

        assertThat(properties.configuredProviders()).containsExactly("google");
        assertThat(properties.isConfigured("GOOGLE")).isTrue();
        assertThat(properties.isConfigured("facebook")).isFalse();
    }
}
