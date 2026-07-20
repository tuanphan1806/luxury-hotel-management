package com.hotel.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.oauth.google.client-id=",
        "app.oauth.google.client-secret=",
        "app.oauth.facebook.client-id=",
        "app.oauth.facebook.client-secret=",
        "app.oauth.frontend-callback-url=https://frontend.example/oauth/callback"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthProviderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reportsNoProvidersAndApplicationStillStartsWithoutCredentials() throws Exception {
        mockMvc.perform(get("/auth/oauth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void authorizeWrapperReturnsSafeNotConfiguredRedirect() throws Exception {
        mockMvc.perform(get("/auth/oauth/authorize/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "https://frontend.example/oauth/callback?status=error&error=oauth_not_configured"));
    }

    @Test
    void regularApiChainRemainsStateless() throws Exception {
        var result = mockMvc.perform(get("/api/user/me"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }
}
