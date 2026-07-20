package com.hotel.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.time.LocalDateTime;

import static com.hotel.backend.util.SecurityTokenHasher.sha256;
@Service
@Slf4j(topic = "EMAIL-SERVIVE")
public class EmailService {
    @Value("${spring.sendgrid.from-email}")
    private String verificationFrom;
    @Value("${app.transactional-email.from-email:${spring.sendgrid.from-email:}}")
    private String transactionalFrom;
    private final SendGrid verificationSendGrid;
    private final SendGrid transactionalSendGrid;
    @Value("${spring.sendgrid.templateId}")
    private String templateId;
    @Value("${spring.sendgrid.verificationLink}")
    private String verificationLink;
    @Value("${app.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;
    @Value("${app.hotel-name:Luxury Hotel}")
    private String hotelName = "Luxury Hotel";
    @Value("${app.email-verification-ttl-hours:48}")
    private long verificationTtlHours;
    private final UserRepository userRepository;
    private final ReservationRepository reservationRepository;

    public EmailService(
            @Qualifier("verificationSendGrid") SendGrid verificationSendGrid,
            @Qualifier("transactionalSendGrid") SendGrid transactionalSendGrid,
            UserRepository userRepository,
            ReservationRepository reservationRepository) {
        this.verificationSendGrid = verificationSendGrid;
        this.transactionalSendGrid = transactionalSendGrid;
        this.userRepository = userRepository;
        this.reservationRepository = reservationRepository;
    }

    public void send(String to, String subject, String text) {
        Email fromEmail = new Email(transactionalFrom);
        Email toemail=new Email(to);
        Content content= new Content("text/plain",text);
        Mail mail=new Mail(fromEmail,subject,toemail,content);

        Request request=new Request();
        try {
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());
            Response response = transactionalSendGrid.api(request);
            log.info("SendGrid status: {}", response.getStatusCode());
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                log.info("Email sent successfully");
                return;
            }
            log.error("Email sent failed with status: {}", response.getStatusCode());
            throw new AppException(ErrorCode.EMAIL_DELIVERY_FAILED);
        } catch (IOException e) {
            log.error("Error occurred while sending email, error: {}", e.getMessage());
            throw new AppException(ErrorCode.EMAIL_DELIVERY_FAILED);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void emailVerification(Long userId) throws IOException {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        emailVerification(user.getEmail(), user.getFullName());
    }

    // email Verificate by sendgrid
    @Transactional(rollbackFor = Exception.class)
    public void emailVerification(String to, String name) throws IOException{
        log.info("Email verification started");
        Email fromEmail = new Email(verificationFrom, hotelName);
        Email toemail=new Email(to);
        String subject="Xác thực tài khoản";

        //TODO genegrate secretCode and save to database
        String code = UUID.randomUUID().toString();
        String secretCode = String.format("?secretCode=%s", code);

        // Lưu vào DB

        User user = userRepository.findByEmail(to)
                .orElseThrow(() -> new RuntimeException("User not found"));
        String previousVerificationCode = user.getVerificationCode();
        LocalDateTime previousVerificationExpiry = user.getVerificationExpiresAt();
        user.setVerificationCode(sha256(code));
        user.setVerificationExpiresAt(LocalDateTime.now().plusHours(verificationTtlHours));
        userRepository.save(user);

        // dinh nghia template
        Map<String,String> map= new HashMap<>();
        map.put("name",name);
        map.put("verification_link", verificationLink+secretCode);
        // map.put("verification_link", verificationLink);
        map.put("emailUser", to);


        Mail mail =new Mail();
        mail.setFrom(fromEmail);
        mail.setSubject(subject);

        Personalization personalization= new Personalization();
        personalization.addTo(toemail);

        //add to dynamic data
        map.forEach(personalization::addDynamicTemplateData);
        mail.addPersonalization(personalization);
        mail.setTemplateId(templateId);

        Request request= new Request();
        request.setMethod(Method.POST);
        request.setEndpoint("mail/send");
        request.setBody(mail.build());
        // log.info("Request body: {}", mail.build());
        try {
            Response response = verificationSendGrid.api(request);
            if (response.getStatusCode() == 202) {
                log.info("Verification sent successfully");
                return;
            }

            log.error("Verification sent failed with status: {}", response.getStatusCode());
            log.error("Body: {}", response.getBody());
            throw new IOException("SendGrid verification email failed with status " + response.getStatusCode());
        } catch (IOException | RuntimeException exception) {
            restoreVerificationState(user, previousVerificationCode, previousVerificationExpiry);
            throw exception;
        }
    }

    private void restoreVerificationState(
            User user,
            String previousVerificationCode,
            LocalDateTime previousVerificationExpiry) {
        user.setVerificationCode(previousVerificationCode);
        user.setVerificationExpiresAt(previousVerificationExpiry);
        userRepository.save(user);
    }

    public void sendGuestBookingConfirmation(String to, Long reservationId, String guestToken) throws IOException {
        var reservation = reservationRepository.findByIdWithDetails(reservationId)
                .orElseThrow(() -> new AppException(ErrorCode.RESERVATION_NOT_FOUND));

        String lookupLink = frontendBaseUrl.replaceAll("/+$", "")
                + "/booking/lookup#token="
                + URLEncoder.encode(guestToken, StandardCharsets.UTF_8);

        NumberFormat vnd = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("vi-VN"));
        String roomSummary = reservation.getRoomTypes().stream()
                .map(ReservationRoomTypeResponse::from)
                .map(roomType -> String.format("- %s x%d: %s",
                        roomType.getRoomTypeName(),
                        roomType.getQuantity(),
                        vnd.format(roomType.getSubtotal())))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- Chưa có thông tin loại phòng");

        String content = """
                Xin chào %s,

                Cảm ơn bạn đã đặt phòng tại LuxStay Hotel.

                Thông tin đặt phòng:
                - Mã đặt phòng: %s
                - Ngày nhận phòng: %s
                - Ngày trả phòng: %s
                - Tổng tiền: %s

                Phòng đã chọn:
                %s

                Bạn có thể xem lại đặt phòng hoặc yêu cầu hủy tại link sau:
                %s

                Nếu bạn làm mất đường dẫn này, vui lòng liên hệ lễ tân để được hỗ trợ.
                """.formatted(
                reservation.getCustomerProfile().getFullName(),
                reservation.getReservationCode(),
                reservation.getCheckIn(),
                reservation.getCheckOut(),
                vnd.format(reservation.getTotalAmount()),
                roomSummary,
                lookupLink);

        send(to, "Xác nhận đặt phòng " + reservation.getReservationCode(), content);
    }
    
} 
