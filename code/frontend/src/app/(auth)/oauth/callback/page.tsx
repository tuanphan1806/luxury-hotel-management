"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { authSession, getApiErrorMessage, getApiErrorStatus, publicApiClient } from "@/lib/api";

type AccountRole = "CUSTOMER" | "STAFF" | "ADMIN";
type SafeOAuthErrorCode =
  | "account_conflict"
  | "missing_email"
  | "unverified_email"
  | "email_verification_required"
  | "account_disabled"
  | "provider_error"
  | "oauth_not_configured";
type OAuthDisplayError = SafeOAuthErrorCode | "invalid_callback" | "session_failed";

interface RefreshTokenResponse {
  accessToken?: string;
  data?: {
    accessToken?: string;
  };
}

interface OAuthUserProfile {
  fullName?: string;
  username?: string;
  imageUrl?: string;
  type?: string;
  role?: string;
}

const SAFE_ERROR_CODES = new Set<SafeOAuthErrorCode>([
  "account_conflict",
  "missing_email",
  "unverified_email",
  "email_verification_required",
  "account_disabled",
  "provider_error",
  "oauth_not_configured",
]);

const normalizeRole = (value?: string): AccountRole => {
  const role = String(value || "CUSTOMER").replace("ROLE_", "").toUpperCase();
  return role === "ADMIN" || role === "STAFF" ? role : "CUSTOMER";
};

const toDisplayError = (value: string | null): OAuthDisplayError =>
  value && SAFE_ERROR_CODES.has(value as SafeOAuthErrorCode)
    ? value as SafeOAuthErrorCode
    : "invalid_callback";

function OAuthCallbackContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { localize } = useLanguage();
  const hasStarted = useRef(false);
  const [isLoading, setIsLoading] = useState(true);
  const [errorCode, setErrorCode] = useState<OAuthDisplayError | null>(null);
  const [profileTicket, setProfileTicket] = useState<string | null>(null);
  const [email, setEmail] = useState("");
  const [profileError, setProfileError] = useState("");
  const [isCompletingProfile, setIsCompletingProfile] = useState(false);
  const [verificationSent, setVerificationSent] = useState(false);

  useEffect(() => {
    if (hasStarted.current) return;
    hasStarted.current = true;

    const finishAuthentication = async () => {
      const status = searchParams.get("status");
      if (status === "profile_required") {
        const ticket = searchParams.get("ticket")?.trim();
        window.history.replaceState({}, "", window.location.pathname);
        if (!ticket) {
          setErrorCode("invalid_callback");
        } else {
          setProfileTicket(ticket);
        }
        setIsLoading(false);
        return;
      }
      if (status !== "success") {
        setErrorCode(toDisplayError(searchParams.get("error")));
        setIsLoading(false);
        return;
      }

      try {
        // The URL carries only a short-lived, one-time exchange code. Remove it
        // before any further navigation, then exchange through the same-origin
        // Next.js proxy so the HttpOnly refresh cookie belongs to the frontend
        // origin rather than becoming a third-party Render cookie.
        const ticket = searchParams.get("ticket")?.trim();
        if (!ticket) throw new Error("Missing OAuth exchange ticket");
        window.history.replaceState({}, "", window.location.pathname);

        const exchangeResponse = await publicApiClient.post<RefreshTokenResponse>(
          "/auth/oauth/exchange",
          { ticket },
        );
        const accessToken = exchangeResponse.data?.accessToken || exchangeResponse.data?.data?.accessToken;
        if (!accessToken) throw new Error("Missing access token in refresh response");

        authSession.setAccessToken(accessToken);
        const profile = await authSession.getCurrentUser<OAuthUserProfile>(false);
        if (!profile) throw new Error("Authenticated profile could not be loaded");

        const role = normalizeRole(profile.type || profile.role);
        const username = profile.username?.trim() || "customer";
        localStorage.setItem("user", JSON.stringify({
          fullName: profile.fullName?.trim() || username,
          username,
          imageUrl: profile.imageUrl || "",
          type: role,
          role,
        }));

        router.replace(role === "ADMIN" || role === "STAFF" ? "/dashboard" : "/");
        router.refresh();
      } catch {
        authSession.clear();
        setErrorCode("session_failed");
        setIsLoading(false);
      }
    };

    void finishAuthentication();
  }, [router, searchParams]);

  const handleProfileCompletion = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!profileTicket || isCompletingProfile) return;

    const normalizedEmail = email.trim().toLowerCase();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalizedEmail)) {
      setProfileError(localize("Vui lòng nhập địa chỉ email hợp lệ.", "Enter a valid email address."));
      return;
    }

    setProfileError("");
    setIsCompletingProfile(true);
    try {
      await publicApiClient.post("/auth/oauth/complete-profile", {
        ticket: profileTicket,
        email: normalizedEmail,
      });
      setProfileTicket(null);
      setVerificationSent(true);
    } catch (error: unknown) {
      const status = getApiErrorStatus(error);
      if (status === 409) {
        setProfileError(localize(
          "Email này đã thuộc một tài khoản khác. Hãy đăng nhập bằng phương thức đã liên kết với tài khoản đó.",
          "This email belongs to another account. Sign in using the method already linked to that account.",
        ));
      } else if (status === 400) {
        setProfileError(localize(
          "Phiên bổ sung email đã hết hạn. Vui lòng quay lại và chọn Facebook một lần nữa.",
          "This email-completion session expired. Go back and choose Facebook again.",
        ));
      } else {
        setProfileError(getApiErrorMessage(error, localize(
          "Chưa thể lưu email lúc này. Vui lòng thử lại.",
          "We could not save your email. Please try again.",
        )));
      }
    } finally {
      setIsCompletingProfile(false);
    }
  };

  const errorMessage = (() => {
    switch (errorCode) {
      case "account_conflict":
        return localize("Email này đã được liên kết với một cách đăng nhập khác. Hãy đăng nhập bằng phương thức cũ hoặc liên hệ khách sạn.", "This email is linked to another sign-in method. Use your existing method or contact the hotel.");
      case "missing_email":
        return localize("Tài khoản mạng xã hội chưa cung cấp email. Hãy cấp quyền email hoặc chọn một cách đăng nhập khác.", "Your social account did not provide an email. Grant email access or choose another sign-in method.");
      case "unverified_email":
        return localize("Nhà cung cấp chưa xác minh email của bạn. Hãy xác minh email tại nhà cung cấp rồi thử lại.", "Your provider has not verified this email. Verify it with the provider, then try again.");
      case "email_verification_required":
        return localize("Email bổ sung chưa được xác minh. Hãy mở liên kết trong email rồi đăng nhập Facebook lại.", "Your additional email has not been verified. Open the email link, then sign in with Facebook again.");
      case "account_disabled":
        return localize("Tài khoản khách sạn đang bị vô hiệu hóa. Vui lòng liên hệ lễ tân để được hỗ trợ.", "Your hotel account is disabled. Please contact the front desk for help.");
      case "provider_error":
        return localize("Nhà cung cấp đăng nhập tạm thời không phản hồi. Vui lòng quay lại và thử lại.", "The sign-in provider is temporarily unavailable. Go back and try again.");
      case "oauth_not_configured":
        return localize("Phương thức đăng nhập này chưa được khách sạn cấu hình. Vui lòng dùng tài khoản và mật khẩu.", "This sign-in provider is not configured. Please use your username and password.");
      case "session_failed":
        return localize("Đăng nhập đã hoàn tất nhưng không thể khôi phục phiên an toàn. Vui lòng đăng nhập lại.", "Sign-in completed, but a secure session could not be restored. Please sign in again.");
      default:
        return localize("Liên kết đăng nhập không hợp lệ hoặc đã hết hạn. Vui lòng bắt đầu lại từ trang đăng nhập.", "The sign-in link is invalid or expired. Please start again from the sign-in page.");
    }
  })();

  return (
    <main className="flex min-h-screen w-full items-center justify-center bg-[#F1F0EA] px-5 py-16">
      <section className="w-full max-w-lg rounded-[1.5rem] border border-[#0F2A43]/10 bg-white p-7 shadow-[0_24px_70px_rgba(20,39,74,0.12)] sm:p-10" aria-labelledby="oauth-callback-title">
        <Link href="/" className="inline-flex rounded-lg text-xs font-bold uppercase tracking-[0.18em] text-[#80632F] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-4">
          Luxury Hotel
        </Link>

        {isLoading ? (
          <div className="py-10 text-center" role="status" aria-live="polite">
            <span className="mx-auto block h-11 w-11 animate-spin rounded-full border-4 border-[#0F2A43]/15 border-t-[#B8944F]" aria-hidden="true" />
            <h1 id="oauth-callback-title" className="mt-6 text-2xl font-bold text-[#0F2A43]">
              {localize("Đang hoàn tất đăng nhập", "Completing sign-in")}
            </h1>
            <p className="mt-3 text-sm font-medium leading-6 text-[#66727C]">
              {localize("Hệ thống đang xác minh phiên với khách sạn. Vui lòng không đóng trang này.", "We are verifying your session with the hotel. Please keep this page open.")}
            </p>
          </div>
        ) : verificationSent ? (
          <div className="py-6" role="status" aria-live="polite">
            <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-emerald-50 text-2xl font-bold text-emerald-700" aria-hidden="true">✓</div>
            <h1 id="oauth-callback-title" className="mt-6 text-2xl font-bold text-[#0F2A43]">
              {localize("Kiểm tra email của bạn", "Check your email")}
            </h1>
            <p className="mt-3 text-sm font-medium leading-7 text-[#66727C]">
              {localize(
                "Luxury Hotel đã gửi liên kết xác minh. Mở liên kết đó; hệ thống sẽ đưa bạn trở lại Luxury Hotel và hoàn tất đăng nhập tự động.",
                "Luxury Hotel sent a verification link. Open it and you will return to Luxury Hotel to finish signing in automatically.",
              )}
            </p>
            <div className="mt-7 flex flex-col gap-3 sm:flex-row">
              <Link href="/login" className="inline-flex min-h-12 flex-1 items-center justify-center rounded-xl bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#091E30] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2">
                {localize("Về trang đăng nhập", "Back to sign in")}
              </Link>
              <Link href="/resend-verification" className="inline-flex min-h-12 flex-1 items-center justify-center rounded-xl border border-[#0F2A43]/15 px-5 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F1F0EA] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2">
                {localize("Gửi lại email", "Resend email")}
              </Link>
            </div>
          </div>
        ) : profileTicket ? (
          <div className="py-6">
            <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-[#B8944F]/12 text-xl font-bold text-[#80632F]" aria-hidden="true">@</div>
            <h1 id="oauth-callback-title" className="mt-6 text-2xl font-bold text-[#0F2A43]">
              {localize("Bổ sung email liên hệ", "Add your contact email")}
            </h1>
            <p className="mt-3 text-sm font-medium leading-7 text-[#66727C]">
              {localize(
                "Facebook đã xác thực bạn nhưng không cung cấp email cho website. Nhập email bạn đang sử dụng để nhận xác nhận đặt phòng và khôi phục tài khoản.",
                "Facebook authenticated you but did not share an email with the website. Enter an email you use for booking confirmations and account recovery.",
              )}
            </p>
            <form className="mt-7 space-y-5" onSubmit={handleProfileCompletion} noValidate>
              <div>
                <label htmlFor="oauth-contact-email" className="block text-sm font-bold text-[#0F2A43]">
                  {localize("Email liên hệ", "Contact email")}
                </label>
                <input
                  id="oauth-contact-email"
                  type="email"
                  inputMode="email"
                  autoComplete="email"
                  autoFocus
                  required
                  maxLength={255}
                  value={email}
                  onChange={(event) => {
                    setEmail(event.target.value);
                    setProfileError("");
                  }}
                  placeholder="name@example.com"
                  aria-describedby="oauth-email-help"
                  aria-invalid={Boolean(profileError)}
                  className="mt-2 min-h-12 w-full rounded-xl border border-[#0F2A43]/20 bg-white px-4 text-sm font-medium text-[#0F2A43] outline-none transition placeholder:text-[#66727C]/65 focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
                />
                <p id="oauth-email-help" className="mt-2 text-xs leading-5 text-[#66727C]">
                  {localize("Bạn cần mở liên kết được gửi tới email này trước khi tài khoản được kích hoạt.", "You must open the link sent to this address before the account is activated.")}
                </p>
              </div>
              {profileError && (
                <p className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-semibold leading-6 text-rose-700" role="alert">
                  {profileError}
                </p>
              )}
              <button
                type="submit"
                disabled={isCompletingProfile || !email.trim()}
                className="inline-flex min-h-12 w-full items-center justify-center rounded-xl bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#091E30] disabled:cursor-not-allowed disabled:opacity-55 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2"
              >
                {isCompletingProfile
                  ? localize("Đang gửi xác minh...", "Sending verification...")
                  : localize("Tiếp tục và xác minh email", "Continue and verify email")}
              </button>
              <Link href="/login" className="inline-flex min-h-11 w-full items-center justify-center rounded-xl text-sm font-bold text-[#80632F] hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">
                {localize("Dùng phương thức đăng nhập khác", "Use another sign-in method")}
              </Link>
            </form>
          </div>
        ) : (
          <div className="py-6">
            <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-rose-50 text-2xl font-bold text-rose-700" aria-hidden="true">!</div>
            <h1 id="oauth-callback-title" className="mt-6 text-2xl font-bold text-[#0F2A43]">
              {localize("Không thể đăng nhập", "Sign-in could not be completed")}
            </h1>
            <p className="mt-3 text-sm font-medium leading-7 text-[#66727C]" role="alert" aria-live="assertive">
              {errorMessage}
            </p>
            <div className="mt-7 flex flex-col gap-3 sm:flex-row">
              <Link href="/login" className="inline-flex min-h-12 flex-1 items-center justify-center rounded-xl bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#091E30] active:translate-y-px focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2">
                {localize("Quay lại đăng nhập", "Back to sign in")}
              </Link>
              <Link href={errorCode === "email_verification_required" ? "/resend-verification" : "/"} className="inline-flex min-h-12 flex-1 items-center justify-center rounded-xl border border-[#0F2A43]/15 px-5 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F1F0EA] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2">
                {errorCode === "email_verification_required"
                  ? localize("Gửi lại email", "Resend email")
                  : localize("Về trang chủ", "Go to homepage")}
              </Link>
            </div>
          </div>
        )}
      </section>
    </main>
  );
}

function OAuthCallbackFallback() {
  return <main className="min-h-screen w-full bg-[#F1F0EA]" aria-busy="true" />;
}

export default function OAuthCallbackPage() {
  return (
    <Suspense fallback={<OAuthCallbackFallback />}>
      <OAuthCallbackContent />
    </Suspense>
  );
}
