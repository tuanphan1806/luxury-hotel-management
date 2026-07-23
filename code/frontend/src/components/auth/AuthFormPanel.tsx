"use client";

import Link from "next/link";
import { useLanguage } from "@/components/i18n/LanguageProvider";

interface AuthFormPanelProps {
  mode: "login" | "signup" | "recovery";
  children: React.ReactNode;
}

export default function AuthFormPanel({ mode, children }: AuthFormPanelProps) {
  const { localize } = useLanguage();
  const panelLabel = mode === "signup"
    ? localize("Tạo tài khoản", "Create account")
    : mode === "recovery"
      ? localize("Khôi phục tài khoản", "Account recovery")
      : localize("Đăng nhập", "Sign in");

  return (
    <main className="relative z-10 flex min-h-[100dvh] flex-1 items-center justify-center overflow-x-hidden bg-transparent px-5 py-6 sm:px-8 sm:py-7 lg:bg-[#E9ECE7] lg:px-10 lg:py-3 xl:px-12">
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 hidden overflow-hidden lg:block">
        <div className="absolute inset-y-0 left-0 hidden w-px bg-[#B8944F]/40 lg:block" />
        <div className="absolute right-0 top-0 h-40 w-40 border-b border-l border-[#B8944F]/16 bg-[#F1F0EA]/65" />
        <div className="absolute bottom-0 left-0 h-28 w-28 border-r border-t border-[#0F2A43]/8 bg-[#FBFAF6]/35" />
      </div>

      <div className="relative w-full max-w-[35rem]">
        <div className="mb-4 flex items-center justify-between gap-4 rounded-xl border border-white/25 bg-[#091E30]/42 px-3 py-1.5 text-white shadow-lg backdrop-blur-md lg:hidden">
          <Link href="/" className="inline-flex min-h-11 items-center gap-2 rounded-lg text-sm font-bold text-white transition hover:text-[#E4C77F] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#E4C77F] lg:hidden">
            <span aria-hidden="true">←</span> Luxury Hotel
          </Link>
          <Link href="/support" className="ml-auto inline-flex min-h-11 items-center rounded-lg text-xs font-bold text-white transition hover:text-[#E4C77F] hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#E4C77F]">
            {localize("Cần hỗ trợ?", "Need help?")}
          </Link>
        </div>

        <section
          className="relative flex flex-col overflow-hidden rounded-[1.75rem] border border-[#0F2A43]/12 bg-[#FBFAF6] shadow-[0_28px_80px_rgba(15,42,67,0.13)] lg:min-h-[min(44.5rem,calc(100dvh-1.5rem))]"
          aria-label={panelLabel}
        >
          <div aria-hidden="true" className="grid h-1 grid-cols-[0.28fr_0.72fr]">
            <span className="bg-[#B8944F]" />
            <span className="bg-[#0F2A43]" />
          </div>
          <div className="flex flex-1 flex-col justify-center p-5 sm:p-7 lg:p-8">
            {children}
          </div>
        </section>

      </div>
    </main>
  );
}
