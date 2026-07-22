"use client";

import { useCallback, useEffect, useState } from "react";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { publicApiClient } from "@/lib/api";

type OAuthProvider = "google" | "facebook";

interface SocialAuthOptionsProps {
  mode: "login" | "signup";
}

const OAUTH_BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || "http://localhost:8080";
const SUPPORTED_PROVIDERS: OAuthProvider[] = ["google", "facebook"];

const isOAuthProvider = (value: unknown): value is OAuthProvider =>
  typeof value === "string" && SUPPORTED_PROVIDERS.includes(value.toLowerCase() as OAuthProvider);

const buildAuthorizationUrl = (provider: OAuthProvider) => {
  const backendUrl = new URL(OAUTH_BACKEND_URL);
  return new URL(`/auth/oauth/authorize/${encodeURIComponent(provider)}`, backendUrl).toString();
};

function ProviderIcon({ provider }: { provider: OAuthProvider }) {
  if (provider === "google") {
    return (
      <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5">
        <path fill="#4285F4" d="M21.6 12.2c0-.7-.1-1.4-.2-2.1H12v4h5.4a4.6 4.6 0 0 1-2 3v2.6h3.3c1.9-1.8 2.9-4.4 2.9-7.5Z" />
        <path fill="#34A853" d="M12 22c2.7 0 5-.9 6.7-2.3l-3.3-2.6c-.9.6-2.1 1-3.4 1-2.6 0-4.8-1.8-5.6-4.1H3v2.7A10.1 10.1 0 0 0 12 22Z" />
        <path fill="#FBBC05" d="M6.4 14a6 6 0 0 1 0-3.9V7.4H3a10 10 0 0 0 0 9.3L6.4 14Z" />
        <path fill="#EA4335" d="M12 6c1.5 0 2.8.5 3.9 1.5l2.9-2.9A9.7 9.7 0 0 0 12 2a10.1 10.1 0 0 0-9 5.4l3.4 2.7A6 6 0 0 1 12 6Z" />
      </svg>
    );
  }

  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5 text-[#1877F2]">
      <path fill="currentColor" d="M22 12a10 10 0 1 0-11.6 9.9v-7H7.9V12h2.5V9.8c0-2.5 1.5-3.9 3.8-3.9 1.1 0 2.3.2 2.3.2v2.5h-1.3c-1.2 0-1.6.8-1.6 1.6V12h2.8l-.4 2.9h-2.4v7A10 10 0 0 0 22 12Z" />
    </svg>
  );
}

export default function SocialAuthOptions({ mode }: SocialAuthOptionsProps) {
  const { localize } = useLanguage();
  const [providers, setProviders] = useState<OAuthProvider[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [activeProvider, setActiveProvider] = useState<OAuthProvider | null>(null);

  const loadProviders = useCallback(async () => {
    setIsLoading(true);
    setLoadError(false);
    try {
      const response = await publicApiClient.get("/auth/oauth/providers");
      const configuredProviders = Array.isArray(response.data?.data)
        ? response.data.data
            .filter(isOAuthProvider)
            .map((provider: string) => provider.toLowerCase() as OAuthProvider)
        : [];
      setProviders(Array.from(new Set(configuredProviders)));
    } catch {
      setProviders([]);
      setLoadError(true);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadProviders();
  }, [loadProviders]);

  const startAuthorization = (provider: OAuthProvider) => {
    setActiveProvider(provider);
    setLoadError(false);
    try {
      window.location.assign(buildAuthorizationUrl(provider));
    } catch {
      setActiveProvider(null);
      setLoadError(true);
    }
  };

  return (
    <section className="mt-5" aria-label={localize("Đăng nhập bằng mạng xã hội", "Social authentication")}>
      <div className="flex items-center gap-3" aria-hidden="true">
        <span className="h-px flex-1 bg-[#0F2A43]/10" />
        <span className="text-xs font-semibold text-[#66727C]">
          {localize("hoặc tiếp tục với", "or continue with")}
        </span>
        <span className="h-px flex-1 bg-[#0F2A43]/10" />
      </div>

      {isLoading ? (
        <div className="mt-3 grid grid-cols-2 gap-3" role="status" aria-live="polite">
          <span className="sr-only">{localize("Đang kiểm tra phương thức đăng nhập...", "Checking sign-in options...")}</span>
          <div className="h-12 animate-pulse rounded-xl bg-[#E5E9ED]" />
          <div className="h-12 animate-pulse rounded-xl bg-[#E5E9ED]" />
        </div>
      ) : (
        <>
        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          {SUPPORTED_PROVIDERS.map((provider) => {
            const providerName = provider === "google" ? "Google" : "Facebook";
            const isActive = activeProvider === provider;
            const isConfigured = !loadError && providers.includes(provider);
            return (
              <button
                key={provider}
                type="button"
                onClick={() => startAuthorization(provider)}
                disabled={activeProvider !== null || !isConfigured}
                aria-describedby={!isConfigured ? "oauth-provider-status" : undefined}
                className="flex min-h-12 items-center justify-center gap-3 rounded-xl border border-[#0F2A43]/14 bg-white px-4 text-sm font-semibold text-[#0F2A43] shadow-sm transition hover:-translate-y-0.5 hover:border-[#B8944F] hover:bg-[#FBFAF6] active:translate-y-px disabled:cursor-not-allowed disabled:bg-[#F1F0EA] disabled:text-[#66727C] disabled:opacity-80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2"
              >
                <ProviderIcon provider={provider} />
                <span>
                  {isActive
                    ? localize("Đang chuyển hướng...", "Redirecting...")
                    : !isConfigured
                      ? localize(`${providerName} tạm chưa sẵn sàng`, `${providerName} is not available`)
                    : mode === "login"
                      ? localize(`Đăng nhập với ${providerName}`, `Sign in with ${providerName}`)
                      : localize(`Đăng ký với ${providerName}`, `Sign up with ${providerName}`)}
                </span>
              </button>
            );
          })}
        </div>
        {(loadError || providers.length < SUPPORTED_PROVIDERS.length) && (
          <div id="oauth-provider-status" className="mt-3 flex items-start justify-between gap-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2.5 text-xs font-medium leading-5 text-amber-900" role="status">
            <span>{loadError
              ? localize("Không thể kiểm tra cấu hình đăng nhập nhanh. Bạn có thể thử tải lại hoặc dùng mật khẩu.", "Quick sign-in configuration could not be checked. Retry or use your password.")
              : localize("Phương thức bị mờ chưa được khách sạn cấu hình OAuth.", "Dimmed providers have not been configured by the hotel.")}</span>
            <button type="button" onClick={() => void loadProviders()} className="min-h-8 shrink-0 font-bold text-[#80632F] underline underline-offset-4 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">
              {localize("Tải lại", "Retry")}
            </button>
          </div>
        )}
        </>
      )}
    </section>
  );
}
