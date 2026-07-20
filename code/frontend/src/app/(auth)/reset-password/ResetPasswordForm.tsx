"use client";

import { FormEvent, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { apiClient, getApiErrorMessage } from "@/lib/api";
import { useLanguage } from "@/components/i18n/LanguageProvider";

export default function ResetPasswordForm() {
  const { localize } = useLanguage();
  const searchParams = useSearchParams();
  const token = searchParams.get("token")?.trim() ?? "";
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [success, setSuccess] = useState(false);
  const [error, setError] = useState("");

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!token) {
      setError(localize("Liên kết đặt lại mật khẩu không hợp lệ.", "The password reset link is invalid."));
      return;
    }
    if (password.length < 8) {
      setError(localize("Mật khẩu mới phải có ít nhất 8 ký tự.", "The new password must contain at least 8 characters."));
      return;
    }
    if (password !== confirmPassword) {
      setError(localize("Xác nhận mật khẩu không khớp.", "Password confirmation does not match."));
      return;
    }

    setError("");
    setIsSubmitting(true);
    try {
      await apiClient.post("/auth/reset-password", { token, password, confirmPassword });
      setSuccess(true);
    } catch (requestError: unknown) {
      setError(getApiErrorMessage(
        requestError,
        localize("Không thể đặt lại mật khẩu. Liên kết có thể đã hết hạn.", "The password could not be reset. The link may have expired."),
      ));
    } finally {
      setIsSubmitting(false);
    }
  };

  if (success) {
    return (
      <section aria-live="polite">
        <div className="flex h-14 w-14 items-center justify-center rounded-full bg-emerald-50 text-2xl text-emerald-700">✓</div>
        <h2 className="mt-6 text-3xl font-bold text-[#0F2A43]">{localize("Đã đổi mật khẩu", "Password updated")}</h2>
        <p className="mt-3 text-sm leading-7 text-[#66727C]">{localize("Mật khẩu mới đã được lưu. Bạn có thể đăng nhập lại ngay bây giờ.", "Your new password has been saved. You can sign in now.")}</p>
        <Link href="/login" className="mt-8 inline-flex min-h-12 items-center justify-center rounded-lg bg-[#0F2A43] px-6 text-sm font-bold text-white">{localize("Đăng nhập", "Sign in")}</Link>
      </section>
    );
  }

  return (
    <section>
      <p className="text-sm font-bold text-[#80632F]">{localize("Bảo mật tài khoản", "Account security")}</p>
      <h2 className="mt-3 text-3xl font-bold text-[#0F2A43]">{localize("Đặt mật khẩu mới", "Set a new password")}</h2>
      <p className="mt-3 text-sm leading-7 text-[#66727C]">{localize("Mật khẩu cần có ít nhất 8 ký tự.", "Your password must contain at least 8 characters.")}</p>

      {!token && <div role="alert" className="mt-5 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm font-medium text-amber-800">{localize("Liên kết thiếu token. Vui lòng mở đúng liên kết trong email.", "The token is missing. Please open the complete link from your email.")}</div>}
      {error && <div role="alert" className="mt-5 rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm font-medium text-rose-700">{error}</div>}

      <form onSubmit={handleSubmit} className="mt-7 space-y-5">
        <label className="block text-sm font-bold text-[#27445F]" htmlFor="reset-password">
          {localize("Mật khẩu mới", "New password")}
          <input id="reset-password" type={showPassword ? "text" : "password"} required minLength={8} maxLength={72} autoComplete="new-password" value={password} onChange={(event) => setPassword(event.target.value)} className="mt-2 min-h-12 w-full rounded-lg border border-[#0F2A43]/20 px-4 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25" />
        </label>
        <label className="block text-sm font-bold text-[#27445F]" htmlFor="reset-confirm-password">
          {localize("Xác nhận mật khẩu", "Confirm password")}
          <input id="reset-confirm-password" type={showPassword ? "text" : "password"} required minLength={8} maxLength={72} autoComplete="new-password" value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} className="mt-2 min-h-12 w-full rounded-lg border border-[#0F2A43]/20 px-4 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25" />
        </label>
        <label className="flex min-h-11 cursor-pointer items-center gap-3 text-sm font-medium text-[#66727C]"><input type="checkbox" checked={showPassword} onChange={(event) => setShowPassword(event.target.checked)} className="h-4 w-4 accent-[#0F2A43]" />{localize("Hiển thị mật khẩu", "Show passwords")}</label>
        <button type="submit" disabled={isSubmitting || !token} className="min-h-12 w-full rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white disabled:cursor-not-allowed disabled:opacity-60">{isSubmitting ? localize("Đang lưu...", "Saving...") : localize("Đặt lại mật khẩu", "Reset password")}</button>
      </form>
      <Link href="/forgot-password" className="mt-7 inline-flex min-h-11 items-center text-sm font-bold text-[#80632F] hover:underline">{localize("Yêu cầu liên kết mới", "Request a new link")}</Link>
    </section>
  );
}
