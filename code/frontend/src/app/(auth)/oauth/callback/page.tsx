"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { authSession, publicApiClient } from "@/lib/api";

type AccountRole = "CUSTOMER" | "STAFF" | "ADMIN";
type SafeOAuthErrorCode =
  | "account_conflict"
  | "missing_email"
  | "unverified_email"
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

  useEffect(() => {
    if (hasStarted.current) return;
    hasStarted.current = true;

    const finishAuthentication = async () => {
      const status = searchParams.get("status");
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

  const errorMessage = (() => {
    switch (errorCode) {
      case "account_conflict":
        return localize("Email này đã được liên kết với một cách đăng nhập khác. Hãy đăng nhập bằng phương thức cũ hoặc liên hệ khách sạn.", "This email is linked to another sign-in method. Use your existing method or contact the hotel.");
      case "missing_email":
        return localize("Tài khoản mạng xã hội chưa cung cấp email. Hãy cấp quyền email hoặc chọn một cách đăng nhập khác.", "Your social account did not provide an email. Grant email access or choose another sign-in method.");
      case "unverified_email":
        return localize("Nhà cung cấp chưa xác minh email của bạn. Hãy xác minh email tại nhà cung cấp rồi thử lại.", "Your provider has not verified this email. Verify it with the provider, then try again.");
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
              <Link href="/" className="inline-flex min-h-12 flex-1 items-center justify-center rounded-xl border border-[#0F2A43]/15 px-5 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F1F0EA] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2">
                {localize("Về trang chủ", "Go to homepage")}
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
