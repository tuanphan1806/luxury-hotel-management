"use client";

import Link from "next/link";
import { useLanguage } from "@/components/i18n/LanguageProvider";

const settingItems = [
  {
    eyebrow: { vi: "Tài khoản", en: "Account" },
    title: { vi: "Bảo mật và đăng nhập", en: "Security and sign-in" },
    description: { vi: "Đổi mật khẩu và kiểm tra các thiết lập giúp bảo vệ tài khoản của bạn.", en: "Change your password and review settings that help protect your account." },
    action: { vi: "Quản lý bảo mật", en: "Manage security" },
    href: "/account/security",
    icon: "lock",
  },
  {
    eyebrow: { vi: "Trải nghiệm", en: "Experience" },
    title: { vi: "Hiển thị và trợ năng", en: "Display and accessibility" },
    description: { vi: "Điều chỉnh cỡ chữ, độ tương phản, gạch chân liên kết và chuyển động.", en: "Adjust text size, contrast, link underlines, and motion." },
    action: { vi: "Tùy chỉnh hiển thị", en: "Customize display" },
    href: "/account/accessibility",
    icon: "accessibility",
  },
  {
    eyebrow: { vi: "Dữ liệu", en: "Data" },
    title: { vi: "Quyền riêng tư và dữ liệu", en: "Privacy and data" },
    description: { vi: "Xem cách khách sạn thu thập, sử dụng và bảo vệ thông tin của bạn.", en: "See how the hotel collects, uses, and protects your information." },
    action: { vi: "Xem chính sách", en: "Read policy" },
    href: "/privacy",
    icon: "shield",
  },
  {
    eyebrow: { vi: "Hỗ trợ", en: "Support" },
    title: { vi: "Hỗ trợ tài khoản", en: "Account support" },
    description: { vi: "Nhận trợ giúp khi gặp vấn đề về tài khoản, mật khẩu hoặc đăng nhập.", en: "Get help with account, password, or sign-in issues." },
    action: { vi: "Đến trung tâm hỗ trợ", en: "Open support center" },
    href: "/support",
    icon: "support",
  },
] as const;

function SettingIcon({ name }: { name: (typeof settingItems)[number]["icon"] }) {
  if (name === "lock") return <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8"><rect x="5" y="10" width="14" height="10" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3" /></svg>;
  if (name === "accessibility") return <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8"><circle cx="12" cy="5" r="2" /><path d="M5 9h14M12 7v13M8.5 20 12 13l3.5 7" /></svg>;
  if (name === "shield") return <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="M12 3 5 6v5c0 4.7 2.9 8.2 7 10 4.1-1.8 7-5.3 7-10V6l-7-3Z" /><path d="m9.5 12 1.7 1.7 3.6-4" /></svg>;
  return <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8"><circle cx="12" cy="12" r="9" /><path d="M8.5 9.5a3.5 3.5 0 1 1 5.5 2.9c-1.3.8-2 1.5-2 2.6M12 18h.01" /></svg>;
}

export default function AccountSettingsHubPage() {
  const { localize } = useLanguage();

  return (
    <div className="min-h-screen bg-[#F1F0EA] px-5 pb-24 pt-32 text-[#0F2A43] md:px-8 md:pt-36">
      <div className="mx-auto max-w-5xl">
        <Link href="/account" className="inline-flex min-h-11 items-center gap-2 text-sm font-bold text-[#66727C] transition hover:text-[#0F2A43]"><span aria-hidden="true">←</span>{localize("Hồ sơ cá nhân", "Personal profile")}</Link>
        <header className="mt-5 border-b border-[#0F2A43]/55 pb-8">
          <p className="flex items-center gap-3 text-[10px] font-bold uppercase tracking-[0.22em] text-[#80632F]"><span aria-hidden="true" className="h-px w-8 bg-[#B8944F]" />{localize("Thiết lập tài khoản", "Account preferences")}</p>
          <h1 className="mt-4 font-serif text-4xl font-bold md:text-6xl">{localize("Cài đặt theo nhu cầu", "Settings, your way")}</h1>
          <p className="mt-5 max-w-3xl text-sm leading-7 text-[#66727C] md:text-base">{localize("Quản lý bảo mật, quyền riêng tư và trải nghiệm sử dụng tại một nơi gọn gàng. Hồ sơ cá nhân được quản lý riêng để biểu mẫu không bị trùng lặp.", "Manage security, privacy, and your viewing experience in one clear place. Personal profile details remain in their own form to avoid duplication.")}</p>
        </header>

        <section className="mt-8 overflow-hidden rounded-[1.5rem] border border-[#0F2A43]/22 bg-[#FBFAF6]" aria-labelledby="settings-center-title">
          <div className="flex flex-wrap items-center justify-between gap-4 border-b border-[#0F2A43]/12 bg-[#F0EADF] px-6 py-5">
            <div><h2 id="settings-center-title" className="text-base font-bold">{localize("Trung tâm cài đặt", "Settings center")}</h2><p className="mt-1 text-xs text-[#66727C]">{localize("Chọn đúng nội dung bạn muốn thay đổi.", "Choose the area you want to update.")}</p></div>
            <span className="rounded-full border border-[#0F2A43]/22 bg-[#FBFAF6] px-3 py-1.5 text-[9px] font-bold uppercase tracking-[0.16em] text-[#80632F]">4 {localize("nhóm thiết lập", "setting groups")}</span>
          </div>

          <div className="divide-y divide-[#0F2A43]/14">
            {settingItems.map((item) => (
              <Link key={item.href} href={item.href} className="group grid gap-4 px-6 py-6 transition hover:bg-[#F1F0EA] sm:grid-cols-[auto_1fr_auto] sm:items-center">
                <span className="flex h-11 w-11 items-center justify-center rounded-full bg-[#EAE2D2] text-[#80632F]"><SettingIcon name={item.icon} /></span>
                <div>
                  <p className="text-[9px] font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize(item.eyebrow.vi, item.eyebrow.en)}</p>
                  <h3 className="mt-1 text-base font-bold">{localize(item.title.vi, item.title.en)}</h3>
                  <p className="mt-1 text-sm leading-6 text-[#66727C]">{localize(item.description.vi, item.description.en)}</p>
                </div>
                <span className="inline-flex min-h-11 items-center gap-3 text-sm font-bold text-[#0F2A43] sm:justify-self-end">{localize(item.action.vi, item.action.en)}<span className="flex h-9 w-9 items-center justify-center rounded-full border border-[#0F2A43]/35 transition group-hover:border-[#B8944F] group-hover:bg-[#0F2A43] group-hover:text-white">→</span></span>
              </Link>
            ))}
          </div>
        </section>

        <p className="mt-4 rounded-xl border border-[#B8944F]/28 bg-[#EAE2D2] px-5 py-4 text-xs leading-6 text-[#66727C]">{localize("Các thay đổi quan trọng về mật khẩu hoặc đăng nhập được bảo vệ bằng phiên xác thực hiện tại.", "Important password or sign-in changes are protected by your current authenticated session.")}</p>
      </div>
    </div>
  );
}
