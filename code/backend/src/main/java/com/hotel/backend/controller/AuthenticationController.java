package com.hotel.backend.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hotel.backend.dto.request.SignInRequest;
import com.hotel.backend.dto.request.ForgotPasswordRequest;
import com.hotel.backend.dto.request.ResendVerificationRequest;
import com.hotel.backend.dto.request.ResetPasswordRequest;
import com.hotel.backend.dto.request.UserCreationRequest;
import com.hotel.backend.dto.request.VerifyEmailRequest;
import com.hotel.backend.dto.response.TokenResponse;
import com.hotel.backend.dto.response.ApiResponse;
import com.hotel.backend.service.AuthenticationService;
import com.hotel.backend.service.PasswordResetService;
import com.hotel.backend.service.UserService;
import com.hotel.backend.service.AuthRateLimitService;
import com.hotel.backend.service.AuthCookieService;
import com.hotel.backend.service.OAuthPostVerificationLoginService;
import com.hotel.backend.config.OAuthProperties;
import com.hotel.backend.security.ClientIpResolver;
import static com.hotel.backend.util.SecurityTokenHasher.sha256;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.util.UriComponentsBuilder;


@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j(topic = "AUTHENTICATION-CONTROLLER")
@Tag(name = "Authentication Controller")
// @PreAuthorize("hasAnyRole('ADMIN', 'STAFF')")
public class AuthenticationController {

    private final AuthenticationService authenticationService;
    private final PasswordResetService passwordResetService;
    private final AuthRateLimitService authRateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final AuthCookieService authCookieService;
    private final OAuthPostVerificationLoginService oauthPostVerificationLoginService;
    private final OAuthProperties oauthProperties;
    @Value("${app.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;
    @Operation(summary = "Sign in", description = "Return an access token and set the rotated refresh token in an HttpOnly cookie")
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> getAccessToken(
            @RequestBody @Valid SignInRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        log.info("Access token request");
        String clientIp = clientIpResolver.resolve(httpRequest);
        authRateLimitService.check("login-ip:" + clientIp, 50, Duration.ofMinutes(15));
        String rateLimitKey = "login:" + clientIp + ":" + request.getUsername().trim().toLowerCase(Locale.ROOT);
        authRateLimitService.check(rateLimitKey, 10, Duration.ofMinutes(15));
        TokenResponse tokens = authenticationService.getAccessToken(request);
        authRateLimitService.reset(rateLimitKey);
        authCookieService.setRefreshToken(response, tokens.getRefreshToken());
        return ResponseEntity.ok(TokenResponse.builder().accessToken(tokens.getAccessToken()).build());
    }
    
    
    @Operation(summary = "Refresh token", description = "Rotate the HttpOnly refresh cookie and return a new access token")
    @PostMapping("/refresh-token")
    public ResponseEntity<TokenResponse> getRefreshToken(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            HttpServletResponse response) {
        log.info("Refresh token request");
        try {
            TokenResponse tokens = authenticationService.getRefreshToken(asBearer(refreshToken));
            authCookieService.setRefreshToken(response, tokens.getRefreshToken());
            return ResponseEntity.ok(TokenResponse.builder().accessToken(tokens.getAccessToken()).build());
        } catch (RuntimeException exception) {
            authCookieService.clearRefreshToken(response);
            throw exception;
        }
    }

    @Operation(summary = "Logout", description = "Invalidate access token")
    @PostMapping("/logout")
    public ResponseEntity<Object> logout(
            @RequestHeader("Authorization") String accessToken,
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            HttpServletResponse response) {
        log.info("Logout request");
        authenticationService.logout(accessToken, asBearer(refreshToken));
        authCookieService.clearRefreshToken(response);
        Map<String,Object>result=new LinkedHashMap<>();
        result.put("status", HttpStatus.OK.value());
        result.put("message","Logout successfully");
        result.put("data","");
        
        return ResponseEntity.ok(result);
    }

    private String asBearer(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return null;
        }
        return "Bearer " + refreshToken.trim();
    }

    private final UserService userService;

