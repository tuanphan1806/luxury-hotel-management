package com.hotel.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Year;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Renders version-controlled, email-client-friendly transactional templates.
 * All caller-provided values are escaped before they enter HTML; links are
 * limited to HTTP(S). A plain-text alternative is always generated as well.
 */
@Component
public class HotelEmailTemplateRenderer {

    private static final String NAVY = "#0F2A43";
    private static final String GOLD = "#B8944F";

    @Value("${app.hotel-name:Luxury Hotel}")
    private String hotelName = "Luxury Hotel";

    @Value("${app.hotel-address:}")
    private String hotelAddress = "";

    @Value("${app.hotel-phone:}")
    private String hotelPhone = "";

    @Value("${app.hotel-email:}")
    private String hotelEmail = "";

    @Value("${app.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl = "http://localhost:3000";

    public RenderedEmail generic(String subject, String body) {
        Tone tone = containsIgnoreCase(subject, "cảnh báo")
                ? Tone.WARNING
                : Tone.GOLD;
        return render(new EmailView(
                subject,
                tone == Tone.WARNING ? "CẢNH BÁO VẬN HÀNH" : "THÔNG BÁO",
                subject,
                null,
                paragraphs(body),
                List.of(),
                null,
                null,
                tone == Tone.WARNING
                        ? "Vui lòng kiểm tra thông tin trong dashboard trước khi thực hiện thao tác tiếp theo."
                        : "Bạn có thể trả lời email này nếu cần Luxury Hotel hỗ trợ thêm.",
                tone));
    }

    public RenderedEmail contactReply(String guestName, String replyMessage) {
        return render(new EmailView(
                "Luxury Hotel đã phản hồi yêu cầu của bạn",
                "TRUNG TÂM HỖ TRỢ",
                "Phản hồi từ Luxury Hotel",
                greeting(guestName),
                paragraphs(replyMessage),
                List.of(),
                "Mở trung tâm hỗ trợ",
                baseUrl() + "/support",
                "Nếu nội dung chưa giải quyết đầy đủ yêu cầu, bạn chỉ cần trả lời email này để tiếp tục trao đổi.",
                Tone.GOLD));
    }

    public RenderedEmail passwordReset(String name, String resetLink, long ttlMinutes) {
        return render(new EmailView(
                "Liên kết đặt lại mật khẩu có hiệu lực trong " + ttlMinutes + " phút",
                "BẢO MẬT TÀI KHOẢN",
                "Đặt lại mật khẩu",
                greeting(name),
                List.of(
                        "Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản Luxury Hotel của bạn.",
                        "Nhấn nút bên dưới để tạo mật khẩu mới. Liên kết chỉ sử dụng được trong "
                                + ttlMinutes + " phút."),
                List.of(new DetailRow("Thời hạn", ttlMinutes + " phút")),
                "Đặt lại mật khẩu",
                resetLink,
                "Nếu bạn không yêu cầu thay đổi này, hãy bỏ qua email. Mật khẩu hiện tại và phiên đăng nhập của bạn vẫn được giữ nguyên.",
                Tone.WARNING));
    }

    public RenderedEmail verification(
            String name,
            String email,
            String verificationLink,
            long ttlHours) {
        return render(new EmailView(
                "Xác thực email để hoàn tất tài khoản Luxury Hotel",
                "XÁC THỰC TÀI KHOẢN",
                "Chào mừng bạn đến Luxury Hotel",
                greeting(name),
                List.of(
                        "Chỉ còn một bước để hoàn tất tài khoản và quản lý đặt phòng thuận tiện hơn.",
                        "Nhấn nút bên dưới để xác nhận địa chỉ email của bạn."),
                List.of(
                        new DetailRow("Email", email),
                        new DetailRow("Hiệu lực", ttlHours + " giờ")),
                "Xác thực email",
                verificationLink,
                "Không chia sẻ liên kết này. Nếu bạn không tạo tài khoản, bạn có thể bỏ qua email một cách an toàn.",
                Tone.SUCCESS));
    }

    public RenderedEmail bookingConfirmation(
            String guestName,
            List<DetailRow> details,
            String lookupLink) {
        return render(new EmailView(
                "Đặt phòng đã được tiếp nhận và đang chờ bước tiếp theo",
                "XÁC NHẬN ĐẶT PHÒNG",
                "Chúng tôi đã nhận thông tin lưu trú của bạn",
                greeting(guestName),
                List.of(
                        "Cảm ơn bạn đã lựa chọn Luxury Hotel. Thông tin đặt phòng được tóm tắt bên dưới.",
                        "Bạn có thể mở đơn để theo dõi thanh toán, trạng thái xác nhận và các bước trước khi nhận phòng."),
                details,
                "Xem đơn đặt phòng",
                lookupLink,
                "Đường dẫn tra cứu gắn với đơn của bạn. Không chuyển tiếp email này cho người khác.",
                Tone.SUCCESS));
    }

    public RenderedEmail auditAlert(String body) {
        return render(new EmailView(
                "Hệ thống vừa ghi nhận một thao tác cần kiểm tra",
                "CẢNH BÁO VẬN HÀNH",
                "Có hoạt động rủi ro cần đối soát",
                "Xin chào quản trị viên,",
                paragraphs(body),
                List.of(),
                "Mở nhật ký hệ thống",
                baseUrl() + "/dashboard/audit-logs",
                "Không xử lý tài chính chỉ dựa trên email. Hãy mở dashboard và đối chiếu ledger, reservation cùng audit trail trước khi quyết định.",
                Tone.DANGER));
    }

    private RenderedEmail render(EmailView view) {
        String safeCtaUrl = safeHttpUrl(view.ctaUrl());
        String paragraphsHtml = view.paragraphs().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> "<p style=\"margin:0 0 16px;color:#334155;font-size:15px;line-height:1.75;\">"
                        + multiline(value) + "</p>")
                .reduce("", String::concat);
        String detailsHtml = detailsHtml(view.details());
        String ctaHtml = safeCtaUrl.isBlank() || isBlank(view.ctaLabel())
                ? ""
                : """
                    <table role="presentation" cellspacing="0" cellpadding="0" border="0" style="margin:28px 0 24px;">
                      <tr>
                        <td bgcolor="#B8944F" style="border-radius:8px;">
                          <a href="__CTA_URL__" target="_blank" style="display:inline-block;padding:14px 24px;color:#0F2A43;font-size:14px;font-weight:700;text-decoration:none;letter-spacing:.01em;">__CTA_LABEL__ &nbsp;→</a>
                        </td>
                      </tr>
                    </table>
                    """
                .replace("__CTA_URL__", escape(safeCtaUrl))
                .replace("__CTA_LABEL__", escape(view.ctaLabel()));
        String noticeHtml = isBlank(view.notice())
                ? ""
                : """
                    <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="margin-top:24px;">
                      <tr>
                        <td style="border-left:4px solid __TONE__;background:#F8F6F1;border-radius:0 8px 8px 0;padding:14px 16px;color:#475569;font-size:13px;line-height:1.65;">__NOTICE__</td>
                      </tr>
                    </table>
                    """
                .replace("__TONE__", view.tone().color)
                .replace("__NOTICE__", multiline(view.notice()));

        String greetingHtml = isBlank(view.greeting())
                ? ""
                : "<p style=\"margin:0 0 18px;color:" + NAVY
                        + ";font-size:16px;font-weight:700;line-height:1.5;\">"
                        + escape(view.greeting()) + "</p>";

        String html = BASE_HTML
                .replace("__PREHEADER__", escape(view.preheader()))
                .replace("__EYEBROW__", escape(view.eyebrow()))
                .replace("__TITLE__", escape(view.title()))
                .replace("__GREETING__", greetingHtml)
                .replace("__PARAGRAPHS__", paragraphsHtml)
                .replace("__DETAILS__", detailsHtml)
                .replace("__CTA__", ctaHtml)
                .replace("__NOTICE__", noticeHtml)
                .replace("__HOTEL_NAME__", escape(hotelName))
                .replace("__HOTEL_HOME__", escape(baseUrl()))
                .replace("__HOTEL_CONTACT__", footerContactHtml())
                .replace("__YEAR__", String.valueOf(Year.now().getValue()))
                .replace("__TONE__", view.tone().color);

        return new RenderedEmail(plainText(view, safeCtaUrl), html);
    }

