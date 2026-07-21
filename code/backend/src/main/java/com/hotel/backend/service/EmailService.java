package com.hotel.backend.service;

import com.hotel.backend.dto.response.ReservationRoomTypeResponse;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import com.hotel.backend.repository.ReservationRepository;
import com.hotel.backend.repository.UserRepository;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import com.sendgrid.helpers.mail.objects.Personalization;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.hotel.backend.util.SecurityTokenHasher.sha256;

@Service
@Slf4j(topic = "EMAIL-SERVICE")
public class EmailService {

    private static final DateTimeFormatter STAY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm · dd/MM/yyyy", Locale.forLanguageTag("vi-VN"));

    @Value("${spring.sendgrid.from-email:}")
    private String verificationFrom;

    @Value("${app.transactional-email.from-email:${spring.sendgrid.from-email:}}")
    private String transactionalFrom;

    @Value("${spring.sendgrid.templateId:}")
    private String templateId;

    @Value("${app.transactional-email.templates.password-reset:}")
    private String passwordResetTemplateId;

    @Value("${app.transactional-email.templates.booking-confirmation:}")
    private String bookingConfirmationTemplateId;

    @Value("${app.transactional-email.templates.contact-reply:}")
    private String contactReplyTemplateId;

    @Value("${app.transactional-email.templates.audit-alert:}")
    private String auditAlertTemplateId;

    @Value("${spring.sendgrid.verificationLink}")
    private String verificationLink;

    @Value("${app.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    @Value("${app.hotel-name:Luxury Hotel}")
    private String hotelName = "Luxury Hotel";

    @Value("${app.hotel-email:}")
    private String hotelEmail = "";

    @Value("${app.email-verification-ttl-hours:48}")
    private long verificationTtlHours;

    private final SendGrid verificationSendGrid;
    private final SendGrid transactionalSendGrid;
    private final UserRepository userRepository;
    private final ReservationRepository reservationRepository;
    private final HotelEmailTemplateRenderer templateRenderer;

    public EmailService(
            @Qualifier("verificationSendGrid") SendGrid verificationSendGrid,
            @Qualifier("transactionalSendGrid") SendGrid transactionalSendGrid,
            UserRepository userRepository,
            ReservationRepository reservationRepository,
            HotelEmailTemplateRenderer templateRenderer) {
        this.verificationSendGrid = verificationSendGrid;
        this.transactionalSendGrid = transactionalSendGrid;
        this.userRepository = userRepository;
        this.reservationRepository = reservationRepository;
        this.templateRenderer = templateRenderer;
    }

    /**
     * Compatibility entry point for operator-authored notifications. The input
     * remains plain text; it is escaped and wrapped in the shared branded shell.
     */
    public void send(String to, String subject, String text) {
        sendTransactional(to, subject, templateRenderer.generic(subject, text), "generic");
    }

    public void sendContactReply(String to, String guestName, String subject, String replyMessage) {
        String safeSubject = normalizeSubject(subject, "Phản hồi yêu cầu hỗ trợ");
        Map<String, Object> dynamicData = commonDynamicData();
        dynamicData.put("guest_name", normalizedName(guestName));
        dynamicData.put("subject", safeSubject);
        dynamicData.put("message_paragraphs", templateParagraphs(replyMessage));
        dynamicData.put("support_url", normalizedFrontendBaseUrl() + "/support");

        sendTransactionalTemplate(
                to,
                guestName,
                safeSubject,
                contactReplyTemplateId,
                dynamicData,
                templateRenderer.contactReply(guestName, replyMessage),
                "contact_reply");
    }

    public void sendPasswordReset(String to, String name, String resetLink, long ttlMinutes) {
        Map<String, Object> dynamicData = commonDynamicData();
        dynamicData.put("name", normalizedName(name));
        dynamicData.put("reset_link", resetLink);
        dynamicData.put("reset_ttl_minutes", ttlMinutes);

        sendTransactionalTemplate(
                to,
                name,
                "Đặt lại mật khẩu " + hotelName,
                passwordResetTemplateId,
                dynamicData,
                templateRenderer.passwordReset(name, resetLink, ttlMinutes),
                "password_reset");
    }

    public void sendAuditAlert(String to, String subject, String body) {
        String safeSubject = normalizeSubject(subject, "Cảnh báo vận hành");
        Map<String, Object> dynamicData = commonDynamicData();
        dynamicData.put("subject", safeSubject);
        dynamicData.put("body_paragraphs", templateParagraphs(body));
        dynamicData.put("audit_url", normalizedFrontendBaseUrl() + "/dashboard/audit-logs");

        sendTransactionalTemplate(
                to,
                "Quản trị viên",
                safeSubject,
                auditAlertTemplateId,
                dynamicData,
                templateRenderer.auditAlert(body),
                "audit_alert");
    }

    @Transactional(rollbackFor = Exception.class)
    public void emailVerification(Long userId) throws IOException {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy tài khoản"));
        emailVerification(user.getEmail(), user.getFullName());
    }

    @Transactional(rollbackFor = Exception.class)
    public void emailVerification(String to, String name) throws IOException {
        User user = userRepository.findByEmail(to)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy tài khoản"));
        String previousVerificationCode = user.getVerificationCode();
        LocalDateTime previousVerificationExpiry = user.getVerificationExpiresAt();

        String rawCode = UUID.randomUUID().toString();
        String verifyUrl = verificationLink + "?secretCode="
                + URLEncoder.encode(rawCode, StandardCharsets.UTF_8);
        user.setVerificationCode(sha256(rawCode));
        user.setVerificationExpiresAt(LocalDateTime.now().plusHours(verificationTtlHours));
        userRepository.save(user);

        try {
            if (templateId != null && !templateId.isBlank()) {
                sendVerificationTemplate(to, name, verifyUrl);
            } else {
                sendHtml(
                        verificationSendGrid,
                        verificationFrom,
                        to,
                        "Xác thực tài khoản " + hotelName,
                        templateRenderer.verification(name, to, verifyUrl, verificationTtlHours),
                        "verification");
            }
            log.info("Verification email accepted by SendGrid");
        } catch (IOException | RuntimeException exception) {
            restoreVerificationState(user, previousVerificationCode, previousVerificationExpiry);
            throw exception;
        }
    }

    public void sendGuestBookingConfirmation(String to, Long reservationId, String guestToken) throws IOException {
        var reservation = reservationRepository.findByIdWithDetails(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));

        String lookupLink = normalizedFrontendBaseUrl()
                + "/booking/lookup#token="
                + URLEncoder.encode(guestToken, StandardCharsets.UTF_8);
        NumberFormat vnd = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("vi-VN"));

