package com.hotel.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.oauth.google.client-id=test-google-client",
        "app.oauth.google.client-secret=test-google-secret",
        "app.oauth.facebook.client-id=test-facebook-client",
        "app.oauth.facebook.client-secret=test-facebook-secret",
        "app.oauth.backend-base-url=https://backend.example",
        "app.oauth.frontend-callback-url=https://frontend.example/oauth/callback"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthConfiguredProviderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void providerEndpointReportsOnlyConfiguredProviders() throws Exception {
        mockMvc.perform(get("/auth/oauth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("google"))
                .andExpect(jsonPath("$.data[1]").value("facebook"));
    }

    @Test
    void authorizeWrapperUsesConfiguredBackendOrigin() throws Exception {
        mockMvc.perform(get("/auth/oauth/authorize/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("https://backend.example/oauth2/authorization/google"));
    }

    @Test
    void defaultAuthorizationCodeFlowCreatesTemporaryOAuthSession() throws Exception {
        var result = mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String decodedRedirect = URLDecoder.decode(
                result.getResponse().getRedirectedUrl(), StandardCharsets.UTF_8);
        assertThat(decodedRedirect)
                .startsWith("https://accounts.google.com/o/oauth2/v2/auth")
                .contains("client_id=test-google-client")
                .contains("redirect_uri=https://backend.example/login/oauth2/code/google")
                .doesNotContain("test-google-secret");
        assertThat(result.getRequest().getSession(false)).isNotNull();
    }

    @Test
    void exchangeEndpointIsPublicButRejectsUnknownOneTimeTicket() throws Exception {
        mockMvc.perform(post("/auth/oauth/exchange")
                        .contentType(APPLICATION_JSON)
                        .content("{\"ticket\":\"unknown-one-time-ticket\"}"))
                .andExpect(status().isBadRequest());
    }
}
