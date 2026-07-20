package com.hotel.backend.service;

import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.entity.User;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.repository.UserRepository;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private SendGrid verificationSendGrid;
    @Mock
    private SendGrid transactionalSendGrid;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ReservationRepository reservationRepository;

    private EmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(
                verificationSendGrid,
                transactionalSendGrid,
                userRepository,
                reservationRepository);
        ReflectionTestUtils.setField(emailService, "verificationFrom", "verify@example.com");
        ReflectionTestUtils.setField(emailService, "transactionalFrom", "contact@example.com");
        ReflectionTestUtils.setField(emailService, "templateId", "verification-template");
        ReflectionTestUtils.setField(emailService, "verificationLink", "http://localhost:8080/auth/confirm-email");
    }

    @Test
    void contactAndTransactionalMessagesUseTransactionalSendGrid() throws Exception {
        when(transactionalSendGrid.api(any(Request.class))).thenReturn(new Response(202, "", null));

        emailService.send("guest@example.com", "Phản hồi liên hệ", "Nội dung phản hồi");

        verify(transactionalSendGrid).api(any(Request.class));
        verify(verificationSendGrid, never()).api(any(Request.class));
    }

    @Test
    void registrationVerificationUsesVerificationSendGrid() throws Exception {
        User user = User.builder()
                .fullName("Nguyen Van A")
                .username("nguyenvana")
                .email("guest@example.com")
                .phone("0900000000")
                .status(UserStatus.PENDING_VERIFICATION)
                .build();
        when(userRepository.findByEmail("guest@example.com")).thenReturn(Optional.of(user));
        when(verificationSendGrid.api(any(Request.class))).thenReturn(new Response(202, "", null));

        emailService.emailVerification("guest@example.com", "Nguyen Van A");

        verify(verificationSendGrid).api(any(Request.class));
        verify(transactionalSendGrid, never()).api(any(Request.class));
        verify(userRepository).save(user);
    }

    @Test
    void failedVerificationDeliveryKeepsThePreviousValidCode() throws Exception {
        LocalDateTime previousExpiry = LocalDateTime.now().plusHours(4);
        User user = User.builder()
                .fullName("Nguyen Van A")
                .username("nguyenvana")
                .email("guest@example.com")
                .phone("0900000000")
                .status(UserStatus.PENDING_VERIFICATION)
                .verificationCode("previous-code-hash")
                .verificationExpiresAt(previousExpiry)
                .build();
        when(userRepository.findByEmail("guest@example.com")).thenReturn(Optional.of(user));
        when(verificationSendGrid.api(any(Request.class))).thenReturn(new Response(500, "failed", null));

        assertThatThrownBy(() -> emailService.emailVerification("guest@example.com", "Nguyen Van A"))
                .isInstanceOf(java.io.IOException.class);

        assertThat(user.getVerificationCode()).isEqualTo("previous-code-hash");
        assertThat(user.getVerificationExpiresAt()).isEqualTo(previousExpiry);
        verify(userRepository, org.mockito.Mockito.times(2)).save(user);
    }
}
