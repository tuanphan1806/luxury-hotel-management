import type { Metadata } from "next";
import InformationPage, { type InformationSection } from "@/components/guest/InformationPage";

export const metadata: Metadata = {
  title: "Yêu cầu xóa dữ liệu",
  description: "Hướng dẫn yêu cầu xóa tài khoản và dữ liệu tại Luxury Hotel.",
};

const sections: InformationSection[] = [
  {
    title: { vi: "1. Gửi yêu cầu", en: "1. Submit a request" },
    body: {
      vi: "Mở Trung tâm hỗ trợ, chọn gửi yêu cầu và ghi rõ “Yêu cầu xóa dữ liệu”. Dùng đúng email của tài khoản; nếu yêu cầu liên quan đến đặt phòng, cung cấp thêm mã đơn để chúng tôi xác định đúng hồ sơ.",
      en: "Open the Support Center, choose to submit a request, and use the subject “Data deletion request”. Use the email linked to your account and include the booking code when the request concerns a reservation.",
    },
  },
  {
    title: { vi: "2. Xác minh danh tính", en: "2. Verify your identity" },
    body: {
      vi: "Để tránh xóa nhầm dữ liệu, Luxury Hotel có thể yêu cầu bạn xác nhận email hoặc một số thông tin đặt phòng không nhạy cảm. Không gửi mật khẩu, OTP, PIN hay thông tin đăng nhập ngân hàng.",
      en: "To prevent unauthorized deletion, Luxury Hotel may ask you to confirm your email or non-sensitive booking details. Never send a password, OTP, PIN, or bank login information.",
    },
  },
  {
    title: { vi: "3. Phạm vi xử lý", en: "3. What will be processed" },
    body: {
      vi: "Sau khi xác minh, chúng tôi sẽ xóa hoặc ẩn danh dữ liệu tài khoản và ngắt liên kết đăng nhập Google/Facebook khi phù hợp. Chứng từ tài chính, lịch sử đặt phòng và audit bắt buộc theo pháp luật, kế toán, phòng chống gian lận hoặc giải quyết tranh chấp chỉ được giữ trong phạm vi và thời hạn cần thiết.",
      en: "After verification, we delete or anonymize account data and disconnect Google/Facebook sign-in where appropriate. Financial, booking, and audit records required for legal, accounting, fraud-prevention, or dispute purposes are retained only to the extent and duration necessary.",
    },
  },
  {
    title: { vi: "4. Xác nhận kết quả", en: "4. Completion notice" },
    body: {
      vi: "Kết quả xử lý được gửi về email đã xác minh. Nếu cần giữ lại một phần dữ liệu, phản hồi sẽ nêu nhóm dữ liệu và lý do lưu giữ thay vì âm thầm bỏ qua yêu cầu.",
      en: "The result is sent to the verified email. If some records must be retained, the response identifies the data category and retention reason rather than silently ignoring the request.",
    },
  },
];

export default function DataDeletionPage() {
  return (
    <InformationPage
      eyebrow={{ vi: "Quyền dữ liệu", en: "Data rights" }}
      title={{ vi: "Yêu cầu xóa dữ liệu", en: "Data deletion request" }}
      intro={{
        vi: "Quy trình minh bạch để yêu cầu xóa tài khoản, dữ liệu đặt phòng hoặc liên kết đăng nhập mạng xã hội.",
        en: "A transparent process for requesting deletion of account data, booking data, or social sign-in connections.",
      }}
      sections={sections}
      notice={{
        vi: "Gửi yêu cầu từ email gắn với tài khoản để quá trình xác minh nhanh và an toàn hơn.",
        en: "Submit the request from the email linked to your account for faster, safer verification.",
      }}
      primaryAction={{ href: "/contact", label: { vi: "Gửi yêu cầu xóa", en: "Submit deletion request" } }}
      secondaryAction={{ href: "/privacy", label: { vi: "Chính sách bảo mật", en: "Privacy policy" } }}
    />
  );
}
