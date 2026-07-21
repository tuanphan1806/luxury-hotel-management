package com.hotel.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HotelEmailTemplateRendererTest {

    private HotelEmailTemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new HotelEmailTemplateRenderer();
        ReflectionTestUtils.setField(renderer, "hotelName", "Luxury Hotel");
        ReflectionTestUtils.setField(renderer, "hotelAddress", "Ha Noi, Viet Nam");
        ReflectionTestUtils.setField(renderer, "hotelPhone", "0900000000");
        ReflectionTestUtils.setField(renderer, "hotelEmail", "support@example.com");
        ReflectionTestUtils.setField(renderer, "frontendBaseUrl", "https://hotel.example.com/");
    }

    @Test
    void passwordResetRendersResponsiveHtmlAndPlainTextFallback() {
        var email = renderer.passwordReset(
                "Nguyễn Văn A",
                "https://hotel.example.com/reset-password?token=safe-token",
                30);

        assertThat(email.html())
                .contains("#0F2A43")
                .contains("#B8944F")
                .contains("@media only screen and (max-width:620px)")
                .contains("Đặt lại mật khẩu")
                .contains("https://hotel.example.com/reset-password?token=safe-token");
        assertThat(email.plainText())
                .contains("Nguyễn Văn A")
                .contains("30 phút")
                .contains("https://hotel.example.com/reset-password?token=safe-token");
    }

    @Test
    void userProvidedContentIsEscapedBeforeRendering() {
        var email = renderer.contactReply(
                "<script>alert('guest')</script>",
                "Xin chào\n<img src=x onerror=alert(1)>");

        assertThat(email.html())
                .doesNotContain("<script>")
                .doesNotContain("<img src=x")
                .contains("&lt;script&gt;")
                .contains("&lt;img src=x onerror=alert(1)&gt;");
    }

    @Test
    void bookingConfirmationShowsOperationalDetails() {
        var email = renderer.bookingConfirmation(
                "Trần Minh",
                List.of(
                        new HotelEmailTemplateRenderer.DetailRow("Mã đặt phòng", "RES-ABC123"),
                        new HotelEmailTemplateRenderer.DetailRow("Tổng tiền", "1.200.000 ₫")),
                "https://hotel.example.com/booking/lookup#token=guest-token");

        assertThat(email.html())
                .contains("RES-ABC123")
                .contains("1.200.000 ₫")
                .contains("Xem đơn đặt phòng");
        assertThat(email.plainText()).contains("Mã đặt phòng: RES-ABC123");
    }

    @Test
    void unsafeCallToActionUrlIsNotRenderedAsALink() {
        var email = renderer.passwordReset("Guest", "javascript:alert(1)", 30);

        assertThat(email.html()).doesNotContain("javascript:alert(1)");
        assertThat(email.plainText()).doesNotContain("javascript:alert(1)");
    }

    @Test
    void renderedEmailUsesUtf8AndVietnameseSafeFontStacks() {
        var email = renderer.passwordReset("Nguyễn Ánh", "https://hotel.example.com/reset", 30);

        assertThat(email.html())
                .contains("content=\"text/html; charset=UTF-8\"")
                .contains("font-family:'Segoe UI',Tahoma,Arial,sans-serif")
                .contains("font-family:'Times New Roman',Times,serif")
                .doesNotContain("Georgia")
                .doesNotContain("font-weight:750")
                .doesNotContain("font-weight:800");
    }

    @Test
    void allSendGridTemplatesUseUtf8AndSupportedFontWeights() throws IOException {
        for (String template : List.of(
                "sendgrid-verification.html",
                "sendgrid-password-reset.html",
                "sendgrid-booking-confirmation.html",
                "sendgrid-contact-reply.html",
                "sendgrid-audit-alert.html")) {
            String html = new ClassPathResource("email-templates/" + template)
                    .getContentAsString(StandardCharsets.UTF_8);

            assertThat(html)
                    .as(template)
                    .contains("content=\"text/html; charset=UTF-8\"")
                    .contains("font-family:'Segoe UI',Tahoma,Arial,sans-serif")
                    .contains("font-family:'Times New Roman',Times,serif")
                    .doesNotContain("Georgia")
                    .doesNotContain("font-weight:750")
                    .doesNotContain("font-weight:800");
        }
    }
}
