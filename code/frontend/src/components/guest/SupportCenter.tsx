"use client";

import Link from "next/link";
import GuestPageHero from "@/components/guest/GuestPageHero";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { GALLERY_HERO_IMAGES } from "@/constants/content";

const faqs = [
  {
    question: { vi: "Làm thế nào để đặt phòng?", en: "How do I make a reservation?" },
    answer: { vi: "Chọn ngày giờ, số khách và số lượng của từng hạng phòng. Kiểm tra lại thông tin liên hệ, sau đó chọn đặt cọc 50% hoặc thanh toán 100% bằng SePay VietQR.", en: "Choose dates, guests, and the quantity required for each room type. Review your contact details, then choose a 50% deposit or 100% payment through SePay VietQR." },
  },
  {
    question: { vi: "Tôi có thể thay đổi hoặc hủy lịch không?", en: "Can I change or cancel a reservation?" },
    answer: { vi: "Mở Lịch sử đặt phòng trong menu tài khoản để xem hành động đang được phép. Đổi ngày, hạng phòng hoặc số lượng cần lễ tân xác nhận lại tồn phòng và chênh lệch chi phí.", en: "Open Booking history from the account menu to see currently available actions. Date, room type, or quantity changes require the front desk to reconfirm availability and any price difference." },
  },
  {
    question: { vi: "Làm sao biết thanh toán đã thành công?", en: "How do I know a payment succeeded?" },
    answer: { vi: "Trạng thái được cập nhật trong lịch sử đặt phòng và trang tra cứu sau khi hệ thống nhận hoặc đối soát giao dịch. Nếu tài khoản đã bị trừ tiền, không chuyển lại ngay.", en: "Status updates in Booking history and Find a booking after the transaction is received or reconciled. If your account was debited, do not transfer again immediately." },
  },
  {
    question: { vi: "Webhook đến muộn có làm mất thanh toán không?", en: "Can a delayed webhook lose my payment?" },
    answer: { vi: "Không. Thời gian giao dịch do ngân hàng cung cấp quyết định khoản tiền có đúng hạn; tác vụ đối soát sẽ tìm lại giao dịch sau thời gian gián đoạn.", en: "No. The bank provider timestamp determines whether the transfer was on time, and reconciliation searches for transactions after downtime." },
  },
  {
    question: { vi: "Tôi cần hỗ trợ trong thời gian lưu trú thì làm gì?", en: "How do I get help during my stay?" },
    answer: { vi: "Gửi yêu cầu kèm mã đặt phòng, số phòng nếu đã check-in và mô tả ngắn vấn đề. Với sự cố khẩn cấp, hãy liên hệ trực tiếp lễ tân tại quầy.", en: "Send a request with your booking code, room number after check-in, and a brief description. For urgent issues, contact the front desk directly." },
  },
  {
    question: { vi: "Tôi cần chuẩn bị gì khi gửi yêu cầu?", en: "What should I include in a support request?" },
    answer: { vi: "Cung cấp mã đơn, email hoặc số điện thoại đã dùng khi đặt. Với vấn đề thanh toán, thêm thời gian chuyển, số tiền và mã giao dịch; với lỗi website, đính kèm mô tả thiết bị và ảnh chụp nếu có.", en: "Include the booking code and the email or phone used to book. For payment issues, add transfer time, amount, and bank reference; for website issues, describe the device and include a screenshot when possible." },
  },
] as const;

