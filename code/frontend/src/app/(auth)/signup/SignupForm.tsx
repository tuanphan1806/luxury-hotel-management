"use client";

import React, { useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { apiClient, getApiErrorMessage } from '@/lib/api';
import SocialAuthOptions from '@/components/auth/SocialAuthOptions';
import { useLanguage } from '@/components/i18n/LanguageProvider';
import {
  LABEL_FULL_NAME,
  PLACEHOLDER_FULL_NAME,
  LABEL_EMAIL,
  PLACEHOLDER_EMAIL,
  LABEL_PASSWORD,
  PLACEHOLDER_PASSWORD,
  LABEL_CONFIRM_PASSWORD,
  BTN_CONTINUE,
  BTN_BACK,
  BTN_REGISTER,
  BTN_REGISTERING,
  TEXT_LOGIN_PROMPT,
  LINK_LOGIN,
  ERROR_INVALID_FULL_NAME,
  ERROR_PASSWORD_MATCH,
  ERROR_PASSWORD_SPACE,
  ERROR_PASSWORD_STRICT,
  LABEL_USERNAME,
  PLACEHOLDER_USERNAME,
} from '@/constants/auth';

/* ────── SVG Icons ────── */
const UserIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
    <circle cx="12" cy="7" r="4" />
  </svg>
);
const MailIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <rect x="2" y="4" width="20" height="16" rx="2" />
    <path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7" />
  </svg>
);
const PhoneIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z" />
  </svg>
);
const LockIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
    <path d="M7 11V7a5 5 0 0 1 10 0v4" />
  </svg>
);
const EyeIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
    <circle cx="12" cy="12" r="3" />
  </svg>
);
const EyeOffIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94" />
    <path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19" />
    <line x1="1" y1="1" x2="23" y2="23" />
  </svg>
);
const ArrowIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
    <path d="M5 12h14" />
    <path d="m12 5 7 7-7 7" />
  </svg>
);
const ArrowLeftIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
    <path d="M19 12H5" />
    <path d="m12 19-7-7 7-7" />
  </svg>
);

/* ────── Input wrapper ────── */
function FormInput({
  id, label, required, icon, children, error,
}: {
  id: string; label: string; required?: boolean; icon: React.ReactNode; children: React.ReactNode; error?: string;
}) {
  return (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-sm font-semibold text-text-dark">
        {label}{required && <span className="text-accent-gold ml-0.5">*</span>}
      </label>
      <div className="relative">
        <span className="absolute left-4 top-1/2 -translate-y-1/2 text-text-light">{icon}</span>
        {children}
      </div>
      {error && <p id={`${id}-error`} className="mt-2 text-xs font-semibold text-rose-700" role="alert">{error}</p>}
    </div>
  );
}

type SignupField = "fullName" | "username" | "email" | "phone" | "password" | "confirmPassword";
type SignupFieldErrors = Partial<Record<SignupField, string>>;

const inputClass =
  'min-h-12 w-full rounded-xl border border-[#0F2A43]/14 bg-[#F7F5EF] py-2.5 pl-12 pr-4 text-sm shadow-[inset_0_1px_0_rgba(255,255,255,0.7)] transition hover:border-[#0F2A43]/24 focus:border-accent-gold focus:bg-white focus:outline-none focus:ring-2 focus:ring-accent-gold/25';