    @Operation(summary = "Create User", description = "API register new user to database")
    @PostMapping("/register")
    public ResponseEntity<Object> CreateUser(
            @RequestBody @Valid UserCreationRequest request,
            HttpServletRequest httpRequest){
        String clientIp = clientIpResolver.resolve(httpRequest);
        String emailKey = sha256(request.getEmail().trim().toLowerCase(Locale.ROOT));
        authRateLimitService.check("register-ip:" + clientIp, 20, Duration.ofHours(1));
        authRateLimitService.check("register:" + clientIp + ":" + emailKey, 5, Duration.ofHours(1));
        Map<String,Object>result=new LinkedHashMap<>();
        result.put("status", HttpStatus.CREATED.value());
        result.put("message","Register successfully. Please check your email to verify your account.");

        result.put("data",userService.save(request));
        
        return new ResponseEntity<>(result,HttpStatus.CREATED);
    }

    @Operation(summary = "Forgot password", description = "Send a time-limited password reset link")
    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(
            @RequestBody @Valid ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = clientIpResolver.resolve(httpRequest);
        authRateLimitService.check(
                "forgot:" + clientIp + ":" + sha256(request.getEmail().trim().toLowerCase(Locale.ROOT)),
                5,
                Duration.ofHours(1));
        authRateLimitService.check("forgot-ip:" + clientIp, 20, Duration.ofHours(1));
        passwordResetService.requestReset(request.getEmail());
        return ApiResponse.success("Nếu email tồn tại, hướng dẫn đặt lại mật khẩu đã được gửi");
    }

    @Operation(summary = "Reset password", description = "Set a new password using a one-time reset token")
    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(
            @RequestBody @Valid ResetPasswordRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = clientIpResolver.resolve(httpRequest);
        authRateLimitService.check(
                "reset:" + clientIp + ":" + sha256(request.getToken().trim()),
                10,
                Duration.ofHours(1));
        passwordResetService.resetPassword(request);
        return ApiResponse.success("Đặt lại mật khẩu thành công");
    }

    @Operation(summary = "Resend verification email", description = "API resend verification email for pending users")
    @PostMapping("/resend-verification")
    public ResponseEntity<Object> resendVerification(
            @RequestBody @Valid ResendVerificationRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = clientIpResolver.resolve(httpRequest);
        authRateLimitService.check(
                "verify:" + clientIp + ":" + sha256(request.getEmail().trim().toLowerCase(Locale.ROOT)),
                5,
                Duration.ofHours(1));
        authRateLimitService.check("verify-ip:" + clientIp, 20, Duration.ofHours(1));
        userService.resendVerification(request.getEmail());
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("status", HttpStatus.OK.value());
        result.put("message", "Verification email sent");
        result.put("data", "");
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Verify email", description = "API verify user email by secret code")
    @PostMapping("/verify-email")
    public ResponseEntity<Object> verifyEmail(@RequestBody @Valid VerifyEmailRequest request) {
        userService.verifyEmail(request.getSecretCode());
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("status", HttpStatus.OK.value());
        result.put("message", "Email verified successfully");
        result.put("data", "");
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Confirm email", description = "API verify user email by secret code and redirect after confirmation")
    @GetMapping("/confirm-email")
    public void confirmEmail(@RequestParam String secretCode ,HttpServletResponse response) throws IOException{
        String redirectUrl;
        try {
            Long verifiedUserId = userService.verifyEmail(secretCode);
            String loginTicket = null;
            try {
                loginTicket = oauthPostVerificationLoginService
                        .issueLoginTicketIfEligible(verifiedUserId)
                        .orElse(null);
            } catch (RuntimeException oauthException) {
                // Email verification already committed. A transient ticket error
                // must fall back to normal login instead of reporting a false
                // verification failure.
                log.warn("Could not issue post-verification OAuth ticket for userId={}", verifiedUserId);
            }

            if (loginTicket != null) {
                redirectUrl = UriComponentsBuilder
                        .fromUriString(oauthProperties.normalizedFrontendCallbackUrl())
                        .replaceQuery(null)
                        .queryParam("status", "success")
                        .queryParam("ticket", loginTicket)
                        .build()
                        .encode()
                        .toUriString();
            } else {
                redirectUrl = normalizedFrontendBaseUrl() + "/login?verification=success";
            }
        } catch (Exception e) {
            log.error("Confirm Email failed: {}", e.getMessage());
            redirectUrl = normalizedFrontendBaseUrl() + "/login?verification=failed";
        }
        response.sendRedirect(redirectUrl);
    }

    private String normalizedFrontendBaseUrl() {
        return frontendBaseUrl.replaceAll("/+$", "");
    }
}
