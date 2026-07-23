"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import GuestPageHero from "@/components/guest/GuestPageHero";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { apiClient, authSession, getApiErrorMessage } from "@/lib/api";
import { siteConfig } from "@/lib/siteConfig";
import { GALLERY_HERO_IMAGES } from "@/constants/content";

interface ContactProfile {
  fullName?: string;
  email?: string;
  phone?: string;
}

const initialForm = {
  name: "",
  email: "",
  phone: "",
  bookingCode: "",
  topic: "",
  message: "",
};

export default function ContactRequestPage() {
  const { localize } = useLanguage();
  const [form, setForm] = useState(initialForm);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [reference, setReference] = useState<number | null>(null);

  useEffect(() => {
    authSession.getCurrentUser<ContactProfile>(false).then((profile) => {
      if (!profile) return;
      setForm((current) => ({
        ...current,
        name: current.name || profile.fullName || "",
        email: current.email || profile.email || "",
        phone: current.phone || profile.phone || "",
      }));
    });
  }, []);

  const topicOptions = [
    ["BOOKING", localize("Đặt phòng mới", "New reservation")],
    ["PAYMENT", localize("Thanh toán chưa cập nhật", "Payment not updated")],
    ["CHANGE_CANCEL", localize("Thay đổi hoặc hủy đơn", "Change or cancel booking")],
    ["CHECKIN_STAY", localize("Nhận phòng hoặc trong kỳ lưu trú", "Check-in or during stay")],
    ["ACCOUNT", localize("Tài khoản và đăng nhập", "Account and sign-in")],
    ["TECHNICAL", localize("Lỗi website", "Website issue")],
    ["OTHER", localize("Yêu cầu khác", "Other request")],
  ];

  const submitRequest = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const name = form.name.trim();
    const email = form.email.trim();
    const phone = form.phone.trim();
    const bookingCode = form.bookingCode.trim();
    const message = form.message.trim();
    const topicLabel = topicOptions.find(([value]) => value === form.topic)?.[1];

    if (!name || !email || !form.topic || !message) {
      setError(localize("Vui lòng hoàn thành các trường bắt buộc.", "Please complete all required fields."));
      return;
    }
    if (phone && !/^[+\d][\d\s().-]{7,29}$/.test(phone)) {
      setError(localize("Số điện thoại chưa đúng định dạng.", "The phone number format is not valid."));
      return;
    }

    setIsSubmitting(true);
    setError("");
    try {
      const response = await apiClient.post("/api/contact-messages", {
        name,
        email,
        phone,
        subject: topicLabel,
        message: bookingCode ? `${localize("Mã đặt phòng", "Booking code")}: ${bookingCode}\n\n${message}` : message,
      });
      setReference(Number(response.data?.data?.id) || null);
      setForm(initialForm);
    } catch (submitError) {
      setError(getApiErrorMessage(submitError, localize("Không thể gửi yêu cầu lúc này. Vui lòng thử lại sau.", "The request could not be sent. Please try again later.")));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen bg-[#F1F0EA] text-[#0F2A43]">
      <GuestPageHero
        imageSrc={GALLERY_HERO_IMAGES.contact}
        imageAlt={localize("Lễ tân hỗ trợ khách tại Luxury Hotel", "Guest assistance at Luxury Hotel")}
        eyebrow={localize("Liên hệ hỗ trợ", "Contact support")}
        title={localize("Gửi thông tin một lần, để chúng tôi xử lý đúng việc.", "Tell us once, so we can handle the right issue.")}
        description={localize("Biểu mẫu này chuyển yêu cầu trực tiếp đến bộ phận vận hành. Hãy thêm mã đặt phòng nếu vấn đề liên quan đến một kỳ lưu trú cụ thể.", "This form sends your request directly to hotel operations. Add the booking code when the issue relates to a specific stay.")}
        className="min-h-[54dvh] md:min-h-[540px]"
      />

      <section className="deferred-section px-5 py-16 md:px-8 md:py-20">
        <div className="mx-auto grid max-w-6xl gap-8 lg:grid-cols-[0.72fr_1.28fr] lg:items-start">
          <aside className="space-y-5 lg:sticky lg:top-28">
            <div className="rounded-[1.5rem] bg-[#0F2A43] p-6 text-white">
              <p className="text-[10px] font-bold uppercase tracking-[0.2em] text-[#D8C398]">{localize("Trước khi gửi", "Before you send")}</p>
              <h2 className="mt-4 font-serif text-3xl font-bold">{localize("Thông tin giúp xử lý nhanh", "Details that speed up support")}</h2>
              <ul className="mt-5 grid gap-4 text-sm leading-6 text-white/74">
                {[localize("Mã đặt phòng nếu đã có đơn.", "Booking code when available."), localize("Thời gian, số tiền và mã giao dịch nếu liên quan thanh toán.", "Time, amount, and bank reference for payment issues."), localize("Thiết bị, trình duyệt và thao tác gây lỗi nếu website có vấn đề.", "Device, browser, and the action that triggered a website issue.")].map((item) => <li key={item} className="flex gap-3"><span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-[#B8944F]" />{item}</li>)}
              </ul>
            </div>
            <div className="rounded-[1.5rem] border border-[#0F2A43]/14 bg-[#EAE2D2] p-6">
              <p className="text-[10px] font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize("Kênh trực tiếp", "Direct channels")}</p>
              <dl className="mt-4 grid gap-4 text-sm">
                <div><dt className="text-xs text-[#66727C]">Email</dt><dd className="mt-1 font-bold">{siteConfig.contact.email || localize("Cập nhật trong cấu hình khách sạn", "Configured by the hotel")}</dd></div>
                <div><dt className="text-xs text-[#66727C]">{localize("Điện thoại", "Phone")}</dt><dd className="mt-1 font-bold">{siteConfig.contact.phone || localize("Lễ tân tiếp nhận qua biểu mẫu", "Use the support form")}</dd></div>
                <div><dt className="text-xs text-[#66727C]">{localize("Tiếp nhận", "Availability")}</dt><dd className="mt-1 font-bold">{localize("24/7 · ưu tiên sự cố đang lưu trú", "24/7 · in-stay issues prioritized")}</dd></div>
              </dl>
              <Link href="/support" className="mt-5 inline-flex min-h-11 items-center text-sm font-bold text-[#0F2A43]">{localize("Xem câu hỏi thường gặp", "Browse common questions")} →</Link>
            </div>
          </aside>

          <div className="rounded-[1.75rem] border border-[#0F2A43]/16 bg-[#FBFAF6] p-6 shadow-[0_22px_60px_rgba(15,42,67,0.09)] md:p-8">
            {reference !== null ? (
              <div className="flex min-h-[34rem] flex-col items-center justify-center text-center" role="status">
                <span className="flex h-16 w-16 items-center justify-center rounded-full bg-emerald-700 text-2xl font-bold text-white">✓</span>
                <p className="mt-6 text-[10px] font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize("Đã tiếp nhận", "Request received")}</p>
                <h2 className="mt-3 font-serif text-4xl font-bold">{localize("Yêu cầu đã đến bộ phận hỗ trợ.", "Your request has reached support.")}</h2>
                <p className="mt-4 max-w-lg text-sm leading-7 text-[#66727C]">{localize("Giữ lại mã bên dưới nếu cần bổ sung thông tin. Không gửi lại cùng một vấn đề để tránh tách lịch sử xử lý.", "Keep the reference below if you need to add details. Avoid resubmitting the same issue so its history stays together.")}</p>
                {reference > 0 && <span className="mt-5 rounded-full bg-[#EAE2D2] px-5 py-2 text-sm font-bold tabular-nums">#{reference}</span>}
                <div className="mt-7 flex flex-wrap justify-center gap-3"><Link href="/support" className="inline-flex min-h-11 items-center rounded-lg border border-[#0F2A43]/18 px-5 text-sm font-bold">{localize("Về trung tâm hỗ trợ", "Back to support")}</Link><button type="button" onClick={() => setReference(null)} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white">{localize("Gửi yêu cầu khác", "Send another request")}</button></div>
              </div>
            ) : (
              <form onSubmit={submitRequest} noValidate>
                <div className="border-b border-[#0F2A43]/14 pb-6"><p className="text-[10px] font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize("Phiếu hỗ trợ", "Support request")}</p><h2 className="mt-3 font-serif text-3xl font-bold md:text-4xl">{localize("Bạn cần chúng tôi hỗ trợ gì?", "How can we help?")}</h2><p className="mt-3 text-sm leading-6 text-[#66727C]">{localize("Các trường có dấu * là bắt buộc.", "Fields marked * are required.")}</p></div>
                {error && <p className="mt-5 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-semibold text-rose-700" role="alert">{error}</p>}

                <div className="mt-6 grid gap-5 md:grid-cols-2">
                  <label className="grid gap-2 text-xs font-bold text-[#0F2A43]">{localize("Họ và tên *", "Full name *")}<input required maxLength={150} autoComplete="name" value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} className="min-h-12 rounded-lg border border-[#0F2A43]/16 bg-white px-4 text-sm font-medium outline-none transition focus:border-[#B8944F]" /></label>
                  <label className="grid gap-2 text-xs font-bold text-[#0F2A43]">Email *<input required type="email" maxLength={255} autoComplete="email" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} className="min-h-12 rounded-lg border border-[#0F2A43]/16 bg-white px-4 text-sm font-medium outline-none transition focus:border-[#B8944F]" /></label>
                  <label className="grid gap-2 text-xs font-bold text-[#0F2A43]">{localize("Số điện thoại", "Phone")}<input type="tel" maxLength={30} autoComplete="tel" value={form.phone} onChange={(event) => setForm({ ...form, phone: event.target.value })} placeholder="+84 ..." className="min-h-12 rounded-lg border border-[#0F2A43]/16 bg-white px-4 text-sm font-medium outline-none transition focus:border-[#B8944F]" /></label>
                  <label className="grid gap-2 text-xs font-bold text-[#0F2A43]">{localize("Mã đặt phòng", "Booking code")}<input maxLength={80} value={form.bookingCode} onChange={(event) => setForm({ ...form, bookingCode: event.target.value })} placeholder={localize("Nếu yêu cầu liên quan đến đơn", "If related to a booking")} className="min-h-12 rounded-lg border border-[#0F2A43]/16 bg-white px-4 text-sm font-medium outline-none transition focus:border-[#B8944F]" /></label>
                </div>

                <label className="mt-5 grid gap-2 text-xs font-bold text-[#0F2A43]">{localize("Nhóm yêu cầu *", "Request topic *")}<select required value={form.topic} onChange={(event) => setForm({ ...form, topic: event.target.value })} className="min-h-12 rounded-lg border border-[#0F2A43]/16 bg-white px-4 text-sm font-medium outline-none transition focus:border-[#B8944F]"><option value="">{localize("Chọn nội dung cần hỗ trợ", "Choose a support topic")}</option>{topicOptions.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
                <label className="mt-5 grid gap-2 text-xs font-bold text-[#0F2A43]">{localize("Mô tả yêu cầu *", "Request details *")}<textarea required rows={7} maxLength={5000} value={form.message} onChange={(event) => setForm({ ...form, message: event.target.value })} placeholder={localize("Mô tả sự việc, thời điểm xảy ra và kết quả bạn mong muốn...", "Describe what happened, when it occurred, and the outcome you need...")} className="min-h-44 rounded-lg border border-[#0F2A43]/16 bg-white px-4 py-3 text-sm font-medium leading-6 outline-none transition focus:border-[#B8944F]" /><span className="text-right text-[11px] font-medium tabular-nums text-[#66727C]">{form.message.length}/5000</span></label>

                <div className="mt-6 flex flex-col-reverse items-stretch justify-between gap-4 border-t border-[#0F2A43]/12 pt-5 sm:flex-row sm:items-center"><p className="max-w-md text-xs leading-5 text-[#66727C]">{localize("Không gửi mật khẩu, OTP, PIN hoặc toàn bộ thông tin thẻ/ngân hàng.", "Never send passwords, OTPs, PINs, or complete card/bank credentials.")}</p><button disabled={isSubmitting} className="min-h-12 rounded-lg bg-[#0F2A43] px-7 text-sm font-bold text-white transition hover:bg-[#091E30] disabled:cursor-not-allowed disabled:opacity-55">{isSubmitting ? localize("Đang gửi...", "Sending...") : localize("Gửi yêu cầu", "Send request")}</button></div>
              </form>
            )}
          </div>
        </div>
      </section>
    </div>
  );
}