export default function SupportCenter() {
  const { localize } = useLanguage();

  return (
    <div className="min-h-screen bg-[#F1F0EA] text-[#0F2A43]">
      <GuestPageHero
        imageSrc={GALLERY_HERO_IMAGES.support}
        imageAlt={localize("Không gian hỗ trợ khách lưu trú", "Guest support at Luxury Hotel")}
        eyebrow={localize("Trung tâm hỗ trợ", "Support center")}
        title={localize("Chúng tôi đồng hành trong từng bước lưu trú.", "Support for every step of your stay.")}
        description={localize("Tìm câu trả lời nhanh về đặt phòng, thanh toán, thay đổi lịch và hỗ trợ vận hành.", "Find clear answers about reservations, payments, schedule changes, and stay support.")}
        actions={(
          <>
            <Link href="/contact" className="inline-flex min-h-12 items-center rounded-lg bg-[#D8C398] px-6 text-sm font-bold text-[#091E30] transition hover:bg-[#C8A35B]">{localize("Gửi yêu cầu hỗ trợ", "Send a support request")}</Link>
            <Link href="/booking/lookup" className="inline-flex min-h-12 items-center rounded-lg border border-white/30 bg-white/6 px-6 text-sm font-bold text-white transition hover:border-[#D8C398] hover:bg-white/10">{localize("Tra cứu đơn", "Find a booking")}</Link>
          </>
        )}
        className="min-h-[58dvh] md:min-h-[570px]"
      />

      <section className="px-5 py-16 md:px-8 md:py-20" aria-labelledby="support-faq-title">
        <div className="mx-auto grid max-w-6xl gap-10 lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start">
          <div>
            <p className="text-[10px] font-bold uppercase tracking-[0.22em] text-[#80632F]">{localize("Câu hỏi thường gặp", "Frequently asked questions")}</p>
            <h2 id="support-faq-title" className="mt-4 max-w-3xl font-serif text-4xl font-bold md:text-5xl">{localize("Câu trả lời rõ ràng trước khi bạn cần chờ hỗ trợ.", "Clear answers before you need to wait for help.")}</h2>

            <div className="mt-9 border-y border-[#0F2A43]/18">
              {faqs.map((faq, index) => (
                <details key={faq.question.vi} className="group border-b border-[#0F2A43]/14 last:border-b-0" open={index === 0}>
                  <summary className="grid min-h-20 cursor-pointer list-none grid-cols-[2.5rem_1fr_auto] items-center gap-3 py-4 focus:outline-none">
                    <span className="font-serif text-lg tabular-nums text-[#80632F]">{String(index + 1).padStart(2, "0")}</span>
                    <span className="text-sm font-bold md:text-base">{localize(faq.question.vi, faq.question.en)}</span>
                    <span aria-hidden="true" className="flex h-9 w-9 items-center justify-center rounded-full border border-[#0F2A43]/24 text-lg transition group-open:rotate-45 group-open:border-[#B8944F] group-open:bg-[#EAE2D2]">+</span>
                  </summary>
                  <p className="pb-6 pl-[3.25rem] pr-12 text-sm leading-7 text-[#66727C]">{localize(faq.answer.vi, faq.answer.en)}</p>
                </details>
              ))}
            </div>
          </div>

          <aside className="rounded-[1.5rem] border border-[#0F2A43]/24 bg-[#FBFAF6] p-6 shadow-[0_18px_48px_rgba(15,42,67,0.08)] lg:sticky lg:top-28">
            <p className="text-[10px] font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize("Cần thêm trợ giúp?", "Need more help?")}</p>
            <h2 className="mt-4 font-serif text-3xl font-bold">{localize("Gửi đúng thông tin, nhận hỗ trợ nhanh hơn.", "Share the right details for faster support.")}</h2>
            <p className="mt-4 text-sm leading-7 text-[#66727C]">{localize("Đăng nhập để gửi yêu cầu gắn với tài khoản và đơn đặt phòng. Khách vãng lai vẫn có thể gửi biểu mẫu bằng email đã dùng khi đặt.", "Sign in to connect a request with your account and booking. Guest bookers can still use the form with their booking email.")}</p>
            <Link href="/contact" className="mt-6 inline-flex min-h-12 w-full items-center justify-center rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#091E30]">{localize("Gửi yêu cầu hỗ trợ", "Send support request")}</Link>
            <Link href="/booking-policy" className="mt-3 inline-flex min-h-11 w-full items-center justify-center text-xs font-bold text-[#66727C] transition hover:text-[#0F2A43]">{localize("Xem chính sách lưu trú", "Read stay policies")} →</Link>
            <div className="mt-5 border-t border-[#0F2A43]/12 pt-5 text-xs leading-6 text-[#66727C]"><strong className="text-[#0F2A43]">{localize("Lưu ý an toàn:", "Safety note:")}</strong> {localize("Khách sạn không yêu cầu OTP, PIN hoặc mật khẩu ngân hàng.", "The hotel never asks for OTPs, PINs, or banking passwords.")}</div>
          </aside>
        </div>
      </section>
    </div>
  );
}
