"use client";

import { FormEvent, useState } from "react";
import Link from "next/link";
import { apiClient, getApiErrorMessage } from "@/lib/api";
import { useLanguage } from "@/components/i18n/LanguageProvider";

export default function ForgotPasswordForm() {
  const { localize } = useLanguage();
  const [email, setEmail] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState("");

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError("");
    setIsSubmitting(true);
    try {
      await apiClient.post("/auth/forgot-password", { email: email.trim() });
      setSubmitted(true);
    } catch (requestError: unknown) {
      setError(getApiErrorMessage(
        requestError,
        localize("Không thể gửi email lúc này. Vui lòng thử lại sau.", "The email could not be sent. Please try again later."),
      ));
    } finally {
      setIsSubmitting(false);
    }
  };

  if (submitted) {
    return (
      <section aria-live="polite">
        <div className="flex h-14 w-14 items-center justify-center rounded-full bg-emerald-50 text-2xl text-emerald-700">✓</div>
        <h2 className="mt-6 text-3xl font-bold text-[#0F2A43]">{localize("Kiểm tra email của bạn", "Check your email")}</h2>
        <p className="mt-3 text-sm leading-7 text-[#66727C]">{localize("Nếu email khớp với một tài khoản đang hoạt động, chúng tôi đã gửi liên kết đặt lại mật khẩu. Hãy kiểm tra cả thư rác.", "If the email matches an active account, we sent a password reset link. Please also check your spam folder.")}</p>
        <div className="mt-8 flex flex-col gap-3 sm:flex-row">
          <Link href="/login" className="inline-flex min-h-11 items-center justify-center rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white">{localize("Về trang đăng nhập", "Back to sign in")}</Link>
          <button type="button" onClick={() => setSubmitted(false)} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43]">{localize("Gửi lại", "Send again")}</button>
        </div>
      </section>
    );
  }

  return (
    <section>
      <p className="text-sm font-bold text-[#80632F]">{localize("Khôi phục tài khoản", "Account recovery")}</p>
      <h2 className="mt-3 text-3xl font-bold text-[#0F2A43]">{localize("Quên mật khẩu", "Forgot password")}</h2>
      <p className="mt-3 text-sm leading-7 text-[#66727C]">{localize("Nhập email đã đăng ký để nhận liên kết đặt lại mật khẩu.", "Enter your registered email to receive a password reset link.")}</p>

      {error && <div role="alert" className="mt-5 rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm font-medium text-rose-700">{error}</div>}

      <form onSubmit={handleSubmit} className="mt-7 space-y-5">
        <label className="block text-sm font-bold text-[#27445F]" htmlFor="forgot-email">
          Email
          <input id="forgot-email" type="email" required autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} placeholder="you@example.com" className="mt-2 min-h-12 w-full rounded-lg border border-[#0F2A43]/20 px-4 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25" />
        </label>
        <button type="submit" disabled={isSubmitting} className="min-h-12 w-full rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white disabled:cursor-not-allowed disabled:opacity-60">
          {isSubmitting ? localize("Đang gửi...", "Sending...") : localize("Gửi liên kết đặt lại", "Send reset link")}
        </button>
      </form>
      <Link href="/login" className="mt-7 inline-flex min-h-11 items-center text-sm font-bold text-[#80632F] hover:underline">← {localize("Quay lại đăng nhập", "Back to sign in")}</Link>
    </section>
  );
}
