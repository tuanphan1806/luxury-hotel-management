"use client";

import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
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

const messageLoaders: Record<Locale, () => Promise<{ default: AbstractIntlMessages }>> = {
  vi: () => import("@/i18n/messages/vi.json"),
  en: () => import("@/i18n/messages/en.json"),
};

function CompatibilityLanguageProvider({
  children,
  locale,
  setLocale,
}: {
  children: React.ReactNode;
  locale: Locale;
  setLocale: (locale: Locale) => void;
}) {
  const translate = useTranslations();

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
  const initial = isAppLocale(initialLocale) ? initialLocale : defaultLocale;
  const [locale, setActiveLocale] = useState<Locale>(initial);
  const [activeMessages, setActiveMessages] = useState<AbstractIntlMessages>(messages);
  const localeRequestId = useRef(0);

  const applyLocale = useCallback((nextLocale: Locale) => {
    if (!isAppLocale(nextLocale) || nextLocale === locale) return;
    const requestId = ++localeRequestId.current;

    void messageLoaders[nextLocale]().then((module) => {
      if (requestId !== localeRequestId.current) return;
      document.cookie = `${localeCookieName}=${nextLocale}; Path=/; Max-Age=31536000; SameSite=Lax`;
      localStorage.setItem(localeCookieName, nextLocale);
      document.documentElement.lang = nextLocale;
      setActiveMessages(module.default);
      setActiveLocale(nextLocale);
    });
  }, [locale]);

  useEffect(() => {
    document.documentElement.lang = locale;
    const storedLocale = localStorage.getItem(localeCookieName);
    const cookieLocale = document.cookie
      .split("; ")
      .find((item) => item.startsWith(`${localeCookieName}=`))
      ?.split("=")[1];
    const preferredLocale = isAppLocale(storedLocale)
      ? storedLocale
      : isAppLocale(cookieLocale)
        ? cookieLocale
        : defaultLocale;
    if (preferredLocale !== locale) applyLocale(preferredLocale);
  }, [applyLocale, locale]);

  return (
    <NextIntlClientProvider key={locale} locale={locale} messages={activeMessages} timeZone="Asia/Ho_Chi_Minh">
      <CompatibilityLanguageProvider locale={locale} setLocale={applyLocale}>{children}</CompatibilityLanguageProvider>
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
    {(["vi", "en"] as const).map((item) => <button key={item} type="button" onClick={() => setLocale(item)} aria-label={item === "vi" ? translate("switchToVietnamese") : translate("switchToEnglish")} aria-pressed={locale === item} className={`rounded-md px-2 py-1 text-[10px] font-bold uppercase tracking-wider transition ${locale === item ? "bg-[#B8944F] text-[#0F2A43]" : "text-current opacity-85 hover:opacity-100"}`}>{compact ? item : item === "vi" ? "VI" : "EN"}</button>)}
  </div>;
}
