"use client";

import Link from "next/link";
import HotelBrand from "@/components/HotelBrand";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import { useLanguage } from "@/components/i18n/LanguageProvider";

interface AuthBrandPanelProps {
  mode: "login" | "signup" | "recovery";
}

export default function AuthBrandPanel({ mode }: AuthBrandPanelProps) {
  const { localize } = useLanguage();
  const isSignup = mode === "signup";
  const isRecovery = mode === "recovery";

  const highlights = isRecovery
    ? [
        localize("Liên kết bảo mật được gửi tới email đã đăng ký", "Secure links are sent to your registered email"),
        localize("Liên kết chỉ sử dụng được một lần", "Each link can only be used once"),
        localize("Quay lại đăng nhập ngay sau khi hoàn tất", "Return to sign in as soon as you finish"),
      ]
    : isSignup
    ? [
        localize("Thông tin khách được tự động điền khi đặt phòng", "Guest details are filled in automatically when booking"),
        localize("Theo dõi toàn bộ trạng thái lưu trú", "Track every stage of your stay"),
        localize("Lưu hóa đơn và lịch sử đặt phòng", "Keep invoices and booking history together"),
      ]
    : [
        localize("Xem và quản lý các đơn đặt phòng", "View and manage your reservations"),
        localize("Theo dõi thanh toán và tải hóa đơn", "Track payments and download invoices"),
        localize("Gửi đánh giá sau kỳ nghỉ", "Share a review after your stay"),
      ];

  return (
    <aside className="relative hidden min-h-[100dvh] overflow-hidden border-r border-[#0F2A43]/10 bg-[#D9D8D1] lg:block lg:w-[48%] xl:w-[52%]">
      <ProgressiveImage
        src="/hotel-lobby.png"
        alt=""
        fill
        priority
        quality={92}
        sizes="(min-width: 1280px) 52vw, 48vw"
        className="object-cover object-center"
      />
      <div aria-hidden="true" className="absolute inset-0 z-[1] bg-[#071E31]/12" />
      <div aria-hidden="true" className="absolute inset-0 z-[1] bg-[linear-gradient(180deg,rgba(7,30,49,0.08)_0%,rgba(7,30,49,0.18)_42%,rgba(7,30,49,0.68)_100%),linear-gradient(90deg,rgba(7,30,49,0.22)_0%,rgba(7,30,49,0.03)_76%)]" />

      <div className="absolute inset-0 z-10 flex flex-col px-7 py-6 xl:px-10 xl:py-8">
        <Link
          href="/"
          aria-label={localize("Luxury Hotel - Trang chủ", "Luxury Hotel - Home")}
          className="inline-flex min-h-11 w-fit items-center rounded-xl px-1 py-1 text-white transition duration-200 ease-out hover:-translate-y-0.5 hover:bg-white/8 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#D8C398] focus-visible:ring-offset-2 focus-visible:ring-offset-[#071E31]"
        >
          <HotelBrand
            tone="inverse"
            descriptor="DIRECT BOOKING"
            markClassName="h-11 w-11"
            wordmarkClassName="text-xl"
          />
        </Link>

        <div className="my-auto max-w-[34rem] pb-4 text-[#FBFAF6]">
          <p className="text-[0.66rem] font-extrabold uppercase tracking-[0.28em] text-[#E7CF98]">
            {isRecovery
              ? localize("Bảo mật tài khoản", "Account security")
              : isSignup
              ? localize("Một tài khoản, trọn hành trình", "One account, one complete journey")
              : localize("Kỳ nghỉ của bạn, được kết nối", "Your stay, seamlessly connected")}
          </p>
          <h1 className="mt-4 max-w-[31rem] text-[clamp(2.35rem,4.2vw,4rem)] font-extrabold leading-[0.98] tracking-[-0.045em] text-white drop-shadow-[0_4px_24px_rgba(0,0,0,0.28)]">
            {isRecovery
              ? localize("Khôi phục quyền truy cập an toàn.", "Recover access securely.")
              : isSignup
              ? localize("Một tài khoản cho toàn bộ kỳ nghỉ.", "One account for your entire stay.")
              : localize("Trở lại hành trình đang chờ bạn.", "Return to the journey waiting for you.")}
          </h1>
          <p className="mt-5 max-w-[31rem] text-sm font-medium leading-7 text-white/88 xl:text-base">
            {isRecovery
              ? localize(
                  "Nhận lại liên kết xác thực hoặc đặt lại mật khẩu trong cùng một trải nghiệm bảo mật và rõ ràng.",
                  "Request a new verification or password reset link through one clear, secure experience.",
                )
              : isSignup
              ? localize(
                  "Tạo tài khoản để đặt phòng nhanh hơn và theo dõi mọi thay đổi trong một nơi.",
                  "Create an account to book faster and track every update in one place.",
                )
              : localize(
                  "Đăng nhập để xem đơn đặt phòng, thanh toán, hóa đơn và những việc cần hoàn tất trước khi đến.",
                  "Sign in to view reservations, payments, invoices, and everything to complete before arrival.",
                )}
          </p>

          <ul className="mt-6 space-y-3 border-t border-white/20 pt-5" aria-label={localize("Tiện ích tài khoản", "Account benefits")}>
            {highlights.map((item) => (
              <li key={item} className="flex items-center gap-3 text-sm font-bold leading-5 text-white">
                <span aria-hidden="true" className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full border border-[#D8C398]/70 bg-[#0F2A43]/45 text-xs text-[#F0D99F]">
                  ✓
                </span>
                <span>{item}</span>
              </li>
            ))}
          </ul>
        </div>

        <p className="text-[0.68rem] font-semibold text-white/70">© 2026 Luxury Hotel</p>
      </div>
    </aside>
  );
}
