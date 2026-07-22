"use client";

import { FormEvent, useState } from "react";
import Link from "next/link";
import { apiClient } from "@/lib/api";
import { useLanguage } from "@/components/i18n/LanguageProvider";

export default function ResendVerificationForm() {
  const { localize } = useLanguage();
  const [email, setEmail] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState("");
  const [emailError, setEmailError] = useState("");

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const normalizedEmail = email.trim().toLowerCase();
    if (!normalizedEmail) {
      setEmailError(localize("Vui lòng nhập email đã đăng ký.", "Enter your registered email."));
      return;
    }
    if (!/^\S+@\S+\.\S+$/.test(normalizedEmail)) {
      setEmailError(localize("Email chưa đúng định dạng.", "Enter a valid email address."));
      return;
    }
    setEmailError("");
    setError("");
    setIsSubmitting(true);

    try {
      await apiClient.post("/auth/resend-verification", {
        email: normalizedEmail,
      });
      setSubmitted(true);
    } catch {
      setError(
        localize(
          "Không thể gửi email xác thực. Hãy kiểm tra tài khoản đang ở trạng thái chờ xác thực và thử lại.",
          "The verification email could not be sent. Make sure the account is pending verification and try again.",
        ),
      );
    } finally {
      setIsSubmitting(false);
    }
  };

  if (submitted) {
    return (
      <section aria-live="polite">
        <div className="mb-5 flex items-center gap-3">
          <span className="h-px w-9 bg-[#B8944F]" aria-hidden="true" />
          <p className="text-[11px] font-bold uppercase tracking-[0.22em] text-[#80632F]">Luxury Hotel</p>
        </div>
        <div className="flex h-14 w-14 items-center justify-center rounded-full border border-emerald-200 bg-emerald-50 text-2xl text-emerald-700">✓</div>
        <h2 className="mt-6 font-serif text-3xl font-semibold leading-tight tracking-tight text-[#0F2A43] sm:text-[2.35rem]">
          {localize("Kiểm tra email của bạn", "Check your email")}
        </h2>
        <p className="mt-3 max-w-md text-sm font-medium leading-7 text-[#66727C]">
          {localize(
            "Nếu email thuộc tài khoản đang chờ xác thực, chúng tôi đã gửi một liên kết xác thực mới. Hãy kiểm tra cả thư rác.",
            "If the email belongs to an account pending verification, we sent a new verification link. Please also check your spam folder.",
          )}
        </p>
        <div className="mt-8 flex flex-col gap-3 sm:flex-row">
          <Link href="/login" className="inline-flex min-h-12 flex-1 items-center justify-center rounded-xl bg-[#0F2A43] px-5 text-sm font-bold text-white shadow-[0_12px_28px_rgba(15,42,67,0.18)] transition hover:-translate-y-0.5 hover:bg-[#091E30] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2">
            {localize("Về trang đăng nhập", "Back to sign in")}
          </Link>
          <button type="button" onClick={() => setSubmitted(false)} className="min-h-12 rounded-xl border border-[#0F2A43]/20 bg-white px-5 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F7F5EF] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]/60">
            {localize("Gửi lại", "Send again")}
          </button>
        </div>
      </section>
    );
  }

  return (
    <section>
      <div className="mb-5">
        <div className="mb-3 flex items-center gap-3">
          <span className="h-px w-9 bg-[#B8944F]" aria-hidden="true" />
          <p className="text-[11px] font-bold uppercase tracking-[0.22em] text-[#80632F]">Luxury Hotel</p>
        </div>
        <p className="text-[11px] font-bold uppercase tracking-[0.16em] text-[#80632F]">{localize("Kích hoạt tài khoản", "Account activation")}</p>
      </div>
      <h2 className="font-serif text-3xl font-semibold leading-tight tracking-tight text-[#0F2A43] sm:text-[2.35rem]">
        {localize("Gửi lại email xác thực", "Resend verification email")}
      </h2>
      <p className="mt-3 max-w-md text-sm font-medium leading-7 text-[#66727C]">
        {localize(
          "Nhập email đã đăng ký để nhận liên kết xác thực mới cho tài khoản đang chờ kích hoạt.",
          "Enter your registered email to receive a new verification link for an account awaiting activation.",
        )}
      </p>

      {error && (
        <div role="alert" className="mt-5 rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm font-medium text-rose-700">
          {error}
        </div>
      )}

      <form noValidate onSubmit={handleSubmit} className="mt-7 space-y-5">
        <label className="block text-sm font-semibold text-[#0F2A43]" htmlFor="verification-email">
          Email
          <span className="relative mt-2 block">
            <span className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-[#66727C]" aria-hidden="true">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="4" width="20" height="16" rx="2" /><path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7" /></svg>
            </span>
            <input
              id="verification-email"
              type="email"
              autoComplete="email"
              autoFocus
              value={email}
              onChange={(event) => {
                setEmail(event.target.value);
                if (emailError) setEmailError("");
              }}
              aria-invalid={Boolean(emailError)}
              aria-describedby={emailError ? "verification-email-error" : undefined}
              placeholder="you@example.com"
              className="min-h-12 w-full rounded-xl border border-[#0F2A43]/14 bg-[#F7F5EF] py-2.5 pl-12 pr-4 text-sm shadow-[inset_0_1px_0_rgba(255,255,255,0.7)] outline-none transition hover:border-[#0F2A43]/24 focus:border-[#B8944F] focus:bg-white focus:ring-2 focus:ring-[#B8944F]/25"
            />
          </span>
        </label>
        {emailError && <p id="verification-email-error" className="-mt-3 text-xs font-semibold text-rose-700" role="alert">{emailError}</p>}
        <button type="submit" disabled={isSubmitting} className="flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-[#0F2A43] px-5 text-sm font-bold text-white shadow-[0_12px_28px_rgba(15,42,67,0.18)] transition hover:-translate-y-0.5 hover:bg-[#091E30] active:translate-y-px disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2">
          {isSubmitting ? <><span aria-hidden="true" className="h-4 w-4 animate-spin rounded-full border-2 border-white border-r-transparent" />{localize("Đang gửi...", "Sending...")}</> : localize("Gửi email xác thực", "Send verification email")}
        </button>
      </form>

      <Link href="/login" className="mt-7 inline-flex min-h-11 items-center rounded-lg text-sm font-bold text-[#80632F] transition hover:text-[#0F2A43] hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]/60">
        ← {localize("Quay lại đăng nhập", "Back to sign in")}
      </Link>
    </section>
  );
}
