package com.hotel.backend.service;

import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.dto.request.ResetPasswordRequest;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.UserRepository;
import com.hotel.backend.repository.UserTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserTokenRepository userTokenRepository;
    @Mock
    private EmailService emailService;
    private BCryptPasswordEncoder passwordEncoder;
    private PasswordResetService passwordResetService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        passwordResetService = new PasswordResetService(
                userRepository,
                userTokenRepository,
                passwordEncoder,
                emailService);
    }

    @Test
    void requestAndResetUseOneTimeHashedToken() {
        User user = User.builder()
                .fullName("Nguyen Van A")
                .username("nguyenvana")
                .email("guest@example.com")
                .phone("0900000000")
                .password(passwordEncoder.encode("OldPassword1!"))
                .status(UserStatus.ACTIVE)
                .build();
        user.setId(7L);
        when(userRepository.findByEmailIgnoreCase("guest@example.com")).thenReturn(Optional.of(user));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        passwordResetService.requestReset(" Guest@Example.com ");

        assertThat(user.getPasswordResetTokenHash()).hasSize(64);
        assertThat(user.getPasswordResetExpiresAt()).isNotNull();

        ArgumentCaptor<String> resetLinkCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordReset(
                org.mockito.ArgumentMatchers.eq("guest@example.com"),
                org.mockito.ArgumentMatchers.eq("Nguyen Van A"),
                resetLinkCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(30L));
        String encodedToken = resetLinkCaptor.getValue().split("reset-password\\?token=")[1].trim();
        String rawToken = URLDecoder.decode(encodedToken, StandardCharsets.UTF_8);
        assertThat(rawToken).isNotEqualTo(user.getPasswordResetTokenHash());

        when(userRepository.findByPasswordResetTokenHashAndPasswordResetExpiresAtAfter(
                org.mockito.ArgumentMatchers.eq(user.getPasswordResetTokenHash()),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(user));
        when(userTokenRepository.existsById(7L)).thenReturn(false);
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setToken(rawToken);
        request.setPassword("NewPassword1!");
        request.setConfirmPassword("NewPassword1!");

        passwordResetService.resetPassword(request);

        assertThat(passwordEncoder.matches("NewPassword1!", user.getPassword())).isTrue();
        assertThat(user.getPasswordResetTokenHash()).isNull();
        assertThat(user.getPasswordResetExpiresAt()).isNull();
        verify(userRepository).save(user);
    }

    @Test
    void unknownEmailDoesNotRevealAccountOrSendMail() {
        when(userRepository.findByEmailIgnoreCase("missing@example.com")).thenReturn(Optional.empty());

        passwordResetService.requestReset("missing@example.com");

        verify(emailService, never()).sendPasswordReset(any(), any(), any(), anyLong());
        verify(userRepository, never()).saveAndFlush(any());
    }
}