        List<HotelEmailTemplateRenderer.DetailRow> details = new ArrayList<>();
        List<Map<String, Object>> roomTypes = new ArrayList<>();
        details.add(new HotelEmailTemplateRenderer.DetailRow(
                "Mã đặt phòng", reservation.getReservationCode()));
        details.add(new HotelEmailTemplateRenderer.DetailRow(
                "Nhận phòng", formatStayTime(reservation.getCheckIn())));
        details.add(new HotelEmailTemplateRenderer.DetailRow(
                "Trả phòng", formatStayTime(reservation.getCheckOut())));
        reservation.getRoomTypes().stream()
                .map(ReservationRoomTypeResponse::from)
                .forEach(roomType -> {
                    String subtotal = vnd.format(roomType.getSubtotal());
                    details.add(new HotelEmailTemplateRenderer.DetailRow(
                            "Hạng phòng",
                            roomType.getRoomTypeName() + " × " + roomType.getQuantity()
                                    + " · " + subtotal));
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", roomType.getRoomTypeName());
                    row.put("quantity", roomType.getQuantity());
                    row.put("subtotal", subtotal);
                    roomTypes.add(row);
                });
        details.add(new HotelEmailTemplateRenderer.DetailRow(
                "Tổng tiền", vnd.format(reservation.getTotalAmount())));

        String guestName = reservation.getCustomerProfile().getFullName();
        Map<String, Object> dynamicData = commonDynamicData();
        dynamicData.put("guest_name", normalizedName(guestName));
        dynamicData.put("reservation_code", reservation.getReservationCode());
        dynamicData.put("check_in", formatStayTime(reservation.getCheckIn()));
        dynamicData.put("check_out", formatStayTime(reservation.getCheckOut()));
        dynamicData.put("room_types", roomTypes);
        dynamicData.put("total_amount", vnd.format(reservation.getTotalAmount()));
        dynamicData.put("lookup_link", lookupLink);

