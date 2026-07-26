package com.hotel.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.repository.UserRepository;
import com.hotel.backend.service.EmailService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticationFlowIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;

    @MockitoBean
    EmailService emailService;

    @Test
    void registerValidationRejectsInvalidEmailAndShortPasswordWithoutCreatingUser() throws Exception {
        String suffix = suffix();
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "QA Invalid",
                                  "username": "qa_invalid_%s",
                                  "email": "not-an-email",
                                  "phone": "09%s",
                                  "password": "123456"
                                }
                                """.formatted(suffix, suffix)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());

        assertThat(userRepository.findByUsernameIgnoreCase("qa_invalid_" + suffix)).isEmpty();
    }

    @Test
    void pendingAccountCannotLoginAndUsesTheSameSafeUnauthorizedContract() throws Exception {
        RegisteredAccount account = registerPendingAccount();

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(account.username(), account.password())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Sai tên đăng nhập hoặc mật khẩu"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(account.username(), "definitely-wrong")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Sai tên đăng nhập hoặc mật khẩu"));
    }

    @Test
    void activatedCustomerCanLoginRefreshOnceAndLogoutThroughHttpOnlyCookieContract() throws Exception {
        RegisteredAccount account = registerPendingAccount();
        var user = userRepository.findByUsernameIgnoreCase(account.username()).orElseThrow();
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(true);
        userRepository.saveAndFlush(user);

        var login = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(account.email(), account.password())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(cookie().httpOnly("refreshToken", true))
                .andReturn();

        String firstAccessToken = json(login.getResponse().getContentAsString())
                .path("accessToken").asText();
        Cookie firstRefreshCookie = login.getResponse().getCookie("refreshToken");
        assertThat(firstAccessToken).isNotBlank();
        assertThat(firstRefreshCookie).isNotNull();

        var refresh = mockMvc.perform(post("/auth/refresh-token")
                        .cookie(firstRefreshCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(cookie().httpOnly("refreshToken", true))
                .andReturn();

        String rotatedAccessToken = json(refresh.getResponse().getContentAsString())
                .path("accessToken").asText();
        Cookie rotatedRefreshCookie = refresh.getResponse().getCookie("refreshToken");
        assertThat(rotatedAccessToken).isNotBlank().isNotEqualTo(firstAccessToken);
        assertThat(rotatedRefreshCookie).isNotNull();
        assertThat(rotatedRefreshCookie.getValue()).isNotEqualTo(firstRefreshCookie.getValue());

        mockMvc.perform(post("/auth/refresh-token").cookie(firstRefreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().maxAge("refreshToken", 0));

        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + rotatedAccessToken)
                        .cookie(rotatedRefreshCookie))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("refreshToken", 0));

        mockMvc.perform(post("/auth/refresh-token").cookie(rotatedRefreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().maxAge("refreshToken", 0));
    }

    @Test
    void refreshWithoutCookieFailsClosedAndClearsAnyBrowserCookie() throws Exception {
        mockMvc.perform(post("/auth/refresh-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().maxAge("refreshToken", 0));
    }

    private RegisteredAccount registerPendingAccount() throws Exception {
        String suffix = suffix();
        String username = "qa_auth_" + suffix;
        String email = username + "@example.test";
        String phone = "08" + suffix;
        String password = "QaPassword123!";

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "QA Authentication",
                                  "username": "%s",
                                  "email": "%s",
                                  "phone": "%s",
                                  "address": "Local QA",
                                  "password": "%s"
                                }
                                """.formatted(username, email, phone, password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data").isNumber());

        var user = userRepository.findByUsernameIgnoreCase(username).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
        assertThat(user.isEmailVerified()).isFalse();
        return new RegisteredAccount(username, email, password);
    }

    private String loginBody(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(
                java.util.Map.of("username", username, "password", password));
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private record RegisteredAccount(String username, String email, String password) {}
}
