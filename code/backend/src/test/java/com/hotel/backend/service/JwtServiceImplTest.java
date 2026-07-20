package com.hotel.backend.service;

import com.hotel.backend.constant.TokenType;
import com.hotel.backend.service.Impl.JwtServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceImplTest {
    private JwtServiceImpl jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtServiceImpl();
        ReflectionTestUtils.setField(jwtService, "expiryMinutes", 5L);
        ReflectionTestUtils.setField(jwtService, "expiryDay", 7L);
        ReflectionTestUtils.setField(jwtService, "accessKey", key("access-key-for-tests-32-bytes!!!"));
        ReflectionTestUtils.setField(jwtService, "refreshKey", key("refresh-key-for-tests-32-bytes!!"));
    }

    @Test
    void tokenCarriesSecurityVersionAndSubject() {
        String token = jwtService.generateAccessToken("security-user", List.of("ROLE_CUSTOMER"), 7L);

        assertThat(jwtService.extractUsername(token, TokenType.ACCESS_TOKEN)).isEqualTo("security-user");
        assertThat(jwtService.extractSecurityVersion(token, TokenType.ACCESS_TOKEN)).isEqualTo(7L);
        assertThat(jwtService.extractJti(token, TokenType.ACCESS_TOKEN)).isNotBlank();
    }

    @Test
    void accessTokenCannotBeVerifiedWithRefreshKey() {
        String token = jwtService.generateAccessToken("security-user", List.of("ROLE_CUSTOMER"), 0L);

        assertThatThrownBy(() -> jwtService.extractUsername(token, TokenType.REFRESH_TOKEN))
                .isInstanceOf(RuntimeException.class);
    }

    /** Ứng dụng phải fail-fast thay vì chỉ lỗi khi request đăng nhập đầu tiên chạy. */
    @Test
    void blankSigningKeyIsRejectedAtStartupValidation() {
        ReflectionTestUtils.setField(jwtService, "accessKey", " ");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(jwtService, "validateSigningKeys"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_ACCESS_KEY");
    }

    /** Access token và refresh token phải dùng hai secret độc lập. */
    @Test
    void reusedSigningKeyIsRejectedAtStartupValidation() {
        String sharedKey = key("shared-signing-key-for-tests-at-least-32-bytes");
        ReflectionTestUtils.setField(jwtService, "accessKey", sharedKey);
        ReflectionTestUtils.setField(jwtService, "refreshKey", sharedKey);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(jwtService, "validateSigningKeys"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("phải khác nhau");
    }

    private String key(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
