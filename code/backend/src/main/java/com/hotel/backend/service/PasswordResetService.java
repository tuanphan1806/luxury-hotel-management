package com.hotel.backend.service;

import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.ReservationAuditAction;
import com.hotel.backend.dto.request.ResetPasswordRequest;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.UserRepository;
import com.hotel.backend.repository.UserTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "PASSWORD-RESET-SERVICE")
public class PasswordResetService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final UserTokenRepository userTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final ReservationAuditService reservationAuditService;

    @Value("${app.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl = "http://localhost:3000";

    @Value("${app.password-reset-ttl-minutes:30}")
    private long resetTtlMinutes = 30;

    @Value("${app.hotel-name:Luxury Hotel}")
    private String hotelName = "Luxury Hotel";

    @Transactional
    public void requestReset(String email) {
        String normalizedEmail = email.trim().toLowerCase();
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);

        // Luôn trả cùng một phản hồi ở controller để tránh dò tài khoản bằng email.
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            log.info("Password reset requested for an unavailable account");
            return;
        }

        String rawToken = generateToken();
        user.setPasswordResetTokenHash(hash(rawToken));
        user.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(resetTtlMinutes));
        userRepository.saveAndFlush(user);

        String resetLink = frontendBaseUrl.replaceAll("/+$", "")
                + "/reset-password?token="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
        String content = """
                Xin chào %s,

                Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn.
                Liên kết đặt lại mật khẩu (có hiệu lực trong %d phút):
                %s

                Nếu bạn không thực hiện yêu cầu này, hãy bỏ qua email. Mật khẩu hiện tại vẫn được giữ nguyên.
                """.formatted(user.getFullName(), resetTtlMinutes, resetLink);

        emailService.send(user.getEmail(), "Đặt lại mật khẩu " + hotelName, content);
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new AppException(ErrorCode.INVALID_REQUEST, "Xác nhận mật khẩu không khớp");
        }

        User user = userRepository
                .findByPasswordResetTokenHashAndPasswordResetExpiresAtAfter(
                        hash(request.getToken().trim()),
                        LocalDateTime.now())
                .orElseThrow(() -> new AppException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID));

        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.invalidateSessions();
        clearResetToken(user);
        userRepository.save(user);

        // Các phiên staff/admin được lưu tập trung sẽ buộc phải đăng nhập lại.
        if (userTokenRepository.existsById(user.getId())) {
            userTokenRepository.deleteById(user.getId());
        }
        reservationAuditService.recordTargetForUser(
                user,
                "USER",
                String.valueOf(user.getId()),
                ReservationAuditAction.PASSWORD_RESET_COMPLETED,
                "Hoàn tất đặt lại mật khẩu",
                java.util.Map.of("sessionsInvalidated", true));
    }

    private void clearResetToken(User user) {
        user.setPasswordResetTokenHash(null);
        user.setPasswordResetExpiresAt(null);
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