    private String detailsHtml(List<DetailRow> details) {
        if (details == null || details.isEmpty()) return "";
        StringBuilder rows = new StringBuilder();
        for (DetailRow detail : details) {
            if (detail == null || isBlank(detail.label()) || isBlank(detail.value())) continue;
            rows.append("<tr>")
                    .append("<td style=\"padding:11px 14px;border-bottom:1px solid #E6E2D8;color:#64748B;font-size:12px;font-weight:700;vertical-align:top;\">")
                    .append(escape(detail.label()))
                    .append("</td>")
                    .append("<td align=\"right\" style=\"padding:11px 14px;border-bottom:1px solid #E6E2D8;color:#0F2A43;font-size:13px;font-weight:700;line-height:1.5;vertical-align:top;\">")
                    .append(multiline(detail.value()))
                    .append("</td></tr>");
        }
        if (rows.isEmpty()) return "";
        return "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin:22px 0 8px;border:1px solid #E6E2D8;border-radius:10px;background:#FBFAF6;border-collapse:separate;overflow:hidden;\">"
                + rows + "</table>";
    }

    private String plainText(EmailView view, String ctaUrl) {
        StringBuilder text = new StringBuilder(view.title()).append("\n\n");
        if (!isBlank(view.greeting())) text.append(view.greeting()).append("\n\n");
        view.paragraphs().stream()
                .filter(value -> value != null && !value.isBlank())
                .forEach(value -> text.append(value.trim()).append("\n\n"));
        if (view.details() != null) {
            view.details().stream()
                    .filter(Objects::nonNull)
                    .filter(detail -> !isBlank(detail.label()) && !isBlank(detail.value()))
                    .forEach(detail -> text.append(detail.label()).append(": ")
                            .append(detail.value()).append('\n'));
            if (!view.details().isEmpty()) text.append('\n');
        }
        if (!ctaUrl.isBlank()) {
            text.append(view.ctaLabel()).append(": ").append(ctaUrl).append("\n\n");
        }
        if (!isBlank(view.notice())) text.append(view.notice()).append("\n\n");
        text.append(hotelName);
        String contact = footerContactPlain();
        if (!contact.isBlank()) text.append("\n").append(contact);
        return text.toString().trim();
    }

