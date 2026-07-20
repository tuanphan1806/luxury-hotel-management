import type { Metadata } from "next";
import ContactRequestPage from "@/components/guest/ContactRequestPage";

export const metadata: Metadata = {
  title: "Liên hệ hỗ trợ",
  description: "Gửi yêu cầu hỗ trợ đặt phòng, thanh toán, tài khoản hoặc kỳ lưu trú đến Luxury Hotel.",
};

export default function ContactPage() {
  return <ContactRequestPage />;
}
