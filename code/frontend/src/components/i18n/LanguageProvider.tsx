"use client";

import React, { createContext, useCallback, useContext, useEffect, useMemo } from "react";
import { useRouter } from "next/navigation";
import { NextIntlClientProvider, useTranslations } from "next-intl";
import type { AbstractIntlMessages } from "next-intl";
import {
  defaultLocale,
  getLocaleTag,
  isAppLocale,
  localeCookieName,
  type AppLocale,
} from "@/i18n/config";

export type Locale = AppLocale;

const legacyMessagePaths = {
  home: "navigation.home", facilities: "navigation.facilities", rooms: "navigation.rooms", about: "navigation.about", reservation: "navigation.reservation", bookings: "navigation.bookings", favorites: "navigation.favorites",
  login: "navigation.login", signup: "navigation.signup", logout: "navigation.logout", greeting: "navigation.greeting",
  overview: "navigation.overview", users: "navigation.users", guests: "navigation.guests", roomTypes: "navigation.roomTypes", settings: "navigation.settings",
  operations: "navigation.operations", management: "navigation.management",
  quickLinks: "common.quickLinks", privacy: "common.privacy", terms: "common.terms", follow: "common.follow", copyright: "common.copyright",
  viewDetails: "common.viewDetails", bookNow: "common.bookNow", perHour: "common.perHour", capacity: "common.capacity", guestsUnit: "common.guestsUnit",
  noDescription: "common.noDescription", noData: "common.noData",
  vietnamese: "language.vietnamese", english: "language.english", language: "language.label",
} as const;

type MessageKey = keyof typeof legacyMessagePaths;
type LanguageContextValue = {
  locale: Locale;
  localeTag: "vi-VN" | "en-US";
  setLocale: (locale: Locale) => void;
  t: (key: MessageKey) => string;
  localize: (vi?: string | null, en?: string | null) => string;
};

const LanguageContext = createContext<LanguageContextValue | null>(null);

function CompatibilityLanguageProvider({ children, locale }: { children: React.ReactNode; locale: Locale }) {
  const router = useRouter();
  const translate = useTranslations();

  useEffect(() => {
    document.documentElement.lang = locale;
    if (document.cookie.split("; ").some((item) => item.startsWith(`${localeCookieName}=`))) return;
    const legacyLocale = localStorage.getItem(localeCookieName);
    if (!isAppLocale(legacyLocale) || legacyLocale === locale) return;
    document.cookie = `${localeCookieName}=${legacyLocale}; Path=/; Max-Age=31536000; SameSite=Lax`;
    router.refresh();
  }, [locale, router]);

  const setLocale = useCallback((nextLocale: Locale) => {
    if (!isAppLocale(nextLocale) || nextLocale === locale) return;
    document.cookie = `${localeCookieName}=${nextLocale}; Path=/; Max-Age=31536000; SameSite=Lax`;
    localStorage.setItem(localeCookieName, nextLocale);
    document.documentElement.lang = nextLocale;
    router.refresh();
  }, [locale, router]);

  const localize = useCallback((vi?: string | null, en?: string | null) => {
    const primary = locale === "vi" ? vi : en;
    const fallback = locale === "vi" ? en : vi;
    return primary?.trim() || fallback?.trim() || "";
  }, [locale]);

  const value = useMemo<LanguageContextValue>(() => ({
    locale,
    localeTag: getLocaleTag(locale),
    setLocale,
    t: (key) => translate(legacyMessagePaths[key]),
    localize,
  }), [locale, localize, setLocale, translate]);

  return <LanguageContext.Provider value={value}>{children}</LanguageContext.Provider>;
}

export function LanguageProvider({
  children,
  initialLocale,
  messages,
}: {
  children: React.ReactNode;
  initialLocale?: string;
  messages: AbstractIntlMessages;
}) {
  const locale = isAppLocale(initialLocale) ? initialLocale : defaultLocale;
  return (
    <NextIntlClientProvider locale={locale} messages={messages} timeZone="Asia/Ho_Chi_Minh">
      <CompatibilityLanguageProvider locale={locale}>{children}</CompatibilityLanguageProvider>
    </NextIntlClientProvider>
  );
}

export function useLanguage() {
  const context = useContext(LanguageContext);
  if (!context) throw new Error("useLanguage must be used inside LanguageProvider");
  return context;
}

export function LanguageSwitcher({ compact = false }: { compact?: boolean }) {
  const translate = useTranslations("language");
  const { locale, setLocale } = useLanguage();
  return <div className="inline-flex rounded-lg border border-current/20 bg-current/5 p-0.5" aria-label={translate("select")}>
    {(["vi", "en"] as const).map((item) => <button key={item} type="button" onClick={() => setLocale(item)} aria-label={item === "vi" ? translate("switchToVietnamese") : translate("switchToEnglish")} aria-pressed={locale === item} className={`rounded-md px-2 py-1 text-[10px] font-bold uppercase tracking-wider transition ${locale === item ? "bg-[#B8944F] text-[#0F2A43]" : "text-current opacity-60 hover:opacity-100"}`}>{compact ? item : item === "vi" ? "VI" : "EN"}</button>)}
  </div>;
}