    private List<String> paragraphs(String body) {
        if (body == null || body.isBlank()) return List.of();
        return Arrays.stream(body.strip().split("\\R\\s*\\R"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private String footerContactHtml() {
        List<String> parts = new ArrayList<>();
        if (!isBlank(hotelAddress)) parts.add(escape(hotelAddress));
        if (!isBlank(hotelPhone)) parts.add(escape(hotelPhone));
        if (!isBlank(hotelEmail)) parts.add(escape(hotelEmail));
        return String.join(" &nbsp;·&nbsp; ", parts);
    }

    private String footerContactPlain() {
        return String.join(" · ", List.of(hotelAddress, hotelPhone, hotelEmail).stream()
                .filter(value -> value != null && !value.isBlank())
                .toList());
    }

    private String baseUrl() {
        String value = safeHttpUrl(frontendBaseUrl);
        return value.isBlank() ? "http://localhost:3000" : value.replaceAll("/+$", "");
    }

    private String safeHttpUrl(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    ? uri.toASCIIString()
                    : "";
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private String greeting(String name) {
        return isBlank(name) ? "Xin chào," : "Xin chào " + name.trim() + ",";
    }

    private String multiline(String value) {
        return escape(value).replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>");
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean containsIgnoreCase(String value, String expected) {
        return value != null && value.toLowerCase().contains(expected.toLowerCase());
    }

    public record RenderedEmail(String plainText, String html) {
    }

    public record DetailRow(String label, String value) {
    }

    private record EmailView(
            String preheader,
            String eyebrow,
            String title,
            String greeting,
            List<String> paragraphs,
            List<DetailRow> details,
            String ctaLabel,
            String ctaUrl,
            String notice,
            Tone tone) {
        private EmailView {
            preheader = Objects.requireNonNullElse(preheader, "");
            eyebrow = Objects.requireNonNullElse(eyebrow, "");
            title = Objects.requireNonNullElse(title, "Luxury Hotel");
            paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
            details = details == null ? List.of() : List.copyOf(details);
            tone = tone == null ? Tone.GOLD : tone;
        }
    }

    private enum Tone {
        GOLD(HotelEmailTemplateRenderer.GOLD),
        SUCCESS("#047857"),
        WARNING("#B45309"),
        DANGER("#BE123C");

        private final String color;

        Tone(String color) {
            this.color = color;
        }
    }

    private static final String BASE_HTML = """
            <!doctype html>
            <html lang="vi">
              <head>
                <meta charset="utf-8">
                <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="x-apple-disable-message-reformatting">
                <title>__TITLE__</title>
                <style>
                  @media only screen and (max-width:620px) {
                    .email-shell { width:100% !important; }
                    .email-pad { padding-left:22px !important; padding-right:22px !important; }
                    .email-title { font-size:28px !important; line-height:1.18 !important; }
                  }
                </style>
              </head>
              <body style="margin:0;padding:0;background:#F1F0EA;font-family:'Segoe UI',Tahoma,Arial,sans-serif;color:#0F2A43;">
                <div style="display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;">__PREHEADER__</div>
                <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" bgcolor="#F1F0EA">
                  <tr>
                    <td align="center" style="padding:28px 12px;">
                      <table class="email-shell" role="presentation" width="640" cellspacing="0" cellpadding="0" border="0" style="width:640px;max-width:640px;border-radius:16px;overflow:hidden;background:#FFFFFF;box-shadow:0 18px 55px rgba(15,42,67,.12);">
                        <tr>
                          <td class="email-pad" bgcolor="#0F2A43" style="padding:30px 38px 34px;border-top:5px solid __TONE__;">
                            <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0">
                              <tr>
                                <td valign="middle">
                                  <table role="presentation" cellspacing="0" cellpadding="0" border="0">
                                    <tr>
                                      <td width="44" height="44" align="center" valign="middle" bgcolor="#B8944F" style="width:44px;height:44px;border-radius:50%;color:#0F2A43;font-family:'Times New Roman',Times,serif;font-size:18px;font-weight:700;">LH</td>
                                      <td style="padding-left:12px;">
                                        <a href="__HOTEL_HOME__" target="_blank" style="color:#FFFFFF;text-decoration:none;font-family:'Times New Roman',Times,serif;font-size:20px;font-weight:700;">__HOTEL_NAME__</a>
                                        <div style="margin-top:3px;color:#D8C398;font-size:9px;font-weight:700;letter-spacing:.22em;">DIRECT BOOKING</div>
                                      </td>
                                    </tr>
                                  </table>
                                </td>
                              </tr>
                            </table>
                            <div style="margin-top:30px;color:#D8C398;font-size:10px;font-weight:700;letter-spacing:.2em;">__EYEBROW__</div>
                            <h1 class="email-title" style="margin:10px 0 0;color:#FFFFFF;font-family:'Times New Roman',Times,serif;font-size:36px;font-weight:700;line-height:1.16;letter-spacing:-.02em;">__TITLE__</h1>
                          </td>
                        </tr>
                        <tr>
                          <td class="email-pad" style="padding:34px 38px 36px;">
                            __GREETING__
                            __PARAGRAPHS__
                            __DETAILS__
                            __CTA__
                            __NOTICE__
                          </td>
                        </tr>
                        <tr>
                          <td class="email-pad" bgcolor="#F8F6F1" style="padding:24px 38px;border-top:1px solid #E6E2D8;text-align:center;">
                            <a href="__HOTEL_HOME__" target="_blank" style="color:#0F2A43;text-decoration:none;font-family:'Times New Roman',Times,serif;font-size:16px;font-weight:700;">__HOTEL_NAME__</a>
                            <div style="margin-top:8px;color:#64748B;font-size:11px;line-height:1.65;">__HOTEL_CONTACT__</div>
                            <div style="margin-top:10px;color:#94A3B8;font-size:10px;">© __YEAR__ __HOTEL_NAME__. Email giao dịch tự động.</div>
                          </td>
                        </tr>
                      </table>
                    </td>
                  </tr>
                </table>
              </body>
            </html>
            """;
}
