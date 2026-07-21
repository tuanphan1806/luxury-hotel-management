package com.hotel.backend.service;

import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.entity.CustomerProfile;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.ReservationRoomType;
import com.hotel.backend.entity.RoomType;
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
import java.math.BigDecimal;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private HotelEmailTemplateRenderer templateRenderer;

    @BeforeEach
    void setUp() {
        templateRenderer = new HotelEmailTemplateRenderer();
        ReflectionTestUtils.setField(templateRenderer, "hotelName", "Luxury Hotel");
        ReflectionTestUtils.setField(templateRenderer, "hotelAddress", "Ha Noi, Viet Nam");
        ReflectionTestUtils.setField(templateRenderer, "hotelPhone", "0900000000");
        ReflectionTestUtils.setField(templateRenderer, "hotelEmail", "support@example.com");
        ReflectionTestUtils.setField(templateRenderer, "frontendBaseUrl", "http://localhost:3000");
        emailService = new EmailService(
                verificationSendGrid,
                transactionalSendGrid,
                userRepository,
                reservationRepository,
                templateRenderer);
        ReflectionTestUtils.setField(emailService, "verificationFrom", "verify@example.com");
        ReflectionTestUtils.setField(emailService, "transactionalFrom", "contact@example.com");
        ReflectionTestUtils.setField(emailService, "templateId", "verification-template");
        ReflectionTestUtils.setField(emailService, "passwordResetTemplateId", "password-reset-template");
        ReflectionTestUtils.setField(emailService, "bookingConfirmationTemplateId", "booking-template");
        ReflectionTestUtils.setField(emailService, "contactReplyTemplateId", "contact-reply-template");
        ReflectionTestUtils.setField(emailService, "auditAlertTemplateId", "audit-alert-template");
        ReflectionTestUtils.setField(emailService, "verificationLink", "http://localhost:8080/auth/confirm-email");
        ReflectionTestUtils.setField(emailService, "frontendBaseUrl", "http://localhost:3000");
        ReflectionTestUtils.setField(emailService, "hotelName", "Luxury Hotel");
        ReflectionTestUtils.setField(emailService, "hotelEmail", "support@example.com");
        ReflectionTestUtils.setField(emailService, "verificationTtlHours", 48L);
    }

    @Test
    void contactAndTransactionalMessagesUseTransactionalSendGrid() throws Exception {
        when(transactionalSendGrid.api(any(Request.class))).thenReturn(new Response(202, "", null));

        emailService.send("guest@example.com", "Phản hồi liên hệ", "Nội dung phản hồi");

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(transactionalSendGrid).api(requestCaptor.capture());
        verify(verificationSendGrid, never()).api(any(Request.class));
        assertThat(requestCaptor.getValue().getBody())
                .contains("text/plain")
                .contains("text/html")
                .contains("#0F2A43")
                .contains("#B8944F")
                .contains("Nội dung phản hồi");
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

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(verificationSendGrid).api(requestCaptor.capture());
        verify(transactionalSendGrid, never()).api(any(Request.class));
        verify(userRepository).save(user);
        assertThat(requestCaptor.getValue().getBody())
                .contains("verification-template")
                .contains("verification_link")
                .contains("verification_ttl_hours")
                .contains("support_email");
    }

    @Test
    void dedicatedTransactionalEmailsUseTheirOwnDynamicTemplates() throws Exception {
        when(transactionalSendGrid.api(any(Request.class))).thenReturn(new Response(202, "", null));

        emailService.sendPasswordReset(
                "guest@example.com",
                "Nguyen Van A",
                "https://hotel.example/reset?token=safe",
                30);
        emailService.sendContactReply(
                "guest@example.com",
                "Nguyen Van A",
                "Yêu cầu đổi lịch\r\nkhông hợp lệ",
                "Chúng tôi đã cập nhật yêu cầu của bạn.");
        emailService.sendAuditAlert(
                "admin@example.com",
                "Cảnh báo đối soát",
                "Giao dịch cần kiểm tra\nVui lòng mở dashboard.");

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(transactionalSendGrid, times(3)).api(requestCaptor.capture());
        String bodies = requestCaptor.getAllValues().stream()
                .map(Request::getBody)
                .reduce("", (left, right) -> left + "\n" + right);

        assertThat(bodies)
                .contains("password-reset-template")
                .contains("reset_ttl_minutes")
                .contains("contact-reply-template")
                .contains("message_paragraphs")
                .contains("audit-alert-template")
                .contains("body_paragraphs")
                .contains("support_email")
                .contains("Yêu cầu đổi lịch không hợp lệ")
                .doesNotContain("Yêu cầu đổi lịch\\r\\nkhông hợp lệ");
    }

    @Test
    void bookingConfirmationUsesStructuredRoomTypeData() throws Exception {
        RoomType roomType = RoomType.builder().typeName("Deluxe King").build();
        roomType.setId(11L);
        ReservationRoomType selectedRoomType = ReservationRoomType.builder()
                .roomType(roomType)
                .quantity(2)
                .roomPrice(new BigDecimal("1200000"))
                .subtotal(new BigDecimal("4800000"))
                .build();
        Reservation reservation = Reservation.builder()
                .reservationCode("RES-EMAIL-01")
                .customerProfile(CustomerProfile.builder().fullName("Nguyen Van A").build())
                .checkIn(LocalDateTime.of(2026, 8, 1, 14, 0))
                .checkOut(LocalDateTime.of(2026, 8, 3, 12, 0))
                .totalAmount(new BigDecimal("4800000"))
                .roomTypes(Set.of(selectedRoomType))
                .build();
        when(reservationRepository.findByIdWithDetails(91L)).thenReturn(Optional.of(reservation));
        when(transactionalSendGrid.api(any(Request.class))).thenReturn(new Response(202, "", null));

        emailService.sendGuestBookingConfirmation("guest@example.com", 91L, "guest-token");

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(Request.class);
        verify(transactionalSendGrid).api(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getBody())
                .contains("booking-template")
                .contains("reservation_code")
                .contains("RES-EMAIL-01")
                .contains("room_types")
                .contains("Deluxe King")
                .contains("quantity")
                .contains("lookup_link");
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