export default function SignupForm() {
  const router = useRouter();
  const { localize } = useLanguage();
  const [step, setStep] = useState(1);

  // Step 1 States
  const [fullName, setFullName] = useState('');
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');

  // Step 2 States
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirm, setShowConfirm] = useState(false);

  const [isLoading, setIsLoading] = useState(false);
  const [isResending, setIsResending] = useState(false);
  const [error, setError] = useState('');
  const [fieldErrors, setFieldErrors] = useState<SignupFieldErrors>({});
  const [registeredEmail, setRegisteredEmail] = useState('');
  const [successMessage, setSuccessMessage] = useState('');

  const clearFieldError = (field: SignupField) => {
    setFieldErrors((current) => current[field] ? { ...current, [field]: undefined } : current);
  };

  const focusFirstInvalidField = (errors: SignupFieldErrors) => {
    const firstField = (Object.keys(errors) as SignupField[])[0];
    const ids: Record<SignupField, string> = {
      fullName: "signup-fullname",
      username: "signup-username",
      email: "signup-email",
      phone: "signup-phone",
      password: "signup-password",
      confirmPassword: "signup-confirm",
    };
    if (firstField) window.requestAnimationFrame(() => document.getElementById(ids[firstField])?.focus());
  };

  /* ────── 1. STEP 1 VALIDATION ────── */
  const handleStep1 = (e: React.FormEvent) => {
    e.preventDefault();
    
    const trimmedFullName = fullName.trim();
    const trimmedUsername = username.trim();
    const trimmedEmail = email.trim().toLowerCase();
    const trimmedPhone = phone.trim();

    const nextErrors: SignupFieldErrors = {};
    if (!trimmedFullName) nextErrors.fullName = localize("Vui lòng nhập họ và tên.", "Enter your full name.");
    if (!trimmedUsername) nextErrors.username = localize("Vui lòng nhập tên đăng nhập.", "Enter a username.");
    if (!trimmedEmail) nextErrors.email = localize("Vui lòng nhập email.", "Enter your email.");
    if (!trimmedPhone) nextErrors.phone = localize("Vui lòng nhập số điện thoại.", "Enter your phone number.");

    const nameRegex = /^[a-zA-ZÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠàáâãèéêìíòóôõùúăđĩũơƯĂẠẢẤẦẨẪẬẮẰẲẴẶẸẺẼỀỀỂưăạảấẩẫậắằẳẵặẹẻẽềềểỄỆỈỊỌỎỐỒỔỖỘỚỜỞỠỢỤỦỨỪễệỉịọỏốồổỗộớờởỡợụủứừỬỮỰỲÝỴÝỶỸửữựỳýỵỷỹ\s]{2,50}$/;
    if (trimmedFullName && !nameRegex.test(trimmedFullName)) nextErrors.fullName = localize("Họ và tên cần 2–50 ký tự và chỉ gồm chữ cái.", ERROR_INVALID_FULL_NAME);
    if (trimmedUsername && !/^[A-Za-z0-9._-]{3,30}$/.test(trimmedUsername)) nextErrors.username = localize("Tên đăng nhập cần 3–30 ký tự, chỉ dùng chữ, số, dấu chấm, gạch dưới hoặc gạch ngang.", "Use 3–30 letters, numbers, dots, underscores, or hyphens.");
    const emailFormatRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (trimmedEmail && !emailFormatRegex.test(trimmedEmail)) nextErrors.email = localize("Email không đúng định dạng.", "Enter a valid email address.");
    if (trimmedPhone && !/^\d{9,11}$/.test(trimmedPhone.replace(/[\s.-]/g, ""))) nextErrors.phone = localize("Số điện thoại cần từ 9 đến 11 chữ số.", "Use a phone number with 9 to 11 digits.");

    if (Object.keys(nextErrors).length > 0) {
      setFieldErrors(nextErrors);
      setError('');
      focusFirstInvalidField(nextErrors);
      return;
    }

    setFieldErrors({});
    setError('');
    setStep(2);
  };

  /* ────── 2. STEP 2 VALIDATION & REDIRECT ────── */
  const handleStep2 = async (e: React.FormEvent) => {
    e.preventDefault();
    
    const nextErrors: SignupFieldErrors = {};
    if (!password) nextErrors.password = localize("Vui lòng nhập mật khẩu.", "Enter a password.");
    if (!confirmPassword) nextErrors.confirmPassword = localize("Vui lòng nhập lại mật khẩu.", "Confirm your password.");
    if (password && /\s/.test(password)) nextErrors.password = localize("Mật khẩu không được chứa khoảng trắng.", ERROR_PASSWORD_SPACE);
    const strictPasswordRegex = /^(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])(?=.*[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?])[A-Za-z0-9!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]{8,}$/;
    if (password && !/\s/.test(password) && !strictPasswordRegex.test(password)) nextErrors.password = localize("Mật khẩu cần ít nhất 8 ký tự, gồm chữ hoa, chữ thường, số và ký tự đặc biệt.", ERROR_PASSWORD_STRICT);
    if (password && confirmPassword && password !== confirmPassword) nextErrors.confirmPassword = localize("Mật khẩu xác nhận không khớp.", ERROR_PASSWORD_MATCH);

    if (Object.keys(nextErrors).length > 0) {
      setFieldErrors(nextErrors);
      setError('');
      focusFirstInvalidField(nextErrors);
      return;
    }

    setFieldErrors({});
    setError('');
    setIsLoading(true);
    
    try {
      await apiClient.post("/auth/register", {
        fullName: fullName.trim(),
        username: username.trim(),
        email: email.trim().toLowerCase(),
        phone: phone.trim(),
        password: password,
        address: "Default Address"
      });

      setRegisteredEmail(email.trim().toLowerCase());
      setSuccessMessage(localize("Đăng ký thành công. Vui lòng kiểm tra email để xác thực tài khoản.", "Registration successful. Check your email to verify the account."));
      setStep(3);
    } catch (err: unknown) {
      console.error(err);
      setError(getApiErrorMessage(err, "Đăng ký không thành công. Vui lòng kiểm tra lại."));
    } finally {
      setIsLoading(false);
    }
  };

  const handleResendVerification = async () => {
    if (!registeredEmail) return;
    setError('');
    setSuccessMessage('');
    setIsResending(true);
    try {
      await apiClient.post("/auth/resend-verification", { email: registeredEmail });
      setSuccessMessage("Email xác thực đã được gửi lại.");
    } catch (err: unknown) {
      console.error(err);
      setError(getApiErrorMessage(err, "Không thể gửi lại email xác thực. Vui lòng thử lại sau."));
    } finally {
      setIsResending(false);
    }
  };

  return (
    <>
      {/* ───── STEP 1: Basic Info ───── */}
      {step === 1 && (
        <>
          <div className="mb-5">
            <div className="mb-3 flex items-center justify-between gap-4">
              <div className="flex items-center gap-3"><span className="h-px w-9 bg-[#B8944F]" aria-hidden="true" /><p className="text-[11px] font-bold uppercase tracking-[0.22em] text-[#80632F]">Luxury Hotel</p></div>
              <span className="rounded-full border border-[#0F2A43]/10 bg-[#F1F0EA] px-3 py-1 text-[10px] font-bold uppercase tracking-[0.14em] text-[#66727C]">{localize("Bước 1 / 2", "Step 1 / 2")}</span>
            </div>
            <h1 className="font-serif text-3xl font-semibold leading-tight tracking-tight text-[#0F2A43] sm:text-[2.3rem]">{localize("Tạo tài khoản khách hàng", "Create your guest account")}</h1>
            <p className="mt-2 max-w-lg text-sm font-medium leading-6 text-[#66727C]">{localize("Đặt phòng nhanh hơn và theo dõi toàn bộ kỳ nghỉ trong một nơi.", "Book faster and track your entire stay in one place.")}</p>
            <div className="mt-4 grid grid-cols-2 gap-2" aria-hidden="true"><span className="h-1 rounded-full bg-[#B8944F]" /><span className="h-1 rounded-full bg-[#0F2A43]/10" /></div>
          </div>
          <form onSubmit={handleStep1} className="space-y-4">
            {error && (
              <div className="p-3 bg-red-50 border border-red-200 text-red-600 rounded-lg text-sm font-medium" role="alert" aria-live="assertive">
                {error}
              </div>
            )}

            <div>
              <FormInput id="signup-fullname" label={localize("Họ và tên", LABEL_FULL_NAME)} required icon={<UserIcon />} error={fieldErrors.fullName}>
                <input
                  id="signup-fullname"
                  type="text"
                  autoComplete="name"
                  autoFocus
                  value={fullName}
                  onChange={(e) => { setFullName(e.target.value); clearFieldError("fullName"); }}
                  aria-invalid={Boolean(fieldErrors.fullName)}
                  aria-describedby={fieldErrors.fullName ? "signup-fullname-error" : undefined}
                  placeholder={localize("Nguyễn Minh Anh", PLACEHOLDER_FULL_NAME)}
                  className={inputClass}
                />
              </FormInput>
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <FormInput id="signup-username" label={localize("Tên đăng nhập", LABEL_USERNAME)} required icon={<UserIcon />} error={fieldErrors.username}>
                <input
                  id="signup-username"
                  type="text"
                  autoComplete="username"
                  value={username}
                  onChange={(e) => { setUsername(e.target.value); clearFieldError("username"); }}
                  aria-invalid={Boolean(fieldErrors.username)}
                  aria-describedby={fieldErrors.username ? "signup-username-error" : undefined}
                  placeholder={localize("minhanh", PLACEHOLDER_USERNAME)}
                  className={inputClass}
                />
              </FormInput>

              <FormInput id="signup-email" label={localize("Email", LABEL_EMAIL)} required icon={<MailIcon />} error={fieldErrors.email}>
                <input
                  id="signup-email"
                  type="email"
                  autoComplete="email"
                  value={email}
                  onChange={(e) => { setEmail(e.target.value); clearFieldError("email"); }}
                  aria-invalid={Boolean(fieldErrors.email)}
                  aria-describedby={fieldErrors.email ? "signup-email-error" : undefined}
                  placeholder={localize("ban@example.com", PLACEHOLDER_EMAIL)}
                  className={inputClass}
                />
              </FormInput>
            </div>

            <FormInput id="signup-phone" label={localize("Số điện thoại", "Phone number")} required icon={<PhoneIcon />} error={fieldErrors.phone}>
              <input
                id="signup-phone"
                type="tel" 
                autoComplete="tel"
                value={phone}
                onChange={(e) => { setPhone(e.target.value); clearFieldError("phone"); }}
                aria-invalid={Boolean(fieldErrors.phone)}
                aria-describedby={fieldErrors.phone ? "signup-phone-error" : undefined}
                placeholder={localize("090 000 0000", "Enter your phone number")}
                className={inputClass}
              />
            </FormInput>

            <button
              type="submit"
              className="mt-1 flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-[#0F2A43] px-4 py-3 font-semibold text-white shadow-[0_12px_28px_rgba(15,42,67,0.18)] transition hover:-translate-y-0.5 hover:bg-[#091E30] active:translate-y-px focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2"
            >
              {localize("Tiếp tục", BTN_CONTINUE)}
              <ArrowIcon />
            </button>
          </form>
          <SocialAuthOptions mode="signup" />
        </>
      )}

      {/* ───── STEP 2: Password ───── */}
      {step === 2 && (
        <form onSubmit={handleStep2} className="space-y-4">
          <div className="mb-2">
            <div className="mb-3 flex items-center justify-between gap-4"><div className="flex items-center gap-3"><span className="h-px w-9 bg-[#B8944F]" aria-hidden="true" /><p className="text-[11px] font-bold uppercase tracking-[0.22em] text-[#80632F]">Luxury Hotel</p></div><span className="rounded-full border border-[#0F2A43]/10 bg-[#F1F0EA] px-3 py-1 text-[10px] font-bold uppercase tracking-[0.14em] text-[#66727C]">{localize("Bước 2 / 2", "Step 2 / 2")}</span></div>
            <h2 className="font-serif text-3xl font-semibold leading-tight text-[#0F2A43]">{localize("Thiết lập mật khẩu", "Set your password")}</h2>
            <p className="mt-2 text-sm font-medium leading-6 text-text-light">{localize("Chỉ còn một bước. Hãy tạo mật khẩu an toàn cho tài khoản.", "Almost done. Create a secure password for your account.")}</p>
            <div className="mt-4 grid grid-cols-2 gap-2" aria-hidden="true"><span className="h-1 rounded-full bg-[#B8944F]" /><span className="h-1 rounded-full bg-[#B8944F]" /></div>
          </div>

          {error && (
            <div className="p-3 bg-red-50 border border-red-200 text-red-600 rounded-lg text-sm font-medium" role="alert" aria-live="assertive">
              {error}
            </div>
          )}

          <FormInput id="signup-password" label={localize("Mật khẩu", LABEL_PASSWORD)} required icon={<LockIcon />} error={fieldErrors.password}>
            <input
              id="signup-password"
              type={showPassword ? 'text' : 'password'}
              autoComplete="new-password"
              autoFocus
              value={password}
              onChange={(e) => { setPassword(e.target.value); clearFieldError("password"); }}
              aria-invalid={Boolean(fieldErrors.password)}
              aria-describedby={fieldErrors.password ? "signup-password-error" : undefined}
              placeholder={PLACEHOLDER_PASSWORD}
              className={`${inputClass} !pr-12`}
            />
            <button
              type="button"
              onClick={() => setShowPassword(!showPassword)}
              className="absolute right-1 top-1/2 flex h-11 w-11 -translate-y-1/2 items-center justify-center rounded-full text-text-light transition-colors hover:bg-[#0F2A43]/6 hover:text-text-dark"
              aria-label={showPassword ? localize("Ẩn mật khẩu", "Hide password") : localize("Hiện mật khẩu", "Show password")}
            >
              {showPassword ? <EyeOffIcon /> : <EyeIcon />}
            </button>
          </FormInput>

          <FormInput id="signup-confirm" label={localize("Xác nhận mật khẩu", LABEL_CONFIRM_PASSWORD)} required icon={<LockIcon />} error={fieldErrors.confirmPassword}>
            <input
              id="signup-confirm"
              type={showConfirm ? 'text' : 'password'}
              autoComplete="new-password"
              value={confirmPassword}
              onChange={(e) => { setConfirmPassword(e.target.value); clearFieldError("confirmPassword"); }}
              aria-invalid={Boolean(fieldErrors.confirmPassword)}
              aria-describedby={fieldErrors.confirmPassword ? "signup-confirm-error" : undefined}
              placeholder={PLACEHOLDER_PASSWORD}
              className={`${inputClass} !pr-12`}
            />
            <button
              type="button"
              onClick={() => setShowConfirm(!showConfirm)}
              className="absolute right-1 top-1/2 flex h-11 w-11 -translate-y-1/2 items-center justify-center rounded-full text-text-light transition-colors hover:bg-[#0F2A43]/6 hover:text-text-dark"
              aria-label={showConfirm ? localize("Ẩn mật khẩu xác nhận", "Hide confirmation password") : localize("Hiện mật khẩu xác nhận", "Show confirmation password")}
            >
              {showConfirm ? <EyeOffIcon /> : <EyeIcon />}
            </button>
          </FormInput>

          <p className="text-xs leading-relaxed text-text-light">
            {localize("Khi tạo tài khoản, bạn đồng ý tuân thủ điều khoản sử dụng và chính sách bảo mật của khách sạn.", "By creating an account, you agree to the hotel's terms of service and privacy policy.")}
          </p>

          <div className="flex gap-3">
            <button
              type="button"
              onClick={() => { setStep(1); setError(''); setFieldErrors({}); }}
              className="flex min-h-12 flex-1 items-center justify-center gap-2 rounded-xl border border-[#0F2A43]/14 px-4 py-3 font-semibold text-text-dark transition hover:border-[#B8944F] hover:bg-white"
            >
              <ArrowLeftIcon />
              {localize("Quay lại", BTN_BACK)}
            </button>
            <button
              type="submit"
              disabled={isLoading}
              className="flex min-h-12 flex-1 items-center justify-center gap-2 rounded-xl bg-[#0F2A43] px-4 py-3 font-semibold text-white shadow-[0_12px_28px_rgba(15,42,67,0.18)] transition hover:-translate-y-0.5 hover:bg-[#091E30] disabled:cursor-not-allowed disabled:opacity-60"
            >
              {isLoading ? (
                <>
                  <span aria-hidden="true" className="h-4 w-4 animate-spin rounded-full border-2 border-white border-r-transparent" />
                  {localize("Đang tạo tài khoản...", BTN_REGISTERING)}
                </>
              ) : (
                <>
                  {localize("Tạo tài khoản", BTN_REGISTER)}
                  <ArrowIcon />
                </>
              )}
            </button>
          </div>
        </form>
      )}

      {step === 3 && (
        <div className="space-y-5">
          <div className="rounded-2xl border border-accent-gold/25 bg-accent-gold/5 p-5 text-center">
            <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-accent-gold text-white">
              <MailIcon />
            </div>
            <h2 className="text-2xl font-bold text-text-dark">Kiểm tra email của bạn</h2>
            <p className="mt-2 text-sm font-medium text-text-light">
              Chúng tôi đã gửi link xác thực đến <span className="font-semibold text-text-dark">{registeredEmail}</span>.
            </p>
          </div>

          {successMessage && (
            <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm font-medium text-emerald-700" role="status" aria-live="polite">
              {successMessage}
            </div>
          )}
          {error && (
            <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm font-medium text-red-600" role="alert" aria-live="assertive">
              {error}
            </div>
          )}

          <div className="grid gap-3 sm:grid-cols-2">
            <button
              type="button"
              onClick={handleResendVerification}
              disabled={isResending}
              className="rounded-xl border border-accent-gold px-4 py-3.5 text-sm font-semibold text-accent-gold transition hover:bg-accent-gold/5 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {isResending ? <span className="inline-flex items-center justify-center gap-2"><span aria-hidden="true" className="h-4 w-4 animate-spin rounded-full border-2 border-current border-r-transparent" />Đang gửi lại...</span> : "Gửi lại email xác thực"}
            </button>
            <button
              type="button"
              onClick={() => router.push('/login?registered=true')}
              className="rounded-xl bg-accent-gold px-4 py-3.5 text-sm font-semibold text-white shadow-md transition hover:bg-[#c9a45e]"
            >
              Đi đến đăng nhập
            </button>
          </div>
        </div>
      )}

      {/* Login link */}
      <p className="mt-5 text-center text-sm text-text-light">
        {localize("Bạn đã có tài khoản? ", TEXT_LOGIN_PROMPT)}
        <Link href="/login" className="text-accent-gold font-semibold hover:underline">
          {localize("Đăng nhập", LINK_LOGIN)}
        </Link>
      </p>
    </>
  );
}
