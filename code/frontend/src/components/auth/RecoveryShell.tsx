"use client";

import ProgressiveImage from "@/components/UI/ProgressiveImage";
import HotelBrand from "@/components/HotelBrand";
import Link from "next/link";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { siteConfig } from "@/lib/siteConfig";

export default function RecoveryShell({ children }: { children: React.ReactNode }) {
  const { localize } = useLanguage();

  return (
    <>
      <aside className="relative hidden min-h-screen overflow-hidden lg:flex lg:w-1/2">
        <ProgressiveImage src="/hotel-lobby.png" alt={localize(`Sảnh khách sạn ${siteConfig.name}`, `${siteConfig.name} hotel lobby`)} fill priority className="object-cover" />
        <div className="absolute inset-0 bg-[#091327]/70" />
        <div className="relative z-10 flex w-full flex-col justify-between p-12">
          <Link href="/" aria-label={localize(`Về trang chủ ${siteConfig.name}`, `Back to ${siteConfig.name} home`)} className="w-fit rounded-lg bg-[#0F2A43]/78 p-2.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">
            <HotelBrand tone="inverse" descriptor="DIRECT BOOKING" wordmarkClassName="text-xl" />
          </Link>
          <div className="max-w-lg pb-8 text-white">
            <p className="text-sm font-bold text-[#E0C58E]">{localize("Bảo mật tài khoản", "Account security")}</p>
            <h1 className="mt-4 text-4xl font-bold leading-tight">{localize("Khôi phục quyền truy cập an toàn", "Recover access securely")}</h1>
            <p className="mt-4 max-w-md text-sm leading-7 text-white/75">{localize("Liên kết đặt lại mật khẩu chỉ sử dụng được một lần và tự hết hạn sau 30 phút.", "The password reset link can only be used once and expires automatically after 30 minutes.")}</p>
          </div>
        </div>
      </aside>

      <main className="flex min-h-screen flex-1 items-center justify-center bg-white px-5 py-10 sm:px-8 lg:px-16">
        <div className="w-full max-w-md">
          <Link href="/" className="mb-10 inline-flex min-h-11 items-center text-sm font-bold text-[#0F2A43] hover:text-[#80632F] lg:hidden">
            ← {localize("Về trang chủ", "Back to homepage")}
          </Link>
          {children}
        </div>
      </main>
    </>
  );
}
