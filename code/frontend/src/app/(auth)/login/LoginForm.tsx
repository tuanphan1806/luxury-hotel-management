"use client";

import React, { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { apiClient, authSession, getApiErrorMessage, getApiErrorStatus } from '@/lib/api';
import {
  PLACEHOLDER_EMAIL,
  LABEL_PASSWORD,
  PLACEHOLDER_PASSWORD,
  BTN_SIGNIN,
  BTN_SIGNING,
  TEXT_SIGNUP_PROMPT,
  LINK_SIGNUP,
  FORM_TITLE_LOGIN,
  FORM_SUBTITLE_LOGIN,
  LABEL_LOGIN_IDENTIFIER,
} from '@/constants/auth';
import { useLanguage } from '@/components/i18n/LanguageProvider';
import SocialAuthOptions from '@/components/auth/SocialAuthOptions';

interface CurrentUserProfile {
  fullName?: string;
  username?: string;
  type?: string;
}

export default function LoginForm() {
  const router = useRouter();
  const { localize } = useLanguage();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState('');
  const [fieldErrors, setFieldErrors] = useState<{ identifier?: string; password?: string }>({});
  const [verificationResult, setVerificationResult] = useState<"success" | "failed" | null>(null);

  useEffect(() => {
    const result = new URLSearchParams(window.location.search).get("verification");
    if (result === "success" || result === "failed") {
      setVerificationResult(result);
    }
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    
    const nextFieldErrors = {
      ...(!email.trim() ? { identifier: localize("Vui lòng nhập tên đăng nhập hoặc email.", "Enter your username or email.") } : {}),
      ...(!password.trim() ? { password: localize("Vui lòng nhập mật khẩu.", "Enter your password.") } : {}),
    };
    if (Object.keys(nextFieldErrors).length > 0) {
      setFieldErrors(nextFieldErrors);
      setError('');
      window.requestAnimationFrame(() => document.getElementById(nextFieldErrors.identifier ? "login-email" : "login-password")?.focus());
      return;
    }

    setFieldErrors({});
    setError('');
    setIsLoading(true);

    try {
      const res = await apiClient.post("/auth/login", {
        username: email.trim(),
        password: password
      });

      const { accessToken } = res.data;
      if (!accessToken) {
        throw new Error("Phản hồi đăng nhập không hợp lệ");
      }
      authSession.setAccessToken(accessToken);

      const profile = await authSession.getCurrentUser<CurrentUserProfile>(false);
      const username = profile?.username || email.trim();
      const role = String(profile?.type || "CUSTOMER").replace("ROLE_", "").toUpperCase();
      const fullName = profile?.fullName?.trim() || username;

      localStorage.setItem("user", JSON.stringify({
        fullName: fullName,
        username: username,
        role: role
      }));

      // Chuyển hướng dựa trên quyền hạn
      if (role === "ADMIN" || role === "STAFF") {
        router.push("/dashboard");
      } else {
        router.push("/");
      }

    } catch (err: unknown) {
      console.error(err);
      if (getApiErrorStatus(err) === 401) {
        setError("Tên đăng nhập hoặc mật khẩu không chính xác.");
      } else {
        setError(getApiErrorMessage(err, "Đã xảy ra lỗi khi đăng nhập. Vui lòng thử lại."));
      }
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <>
      {/* Title */}
      <div className="mb-5">
        <div className="mb-3 flex items-center gap-3">
          <span className="h-px w-9 bg-[#B8944F]" aria-hidden="true" />
          <p className="text-[11px] font-bold uppercase tracking-[0.22em] text-[#80632F]">Luxury Hotel</p>
        </div>
        <h2 className="font-serif text-3xl font-semibold leading-tight tracking-tight text-[#0F2A43] sm:text-[2.35rem]">{localize("Chào mừng bạn trở lại", FORM_TITLE_LOGIN)}</h2>
        <p className="mt-2 max-w-md text-sm font-medium leading-6 text-text-light">{localize("Đăng nhập để tiếp tục quản lý kỳ nghỉ của bạn.", FORM_SUBTITLE_LOGIN)}</p>
      </div>

      {/* Error */}
      {verificationResult === "success" && (
        <div className="mb-4 rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-700" role="status" aria-live="polite">
          {localize("Xác thực email thành công. Tài khoản của bạn đã được kích hoạt.", "Email verified successfully. Your account is now active.")}
        </div>
      )}
      {verificationResult === "failed" && (
        <div className="mb-4 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-600" role="alert" aria-live="assertive">
          {localize("Liên kết xác thực không hợp lệ hoặc đã được sử dụng. Vui lòng gửi lại email xác thực.", "The verification link is invalid or has already been used. Please request a new verification email.")}
        </div>
      )}
      {error && (
        <div className="mb-4 p-3 bg-red-50 border border-red-200 text-red-600 rounded-lg text-sm" role="alert" aria-live="assertive">
          {error}
        </div>
      )}

      {/* Form */}
      <form noValidate onSubmit={handleSubmit} className="space-y-4">
        <div>
          <label htmlFor="login-email" className="mb-2 block text-sm font-semibold text-text-dark">
            {localize("Tên đăng nhập hoặc email", LABEL_LOGIN_IDENTIFIER)}
          </label>
          <div className="relative">
            <span className="absolute left-4 top-1/2 -translate-y-1/2 text-text-light">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <rect x="2" y="4" width="20" height="16" rx="2"/>
                <path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/>
              </svg>
            </span>
            <input
              id="login-email"
              type="text"
              autoComplete="username"
              autoFocus
              value={email}
              onChange={(e) => {
                setEmail(e.target.value);
                if (fieldErrors.identifier) setFieldErrors((current) => ({ ...current, identifier: undefined }));
              }}
              aria-invalid={Boolean(fieldErrors.identifier)}
              aria-describedby={fieldErrors.identifier ? "login-email-error" : undefined}
              placeholder={localize("Nhập tên đăng nhập hoặc email", PLACEHOLDER_EMAIL)}
              className="min-h-12 w-full rounded-xl border border-[#0F2A43]/14 bg-[#F7F5EF] py-2.5 pl-12 pr-4 text-sm shadow-[inset_0_1px_0_rgba(255,255,255,0.7)] transition hover:border-[#0F2A43]/24 focus:border-accent-gold focus:bg-white focus:outline-none focus:ring-2 focus:ring-accent-gold/25"
            />
          </div>
          {fieldErrors.identifier && <p id="login-email-error" className="mt-2 text-xs font-semibold text-rose-700" role="alert">{fieldErrors.identifier}</p>}
        </div>

        <div>
          <div className="flex items-center justify-between mb-2">
            <label htmlFor="login-password" className="block text-sm font-semibold text-text-dark">
              {localize("Mật khẩu", LABEL_PASSWORD)}
            </label>
            <Link href="/forgot-password" className="text-xs font-semibold text-[#80632F] hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]/60">
              {localize("Quên mật khẩu?", "Forgot password?")}
            </Link>
          </div>
          <div className="relative">
            <span className="absolute left-4 top-1/2 -translate-y-1/2 text-text-light">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
                <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
              </svg>
            </span>
            <input
              id="login-password"
              type={showPassword ? 'text' : 'password'}
              autoComplete="current-password"
              value={password}
              onChange={(e) => {
                setPassword(e.target.value);
                if (fieldErrors.password) setFieldErrors((current) => ({ ...current, password: undefined }));
              }}
              aria-invalid={Boolean(fieldErrors.password)}
              aria-describedby={fieldErrors.password ? "login-password-error" : undefined}
              placeholder={PLACEHOLDER_PASSWORD}
              className="min-h-12 w-full rounded-xl border border-[#0F2A43]/14 bg-[#F7F5EF] py-2.5 pl-12 pr-12 text-sm shadow-[inset_0_1px_0_rgba(255,255,255,0.7)] transition hover:border-[#0F2A43]/24 focus:border-accent-gold focus:bg-white focus:outline-none focus:ring-2 focus:ring-accent-gold/25"
            />
            <button
              type="button"
              onClick={() => setShowPassword(!showPassword)}
              className="absolute right-1 top-1/2 flex h-11 w-11 -translate-y-1/2 items-center justify-center rounded-full text-text-light transition-colors hover:bg-[#0F2A43]/6 hover:text-text-dark"
              aria-label={showPassword ? localize('Ẩn mật khẩu', 'Hide password') : localize('Hiện mật khẩu', 'Show password')}
            >
              {showPassword ? (
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/>
                  <path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/>
                  <line x1="1" y1="1" x2="23" y2="23"/>
                </svg>
              ) : (
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                  <circle cx="12" cy="12" r="3"/>
                </svg>
              )}
            </button>
          </div>
          {fieldErrors.password && <p id="login-password-error" className="mt-2 text-xs font-semibold text-rose-700" role="alert">{fieldErrors.password}</p>}
        </div>

        <button
          type="submit"
          disabled={isLoading}
          className="flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-[#0F2A43] px-4 py-3 font-semibold text-white shadow-[0_12px_28px_rgba(15,42,67,0.18)] transition hover:-translate-y-0.5 hover:bg-[#091E30] active:translate-y-px disabled:cursor-not-allowed disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2"
        >
          {isLoading ? (
            <>
              <span aria-hidden="true" className="h-4 w-4 animate-spin rounded-full border-2 border-white border-r-transparent" />
              {localize("Đang đăng nhập...", BTN_SIGNING)}
            </>
          ) : (
            <>
                  {localize("Đăng nhập", BTN_SIGNIN)}
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <path d="M5 12h14"/>
                <path d="m12 5 7 7-7 7"/>
              </svg>
            </>
          )}
        </button>
      </form>

      <SocialAuthOptions mode="login" />

      <p className="mt-3 text-center text-sm text-text-light">
        {localize("Tài khoản chưa được xác thực?", "Account not verified?")}{" "}
        <Link href="/resend-verification" className="font-semibold text-[#80632F] hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]/60">
          {localize("Gửi lại email xác thực", "Resend verification email")}
        </Link>
      </p>

      <p className="mt-5 text-center text-sm text-text-light">
        {localize("Bạn chưa có tài khoản? ", TEXT_SIGNUP_PROMPT)}
        <Link href="/signup" className="text-accent-gold font-semibold hover:underline">
          {localize("Tạo tài khoản", LINK_SIGNUP)}
        </Link>
      </p>
    </>
  );
}