        sendTransactionalTemplate(
                to,
                guestName,
                "Xác nhận đặt phòng " + reservation.getReservationCode(),
                bookingConfirmationTemplateId,
                dynamicData,
                templateRenderer.bookingConfirmation(
                        guestName,
                        details,
                        lookupLink),
                "booking_confirmation");
    }

    private void sendTransactional(
            String to,
            String subject,
            HotelEmailTemplateRenderer.RenderedEmail rendered,
            String purpose) {
        try {
            sendHtml(transactionalSendGrid, transactionalFrom, to, subject, rendered, purpose);
        } catch (IOException exception) {
            log.warn("SendGrid transport failed purpose={}: {}", purpose, exception.getMessage());
            throw new AppException(ErrorCode.EMAIL_DELIVERY_FAILED);
        }
    }

    private void sendTransactionalTemplate(
            String to,
            String recipientName,
            String subject,
            String dynamicTemplateId,
            Map<String, Object> dynamicData,
            HotelEmailTemplateRenderer.RenderedEmail fallback,
            String purpose) {
        try {
            if (dynamicTemplateId != null && !dynamicTemplateId.isBlank()) {
                sendDynamicTemplate(
                        transactionalSendGrid,
                        transactionalFrom,
                        to,
                        recipientName,
                        dynamicTemplateId,
                        dynamicData,
                        purpose);
            } else {
                sendHtml(transactionalSendGrid, transactionalFrom, to, subject, fallback, purpose);
            }
        } catch (IOException exception) {
            log.warn("SendGrid transport failed purpose={}: {}", purpose, exception.getMessage());
            throw new AppException(ErrorCode.EMAIL_DELIVERY_FAILED);
        }
    }

    private void sendVerificationTemplate(String to, String name, String verifyUrl) throws IOException {
        requireEmailAddress(verificationFrom, "spring.sendgrid.from-email");
        requireEmailAddress(to, "recipient");

        Map<String, Object> dynamicData = commonDynamicData();
        dynamicData.put("name", normalizedName(name));
        dynamicData.put("verification_link", verifyUrl);
        dynamicData.put("emailUser", to);
        dynamicData.put("verification_ttl_hours", verificationTtlHours);

        sendDynamicTemplate(
                verificationSendGrid,
                verificationFrom,
                to,
                name,
                templateId,
                dynamicData,
                "verification");
    }

    private void sendDynamicTemplate(
            SendGrid client,
            String from,
            String to,
            String recipientName,
            String dynamicTemplateId,
            Map<String, Object> dynamicData,
            String purpose) throws IOException {
        requireEmailAddress(from, "from-email");
        requireEmailAddress(to, "recipient");

        Mail mail = new Mail();
        mail.setFrom(new Email(from.trim(), hotelName));
        mail.setReplyTo(new Email(replyToAddress(), hotelName));
        mail.setTemplateId(dynamicTemplateId.trim());

        Personalization personalization = new Personalization();
        personalization.addTo(new Email(to.trim(), normalizedName(recipientName)));
        dynamicData.forEach(personalization::addDynamicTemplateData);
        mail.addPersonalization(personalization);

        requireAccepted(execute(client, mail), purpose);
    }

    private void sendHtml(
            SendGrid client,
            String from,
            String to,
            String subject,
            HotelEmailTemplateRenderer.RenderedEmail rendered,
            String purpose) throws IOException {
        requireEmailAddress(from, "from-email");
        requireEmailAddress(to, "recipient");

        Mail mail = new Mail();
        mail.setFrom(new Email(from.trim(), hotelName));
        mail.setReplyTo(new Email(replyToAddress(), hotelName));
        mail.setSubject(subject);
        Personalization personalization = new Personalization();
        personalization.addTo(new Email(to.trim()));
        mail.addPersonalization(personalization);
        mail.addContent(new Content("text/plain", rendered.plainText()));
        mail.addContent(new Content("text/html", rendered.html()));

        requireAccepted(execute(client, mail), purpose);
    }

    private Response execute(SendGrid client, Mail mail) throws IOException {
        Request request = new Request();
        request.setMethod(Method.POST);
        request.setEndpoint("mail/send");
        request.setBody(mail.build());
        return client.api(request);
    }

    private void requireAccepted(Response response, String purpose) throws IOException {
        int status = response == null ? 0 : response.getStatusCode();
        if (status >= 200 && status < 300) {
            log.info("SendGrid accepted email purpose={} status={}", purpose, status);
            return;
        }
        String diagnostic = response == null || response.getBody() == null
                ? ""
                : response.getBody().replaceAll("[\\r\\n]+", " ");
        if (diagnostic.length() > 300) diagnostic = diagnostic.substring(0, 300);
        log.warn("SendGrid rejected email purpose={} status={} response={}", purpose, status, diagnostic);
        throw new IOException("SendGrid rejected " + purpose + " email with status " + status);
    }

    private void restoreVerificationState(
            User user,
            String previousVerificationCode,
            LocalDateTime previousVerificationExpiry) {
        user.setVerificationCode(previousVerificationCode);
        user.setVerificationExpiresAt(previousVerificationExpiry);
        userRepository.save(user);
    }

    private void requireEmailAddress(String value, String property) {
        if (value == null || value.isBlank() || !value.contains("@")) {
            throw new AppException(
                    ErrorCode.EMAIL_DELIVERY_FAILED,
                    "Cấu hình email không hợp lệ: " + property);
        }
    }

    private String replyToAddress() {
        if (hotelEmail != null && hotelEmail.contains("@")) return hotelEmail.trim();
        if (transactionalFrom != null && transactionalFrom.contains("@")) return transactionalFrom.trim();
        return verificationFrom == null ? "" : verificationFrom.trim();
    }

    private Map<String, Object> commonDynamicData() {
        Map<String, Object> dynamicData = new LinkedHashMap<>();
        dynamicData.put("hotel_name", hotelName);
        dynamicData.put("support_email", replyToAddress());
        dynamicData.put("home_url", normalizedFrontendBaseUrl());
        dynamicData.put("current_year", Year.now().getValue());
        return dynamicData;
    }

    private List<String> templateParagraphs(String value) {
        if (value == null || value.isBlank()) return List.of();
        return value.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private String normalizedName(String value) {
        return value == null || value.isBlank() ? "quý khách" : value.trim();
    }

    private String normalizeSubject(String value, String fallback) {
        String normalized = value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
        if (normalized.isBlank()) normalized = fallback;
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    private String normalizedFrontendBaseUrl() {
        return frontendBaseUrl.replaceAll("/+$", "");
    }

    private String formatStayTime(LocalDateTime value) {
        return value == null ? "Chưa xác định" : STAY_TIME_FORMAT.format(value);
    }
}
