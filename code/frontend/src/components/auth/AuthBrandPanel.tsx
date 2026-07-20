"use client";

import ProgressiveImage from "@/components/UI/ProgressiveImage";
import HotelBrand from "@/components/HotelBrand";
import Link from "next/link";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { siteConfig } from "@/lib/siteConfig";

interface AuthBrandPanelProps {
  mode: "login" | "signup";
}

export default function AuthBrandPanel({ mode }: AuthBrandPanelProps) {
  const { localize } = useLanguage();
  const isLogin = mode === "login";
  const features = isLogin
    ? [
        localize("Xem và quản lý các đơn đặt phòng", "View and manage your reservations"),
        localize("Theo dõi thanh toán và tải hóa đơn", "Track payments and download invoices"),
        localize("Gửi đánh giá sau kỳ nghỉ", "Review your stay after checkout"),
      ]
    : [
        localize("Thông tin khách được tự động điền khi đặt phòng", "Guest details are filled in automatically"),
        localize("Theo dõi toàn bộ trạng thái lưu trú", "Track every stage of your stay"),
        localize("Lưu hóa đơn và lịch sử đặt phòng", "Keep invoices and booking history"),
      ];

  return (
    <aside className="relative hidden min-h-[100dvh] overflow-hidden bg-[#091E30] lg:flex lg:w-[46%] xl:w-1/2" aria-label={localize("Giới thiệu khách sạn", "Hotel introduction")}>
      <ProgressiveImage src="/hotel-lobby.png" alt={localize(`Sảnh ${siteConfig.name}`, `${siteConfig.name} lobby`)} fill priority className="object-cover opacity-65" sizes="50vw" />
      <div className="absolute inset-0 bg-gradient-to-t from-[#061814]/95 via-[#091E30]/62 to-[#091E30]/35" />

      <div className="relative z-10 flex w-full flex-col justify-between p-10 xl:p-14">
        <Link href="/" aria-label={localize(`Về trang chủ ${siteConfig.name}`, `Back to ${siteConfig.name} home`)} className="flex w-fit rounded-lg text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#D8C398] focus-visible:ring-offset-4 focus-visible:ring-offset-[#091E30]">
          <HotelBrand tone="inverse" descriptor="DIRECT BOOKING" wordmarkClassName="text-xl" />
        </Link>

        <div className="max-w-xl pb-4">
          <p className="text-xs font-bold uppercase tracking-[0.24em] text-[#D8C398]">
            {localize("Kỳ nghỉ của bạn, được kết nối", "Your stay, connected")}
          </p>
          <h1 className="mt-4 font-serif text-4xl font-semibold leading-[1.08] text-white xl:text-6xl">
            {isLogin
              ? localize("Trở lại hành trình đang chờ bạn.", "Return to the journey waiting for you.")
              : localize("Một tài khoản cho toàn bộ kỳ nghỉ.", "One account for your entire stay.")}
          </h1>
          <p className="mt-5 max-w-lg text-sm font-medium leading-7 text-white/70 xl:text-base">
            {isLogin
              ? localize("Đăng nhập để xem đơn đặt phòng, thanh toán, hóa đơn và những việc cần hoàn tất trước khi đến.", "Sign in to review reservations, payments, invoices, and anything to complete before arrival.")
              : localize("Tạo tài khoản khách hàng để đặt phòng nhanh hơn và theo dõi mọi thay đổi trong một nơi.", "Create a guest account to book faster and track every update in one place.")}
          </p>

          <ul className="mt-8 grid gap-3 text-sm font-semibold text-white/86">
            {features.map((feature) => (
              <li key={feature} className="flex items-center gap-3">
                <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full border border-[#D8C398]/40 bg-[#D8C398]/10 text-xs text-[#D8C398]">✓</span>
                {feature}
              </li>
            ))}
          </ul>
        </div>

        <p className="text-xs font-medium text-white/45">© 2026 {siteConfig.name}</p>
      </div>
    </aside>
  );
}
