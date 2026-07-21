"use client";

import React, { useCallback, useEffect, useRef, useState } from "react";
import dynamic from "next/dynamic";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { LanguageSwitcher, useLanguage } from "@/components/i18n/LanguageProvider";
import FavoriteRoomsMenu from "@/components/favorites/FavoriteRoomsMenu";
import { useFavorites } from "@/components/favorites/FavoritesProvider";
import HotelBrand from "@/components/HotelBrand";
import { apiClient, authSession } from "@/lib/api";
import { scheduleIdleTask, shouldConserveData } from "@/lib/performance";
import { siteConfig } from "@/lib/siteConfig";

const ChatWidget = dynamic(() => import("@/components/ChatWidget"), {
  ssr: false,
  loading: () => null,
});

type MainAccountRole = "CUSTOMER" | "STAFF" | "ADMIN";

interface MainAccount {
  fullName: string;
  role: MainAccountRole;
  username?: string;
  email?: string;
  phone?: string;
}

interface CurrentUserProfile {
  fullName?: string;
  username?: string;
  email?: string;
  phone?: string;
  imageUrl?: string;
  type?: string;
}

const normalizeRole = (value?: string): MainAccountRole => {
  const role = String(value || "CUSTOMER").replace("ROLE_", "").toUpperCase();
  return role === "ADMIN" || role === "STAFF" ? role : "CUSTOMER";
};

export default function MainSiteShell({ children }: Readonly<{ children: React.ReactNode }>) {
  const pathname = usePathname();
  const router = useRouter();
  const { t, localize, locale, setLocale } = useLanguage();
  const { favoriteCount } = useFavorites();
  const [mounted, setMounted] = useState(false);
  const [user, setUser] = useState<MainAccount | null>(null);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [accountMenuOpen, setAccountMenuOpen] = useState(false);
  const [languageMenuOpen, setLanguageMenuOpen] = useState(false);
  const [favoriteMenuOpen, setFavoriteMenuOpen] = useState(false);
  const menuButtonRef = useRef<HTMLButtonElement>(null);
  const drawerRef = useRef<HTMLDivElement>(null);
  const accountMenuRef = useRef<HTMLDivElement>(null);
  const languageMenuRef = useRef<HTMLDivElement>(null);
  const favoriteMenuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setMounted(true);
    const loadCachedUser = () => {
      const storedUser = localStorage.getItem("user");
      if (!storedUser) {
        setUser(null);
        return;
      }

      try {
        const parsed = JSON.parse(storedUser);
        setUser({
          fullName: parsed.fullName || parsed.username || "Customer",
          role: normalizeRole(parsed.role || parsed.type),
          username: parsed.username,
          email: parsed.email,
          phone: parsed.phone,
        });
      } catch {
        setUser(null);
      }
    };

    const syncCurrentUser = async () => {
      const profile = await authSession.getCurrentUser<CurrentUserProfile>(false);
      if (!profile) {
        setUser(null);
        return;
      }

      try {
        localStorage.setItem(
          "user",
          JSON.stringify({
            fullName: profile.fullName,
            username: profile.username,
            email: profile.email,
            phone: profile.phone,
            imageUrl: profile.imageUrl,
            type: normalizeRole(profile.type),
            role: normalizeRole(profile.type),
          }),
        );
        loadCachedUser();
      } catch {
        setUser(null);
      }
    };

    loadCachedUser();
    void syncCurrentUser();
    window.addEventListener("storage", loadCachedUser);
    return () => window.removeEventListener("storage", loadCachedUser);
  }, []);

  useEffect(() => {
    setMobileMenuOpen(false);
    setAccountMenuOpen(false);
    setLanguageMenuOpen(false);
    setFavoriteMenuOpen(false);
  }, [pathname]);

  useEffect(() => {
    if (!accountMenuOpen && !languageMenuOpen && !favoriteMenuOpen) return;

    const closeUtilityMenus = (event: PointerEvent) => {
      const target = event.target as Node;
      if (accountMenuOpen && !accountMenuRef.current?.contains(target)) setAccountMenuOpen(false);
      if (languageMenuOpen && !languageMenuRef.current?.contains(target)) setLanguageMenuOpen(false);
      if (favoriteMenuOpen && !favoriteMenuRef.current?.contains(target)) setFavoriteMenuOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setAccountMenuOpen(false);
      setLanguageMenuOpen(false);
      setFavoriteMenuOpen(false);
    };

    document.addEventListener("pointerdown", closeUtilityMenus);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("pointerdown", closeUtilityMenus);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [accountMenuOpen, favoriteMenuOpen, languageMenuOpen]);

  useEffect(() => {
    if (pathname === "/") return;

    const frame = window.requestAnimationFrame(() => {
      if (window.location.hash) return;
      const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      window.scrollTo({ top: 0, behavior: reduceMotion ? "auto" : "smooth" });
      document.getElementById("main-content")?.focus({ preventScroll: true });
    });

    return () => window.cancelAnimationFrame(frame);
  }, [pathname]);

  useEffect(() => {
    const warmGuestRoutes = () => {
      if (shouldConserveData()) return;
      ["/facilities", "/rooms", "/about", "/reservation", "/support", "/contact"].forEach((href) => {
        router.prefetch(href);
      });
    };
    return scheduleIdleTask(warmGuestRoutes);
  }, [router]);

  useEffect(() => {
    const desktopNavigation = window.matchMedia("(min-width: 1280px)");
    const closeMenuOnDesktop = (event: MediaQueryListEvent) => {
      if (event.matches) setMobileMenuOpen(false);
    };

    desktopNavigation.addEventListener("change", closeMenuOnDesktop);
    return () => desktopNavigation.removeEventListener("change", closeMenuOnDesktop);
  }, []);

  useEffect(() => {
    if (!mobileMenuOpen) return;

    const previousOverflow = document.body.style.overflow;
    const menuButton = menuButtonRef.current;
    const focusFrame = window.requestAnimationFrame(() => {
      drawerRef.current?.querySelector<HTMLElement>("[data-drawer-focus]")?.focus();
    });

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        setMobileMenuOpen(false);
        return;
      }

      if (event.key !== "Tab" || !drawerRef.current) return;
      const focusableElements = Array.from(
        drawerRef.current.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ),
      ).filter((element) => element.offsetParent !== null);

      if (!focusableElements.length) return;
      const firstElement = focusableElements[0];
      const lastElement = focusableElements[focusableElements.length - 1];

      if (event.shiftKey && document.activeElement === firstElement) {
        event.preventDefault();
        lastElement.focus();
      } else if (!event.shiftKey && document.activeElement === lastElement) {
        event.preventDefault();
        firstElement.focus();
      }
    };

    document.body.style.overflow = "hidden";
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      window.cancelAnimationFrame(focusFrame);
      document.body.style.overflow = previousOverflow;
      document.removeEventListener("keydown", handleKeyDown);
      menuButton?.focus({ preventScroll: true });
    };
  }, [mobileMenuOpen]);

  const closeMobileMenu = useCallback(() => setMobileMenuOpen(false), []);

  const handleLogout = async () => {
    closeMobileMenu();
    setAccountMenuOpen(false);
    try {
      await apiClient.post("/auth/logout");
    } catch (error) {
      console.warn("Logout request failed, clearing local session", error);
    }
    authSession.clear();
    setUser(null);
    window.location.href = "/";
  };

  const isActiveRoute = (href: string) =>
    href === "/" ? pathname === "/" : pathname === href || pathname.startsWith(`${href}/`);

  const navigationItems = [
    { href: "/", label: t("home") },
    { href: "/facilities", label: t("facilities") },
    { href: "/rooms", label: t("rooms") },
    { href: "/about", label: t("about") },
    { href: "/reservation", label: t("reservation") },
  ];

  const accountHref = user?.role === "ADMIN" || user?.role === "STAFF" ? "/dashboard" : "/account";
  const accountLabel = user?.role === "CUSTOMER"
    ? localize("Thông tin tài khoản", "Account settings")
    : localize("Bảng điều khiển", "Dashboard");
  const bookingHistoryHref = user?.role === "ADMIN" || user?.role === "STAFF" ? "/dashboard/reservations" : "/my-bookings";
  const settingsHref = user?.role === "ADMIN" || user?.role === "STAFF" ? "/dashboard/settings" : "/account/settings";
  const accountRoleLabel = user?.role === "ADMIN"
    ? localize("Quản trị viên", "Administrator")
    : user?.role === "STAFF"
      ? localize("Nhân viên", "Staff")
      : localize("Khách hàng", "Guest");

  const desktopLinkClass = (href: string) => {
    const active = isActiveRoute(href);
    return `relative flex min-h-11 items-center px-1 text-sm font-semibold transition-colors after:absolute after:inset-x-1 after:bottom-0 after:h-px after:origin-left after:bg-[#B8944F] after:transition-transform ${
      active
        ? "text-[#0F2A43] after:scale-x-100"
        : "text-[#66727C] after:scale-x-0 hover:text-[#0F2A43] hover:after:scale-x-100"
    }`;
  };

  const showMobileBookingBar = !pathname.startsWith("/reservation") && !pathname.startsWith("/booking");

  return (
    <div className="guest-site home-color-story flex min-h-screen flex-col">
      <a
        href="#main-content"
        className="fixed left-4 top-3 z-[90] -translate-y-24 rounded-lg bg-[#FBFAF6] px-4 py-3 text-sm font-bold text-[#0F2A43] shadow-lg transition-transform focus:translate-y-0 focus:outline-none focus:ring-2 focus:ring-[#B8944F]"
      >
        {localize("Bỏ qua điều hướng", "Skip to content")}
      </a>

      <header className="guest-navigation fixed inset-x-0 top-0 z-50 border-b border-[#0F2A43]/14 text-[#0F2A43] shadow-[0_10px_32px_rgba(15,42,67,0.10)]">
        <div className="mx-auto flex h-20 w-full max-w-[1400px] items-center justify-between gap-4 px-4 sm:px-6 lg:px-8">
          <Link
            href="/"
            aria-label={localize(`${siteConfig.name} — về trang chủ`, `${siteConfig.name} — home`)}
            className="group flex min-h-12 min-w-0 items-center gap-2.5 rounded-lg focus:outline-none focus:ring-2 focus:ring-[#B8944F] focus:ring-offset-4 focus:ring-offset-[#DCE3E8] sm:gap-3"
          >
            <HotelBrand
              descriptor="DIRECT BOOKING"
              priority
              className="gap-2.5 sm:gap-3"
              markClassName="h-11 w-11 transition-transform duration-300 group-hover:-translate-y-0.5 sm:h-12 sm:w-12"
              wordmarkClassName="text-base min-[380px]:text-lg"
            />
          </Link>

          <div className="hidden min-w-0 flex-1 items-center justify-end gap-2.5 xl:flex">
            <nav aria-label={localize("Điều hướng chính", "Primary navigation")} className="flex items-center gap-3">
              {navigationItems.map((item) => (
                <Link key={item.href} href={item.href} aria-current={isActiveRoute(item.href) ? "page" : undefined} className={desktopLinkClass(item.href)}>
                  {item.label}
                </Link>
              ))}
            </nav>

            <span aria-hidden="true" className="mx-1 h-6 w-px bg-[#0F2A43]/12" />
            <div ref={favoriteMenuRef} className="relative">
              <button
                type="button"
                aria-expanded={favoriteMenuOpen}
                aria-controls="favorite-rooms-menu"
                aria-label={localize(`Mở ${favoriteCount} hạng phòng yêu thích`, `Open ${favoriteCount} favorite room types`)}
                onClick={() => {
                  setFavoriteMenuOpen((open) => !open);
                  setAccountMenuOpen(false);
                  setLanguageMenuOpen(false);
                }}
                className={`relative inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-full border transition focus:outline-none focus:ring-2 focus:ring-[#B8944F] ${favoriteMenuOpen ? "border-[#0F2A43] bg-[#0F2A43] text-white" : "border-[#0F2A43]/24 bg-[#FBFAF6] text-[#80632F] hover:border-[#B8944F] hover:bg-[#F0EADF]"}`}
              >
                <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill={favoriteCount ? "currentColor" : "none"} stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1.1L12 21l7.8-7.5 1.1-1.1a5.5 5.5 0 0 0-.1-7.8Z" />
                </svg>
                {favoriteCount > 0 && <span className="absolute -right-1 -top-1 flex h-5 min-w-5 items-center justify-center rounded-full bg-rose-600 px-1 text-[10px] font-bold tabular-nums text-white">{favoriteCount > 9 ? "9+" : favoriteCount}</span>}
              </button>
              <FavoriteRoomsMenu open={favoriteMenuOpen} onClose={() => setFavoriteMenuOpen(false)} />
            </div>

            {mounted && user ? (
              <div ref={accountMenuRef} className="relative">
                <button
                  type="button"
                  aria-expanded={accountMenuOpen}
                  aria-controls="guest-account-menu"
                  onClick={() => {
                    setAccountMenuOpen((open) => !open);
                    setLanguageMenuOpen(false);
                    setFavoriteMenuOpen(false);
                  }}
                  className="flex min-h-12 max-w-56 items-center gap-2.5 rounded-full border border-[#0F2A43]/18 bg-[#FBFAF6] py-1.5 pl-1.5 pr-3 text-left transition hover:border-[#B8944F] focus:outline-none focus:ring-2 focus:ring-[#B8944F]"
                >
                  <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-[#0F2A43] font-serif text-sm font-bold uppercase text-[#F1F0EA]">{user.fullName.trim().charAt(0) || "U"}</span>
                  <span className="min-w-0 flex-1 leading-tight">
                    <span className="block truncate text-xs font-bold text-[#0F2A43]">{user.fullName}</span>
                    <span className="mt-1 block truncate text-[9px] font-bold uppercase tracking-[0.16em] text-[#80632F]">{accountRoleLabel}</span>
                  </span>
                  <svg aria-hidden="true" viewBox="0 0 20 20" className={`h-4 w-4 shrink-0 text-[#66727C] transition ${accountMenuOpen ? "rotate-180" : ""}`} fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="m6 8 4 4 4-4" /></svg>
                </button>

                {accountMenuOpen && (
                  <div id="guest-account-menu" className="absolute right-0 top-full z-[80] mt-3 w-[19.5rem] overflow-hidden rounded-[1.25rem] border border-[#0F2A43]/14 bg-[#FBFAF6] shadow-[0_24px_70px_rgba(15,42,67,0.22)]">
                    <div className="relative overflow-hidden bg-[#0F2A43] px-5 py-5 text-white">
                      <div aria-hidden="true" className="absolute -right-8 -top-10 h-32 w-32 rounded-full border border-[#B8944F]/24" />
                      <div className="relative flex items-center gap-3">
                        <span className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full border border-[#B8944F]/70 font-serif text-lg font-bold uppercase text-[#F1F0EA]">{user.fullName.trim().charAt(0) || "U"}</span>
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-2"><p className="truncate text-sm font-bold">{user.fullName}</p><span className="shrink-0 rounded-md border border-[#B8944F]/45 px-1.5 py-0.5 text-[8px] font-bold uppercase tracking-[0.12em] text-[#D8C398]">{accountRoleLabel}</span></div>
                          <p className="mt-1 truncate text-[11px] text-white/70">{user.email || user.username || accountRoleLabel}</p>
                          {user.phone && <p className="mt-1 truncate text-[11px] text-white/58">{user.phone}</p>}
                        </div>
                      </div>
                    </div>

                    <div className="space-y-2 px-3 py-3">
                      {[
                        { href: accountHref, label: user.role === "CUSTOMER" ? localize("Thông tin cá nhân", "Personal information") : accountLabel, icon: <svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.8"><circle cx="12" cy="8" r="3" /><path d="M5.5 20a6.5 6.5 0 0 1 13 0" /></svg> },
                        { href: bookingHistoryHref, label: user.role === "CUSTOMER" ? localize("Lịch sử đặt phòng", "Booking history") : localize("Quản lý đặt phòng", "Manage bookings"), icon: <svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.8"><rect x="4" y="5" width="16" height="15" rx="2" /><path d="M8 3v4M16 3v4M4 10h16" /></svg> },
                        { href: settingsHref, label: localize("Cài đặt tài khoản", "Account settings"), icon: <svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.8"><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.83 2.83-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 .6 1.7 1.7 0 0 0-.4 1.1V21H9.6v-.09A1.7 1.7 0 0 0 8.5 19.4a1.7 1.7 0 0 0-1.88.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-1.6-1H3v-4h.09A1.7 1.7 0 0 0 4.6 8.5a1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-1.6V3h4v.09A1.7 1.7 0 0 0 15.5 4.6a1.7 1.7 0 0 0 1.88-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.4 9a1.7 1.7 0 0 0 1.6 1h.09v4H21a1.7 1.7 0 0 0-1.6 1Z" /></svg> },
                      ].map((item, index) => (
                        <Link key={item.href} href={item.href} onClick={() => setAccountMenuOpen(false)} className="group flex min-h-12 items-center gap-3 rounded-xl border border-[#0F2A43]/14 bg-white px-3 text-sm font-semibold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F0EADF]">
                          <span className={`flex h-8 w-8 items-center justify-center rounded-full ${index === 0 ? "bg-[#0F2A43] text-white" : "bg-[#EAE2D2] text-[#80632F]"}`}>{item.icon}</span>
                          <span className="flex-1">{item.label}</span><span aria-hidden="true" className="text-[#80632F] transition group-hover:translate-x-0.5">›</span>
                        </Link>
                      ))}
                      <div className="flex min-h-11 items-center justify-between gap-4 px-2 pt-1">
                        <span className="text-sm font-semibold text-[#0F2A43]">{t("language")}</span>
                        <LanguageSwitcher />
                      </div>
                    </div>
                    <button type="button" onClick={handleLogout} className="flex min-h-12 w-full items-center gap-3 border-t border-[#0F2A43]/10 bg-[#F1F0EA] px-6 text-left text-sm font-bold text-rose-700 transition hover:bg-rose-50">
                      <span aria-hidden="true">↪</span>{t("logout")}
                    </button>
                  </div>
                )}
              </div>
            ) : mounted ? (
              <>
                <Link href="/login" className={desktopLinkClass("/login")}>{t("login")}</Link>
                <Link href="/signup" className="inline-flex min-h-11 items-center rounded-lg bg-[#0F2A43] px-4 text-sm font-bold text-white transition hover:bg-[#091E30] focus:outline-none focus:ring-2 focus:ring-[#B8944F]">
                  {t("signup")}
                </Link>
                <div ref={languageMenuRef} className="relative">
                  <button
                    type="button"
                    aria-expanded={languageMenuOpen}
                    aria-controls="guest-language-menu"
                    aria-label={localize("Mở lựa chọn ngôn ngữ", "Open language selector")}
                    onClick={() => {
                      setLanguageMenuOpen((open) => !open);
                      setAccountMenuOpen(false);
                      setFavoriteMenuOpen(false);
                    }}
                    className="inline-flex h-11 items-center gap-2 rounded-lg border border-[#0F2A43]/18 bg-[#FBFAF6] px-3 text-xs font-bold uppercase text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F0EADF] focus:outline-none focus:ring-2 focus:ring-[#B8944F]"
                  >
                    <svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.8"><circle cx="12" cy="12" r="9" /><path d="M3 12h18M12 3a15 15 0 0 1 0 18M12 3a15 15 0 0 0 0 18" /></svg>
                    {locale.toUpperCase()}
                  </button>
                  {languageMenuOpen && (
                    <div id="guest-language-menu" className="absolute right-0 top-full z-[80] mt-3 w-48 rounded-xl border border-[#0F2A43]/12 bg-[#FBFAF6] p-2 shadow-[0_20px_55px_rgba(15,42,67,0.2)]">
                      {(["vi", "en"] as const).map((item) => (
                        <button key={item} type="button" onClick={() => { setLocale(item); setLanguageMenuOpen(false); }} className={`flex min-h-11 w-full items-center justify-between rounded-lg px-3 text-sm font-bold transition ${locale === item ? "bg-[#EAE2D2] text-[#0F2A43]" : "text-[#66727C] hover:bg-[#F0EADF] hover:text-[#0F2A43]"}`}>
                          <span>{item === "vi" ? "Tiếng Việt" : "English"}</span><span className="text-xs uppercase">{item}</span>
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              </>
            ) : (
              <div aria-hidden="true" className="h-10 w-36 animate-pulse rounded-lg bg-[#0F2A43]/7" />
            )}
          </div>

          <div className="flex items-center gap-2 xl:hidden">
            <Link href="/rooms?favorites=1" aria-label={localize(`Xem ${favoriteCount} hạng phòng yêu thích`, `View ${favoriteCount} favorite room types`)} className="relative inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-full border border-[#0F2A43]/20 bg-[#FBFAF6] text-[#80632F] transition hover:border-[#B8944F] hover:bg-[#F0EADF] focus:outline-none focus:ring-2 focus:ring-[#B8944F]">
              <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill={favoriteCount ? "currentColor" : "none"} stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1.1L12 21l7.8-7.5 1.1-1.1a5.5 5.5 0 0 0-.1-7.8Z" /></svg>
              {favoriteCount > 0 && <span className="absolute -right-1 -top-1 flex h-5 min-w-5 items-center justify-center rounded-full bg-rose-600 px-1 text-[10px] font-bold tabular-nums text-white">{favoriteCount > 9 ? "9+" : favoriteCount}</span>}
            </Link>
            <button
              ref={menuButtonRef}
              type="button"
              aria-expanded={mobileMenuOpen}
              aria-controls="main-mobile-navigation"
              aria-label={mobileMenuOpen ? localize("Đóng trình đơn", "Close menu") : localize("Mở trình đơn", "Open menu")}
              onClick={() => setMobileMenuOpen((open) => !open)}
              className="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-lg border border-[#0F2A43]/18 bg-[#F1F0EA] text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F0EADF] focus:outline-none focus:ring-2 focus:ring-[#B8944F] focus:ring-offset-2 focus:ring-offset-[#DCE3E8]"
            >
              <span className="sr-only">{localize("Trình đơn", "Menu")}</span>
              <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><path d="M4 7h16M4 12h16M4 17h16" /></svg>
            </button>
          </div>
        </div>
      </header>

      {mobileMenuOpen && (
        <div className="fixed inset-0 z-[70] xl:hidden">
          <button type="button" aria-label={localize("Đóng trình đơn", "Close menu")} onClick={closeMobileMenu} className="main-mobile-backdrop absolute inset-0 h-full w-full cursor-default bg-[#0F2A43]/64" />
          <div
            ref={drawerRef}
            id="main-mobile-navigation"
            role="dialog"
            aria-modal="true"
            aria-labelledby="mobile-navigation-title"
            className="main-mobile-drawer absolute inset-y-0 right-0 flex h-[100dvh] w-[min(91vw,24rem)] flex-col overflow-y-auto border-l border-[#0F2A43]/14 bg-[#E5E9ED] text-[#0F2A43] shadow-[-24px_0_70px_rgba(15,42,67,0.22)]"
          >
            <div className="flex items-start justify-between border-b border-[#0F2A43]/10 px-5 pb-5 pt-6 sm:px-6">
              <div>
                <p className="text-[0.68rem] font-bold uppercase tracking-[0.23em] text-[#80632F]">{siteConfig.name}</p>
                <h2 id="mobile-navigation-title" className="mt-2 font-serif text-2xl font-semibold text-[#0F2A43]">
                  {localize("Khám phá khách sạn", "Explore the hotel")}
                </h2>
              </div>
              <button
                data-drawer-focus
                type="button"
                onClick={closeMobileMenu}
                aria-label={localize("Đóng trình đơn", "Close menu")}
                className="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-lg border border-[#0F2A43]/12 bg-white text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#EAE2D2] focus:outline-none focus:ring-2 focus:ring-[#B8944F]"
              >
                <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
                  <path d="m6 6 12 12M18 6 6 18" />
                </svg>
              </button>
            </div>

            <nav aria-label={localize("Điều hướng di động", "Mobile navigation")} className="grid gap-1 px-4 py-5 sm:px-5">
              {navigationItems.map((item, index) => {
                const active = isActiveRoute(item.href);
                return (
                  <Link
                    key={item.href}
                    href={item.href}
                    onClick={closeMobileMenu}
                    aria-current={active ? "page" : undefined}
                    className={`group grid min-h-[3.35rem] grid-cols-[2rem_1fr_auto] items-center gap-3 rounded-xl border px-3.5 py-2.5 transition focus:outline-none focus:ring-2 focus:ring-[#B8944F] ${
                      active
                        ? "border-[#B8944F]/50 bg-[#EAE2D2] text-[#0F2A43]"
                        : "border-transparent text-[#66727C] hover:border-[#0F2A43]/8 hover:bg-white hover:text-[#0F2A43]"
                    }`}
                  >
                    <span className="text-[0.65rem] font-bold tabular-nums tracking-[0.16em] text-[#80632F]">{String(index + 1).padStart(2, "0")}</span>
                    <span className="text-sm font-bold">{item.label}</span>
                    <svg aria-hidden="true" viewBox="0 0 20 20" className={`h-4 w-4 transition-transform ${active ? "translate-x-0 text-[#80632F]" : "-translate-x-1 text-[#66727C] group-hover:translate-x-0"}`} fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                      <path d="M4 10h11M11 6l4 4-4 4" />
                    </svg>
                  </Link>
                );
              })}
            </nav>

            <div className="mt-auto border-t border-[#0F2A43]/10 bg-[#F1F0EA] px-5 py-5 sm:px-6">
              {mounted && user ? (
                <div className="rounded-xl border border-[#0F2A43]/10 bg-[#FBFAF6] p-4">
                  <div className="flex items-center gap-3 border-b border-[#0F2A43]/10 pb-4">
                    <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-[#0F2A43] font-serif font-bold text-white">{user.fullName.trim().charAt(0) || "U"}</span>
                    <div className="min-w-0"><p className="truncate text-sm font-bold text-[#0F2A43]">{user.fullName}</p><p className="mt-1 truncate text-xs text-[#66727C]">{user.email || user.username || accountRoleLabel}</p></div>
                  </div>
                  <div className="mt-3 grid gap-2">
                    <Link href={accountHref} onClick={closeMobileMenu} className="flex min-h-11 items-center justify-between rounded-lg border border-[#0F2A43]/12 bg-white px-3 text-sm font-bold text-[#0F2A43]"><span>{user.role === "CUSTOMER" ? localize("Thông tin cá nhân", "Personal information") : accountLabel}</span><span aria-hidden="true">›</span></Link>
                    <Link href={bookingHistoryHref} onClick={closeMobileMenu} className="flex min-h-11 items-center justify-between rounded-lg border border-[#0F2A43]/12 bg-white px-3 text-sm font-bold text-[#0F2A43]"><span>{user.role === "CUSTOMER" ? localize("Lịch sử đặt phòng", "Booking history") : localize("Quản lý đặt phòng", "Manage bookings")}</span><span aria-hidden="true">›</span></Link>
                    <Link href={settingsHref} onClick={closeMobileMenu} className="flex min-h-11 items-center justify-between rounded-lg border border-[#0F2A43]/12 bg-white px-3 text-sm font-bold text-[#0F2A43]"><span>{localize("Cài đặt tài khoản", "Account settings")}</span><span aria-hidden="true">›</span></Link>
                  </div>
                  <button type="button" onClick={handleLogout} className="mt-3 inline-flex min-h-11 w-full items-center justify-center rounded-lg border border-rose-200 bg-white px-4 text-sm font-bold text-rose-700 transition hover:bg-rose-50 focus:outline-none focus:ring-2 focus:ring-rose-400">
                    {t("logout")}
                  </button>
                </div>
              ) : mounted ? (
                <div className="grid grid-cols-2 gap-3">
                  <Link href="/login" onClick={closeMobileMenu} className="inline-flex min-h-11 items-center justify-center rounded-lg border border-[#0F2A43]/16 bg-white px-4 text-sm font-bold text-[#0F2A43] transition hover:bg-[#E5E9ED] focus:outline-none focus:ring-2 focus:ring-[#B8944F]">
                    {t("login")}
                  </Link>
                  <Link href="/signup" onClick={closeMobileMenu} className="inline-flex min-h-11 items-center justify-center rounded-lg bg-[#0F2A43] px-4 text-sm font-bold text-white transition hover:bg-[#091E30] focus:outline-none focus:ring-2 focus:ring-[#B8944F]">
                    {t("signup")}
                  </Link>
                </div>
              ) : (
                <div aria-hidden="true" className="h-12 animate-pulse rounded-lg bg-[#0F2A43]/7" />
              )}

              <div className="mt-4 grid grid-cols-2 gap-2">
                <Link href="/rooms?favorites=1" onClick={closeMobileMenu} className="flex min-h-11 items-center justify-center gap-2 rounded-lg border border-[#0F2A43]/12 bg-[#FBFAF6] px-3 text-xs font-bold text-[#0F2A43] transition hover:border-[#B8944F]">
                  <svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4 text-[#80632F]" fill={favoriteCount ? "currentColor" : "none"} stroke="currentColor" strokeWidth="1.8"><path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1.1L12 21l7.8-7.5 1.1-1.1a5.5 5.5 0 0 0-.1-7.8Z" /></svg>
                  {t("favorites")} <span className="tabular-nums text-[#80632F]">{favoriteCount}</span>
                </Link>
                <Link href="/support" onClick={closeMobileMenu} className="flex min-h-11 items-center justify-center rounded-lg border border-[#0F2A43]/12 bg-[#FBFAF6] px-3 text-xs font-bold text-[#0F2A43] transition hover:border-[#B8944F]">
                  {localize("Hỗ trợ", "Support")}
                </Link>
              </div>

              <div className="mt-5 flex min-h-11 items-center justify-between gap-4 border-t border-[#0F2A43]/10 pt-4">
                <span className="text-sm font-semibold text-[#66727C]">{t("language")}</span>
                <LanguageSwitcher />
              </div>
            </div>
          </div>
        </div>
      )}

      <main id="main-content" tabIndex={-1} className={`home-color-story min-h-screen flex-1 focus:outline-none ${showMobileBookingBar ? "pb-20 lg:pb-0" : ""}`}>
        {children}
      </main>

      {showMobileBookingBar && (
        <div className="guest-navigation mobile-booking-safe-area fixed inset-x-0 bottom-0 z-40 border-t border-[#0F2A43]/14 px-4 pt-3 shadow-[0_-14px_36px_rgba(15,42,67,0.13)] lg:hidden">
          <div className="mx-auto flex max-w-lg items-center gap-3">
            <div className="min-w-0 flex-1">
              <p className="truncate text-xs font-bold text-[#0F2A43]">{localize("Sẵn sàng cho kỳ lưu trú?", "Ready for your stay?")}</p>
              <p className="truncate text-[11px] text-[#66727C]">{localize("Xem giá và số phòng còn trống", "See live rates and availability")}</p>
            </div>
            <Link href="/reservation" className="inline-flex min-h-11 shrink-0 items-center justify-center rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#091E30]">
              {localize("Tìm phòng", "Find a room")}
            </Link>
          </div>
        </div>
      )}

      <div className={mobileMenuOpen ? "hidden" : ""}>
        <ChatWidget />
      </div>

      <footer className="guest-footer relative mt-auto border-t border-white/8 px-6 pb-28 pt-14 text-white md:px-10 lg:pb-10">
        <div className="mx-auto max-w-[1400px]">
          <div className="grid gap-7 border-b border-white/12 pb-10 lg:grid-cols-[1fr_auto] lg:items-end">
            <div>
              <p className="text-xs font-bold uppercase tracking-[0.24em] text-[#B8944F]">{localize("Đặt phòng trực tiếp", "Book direct")}</p>
              <h2 className="mt-3 max-w-3xl font-serif text-3xl font-semibold leading-tight text-[#FBFAF6] md:text-4xl">
                {localize("Chọn đúng loại phòng, theo dõi thanh toán rõ ràng và luôn biết bước tiếp theo.", "Choose the right room, track every payment, and always know what comes next.")}
              </h2>
            </div>
            <div className="flex flex-col gap-3 sm:flex-row">
              <Link href="/reservation" className="inline-flex min-h-12 items-center justify-center rounded-lg bg-[#FBFAF6] px-6 text-sm font-bold text-[#0F2A43] transition hover:bg-white">
                {localize("Kiểm tra phòng trống", "Check availability")}
              </Link>
              <Link href="/booking/lookup" className="inline-flex min-h-12 items-center justify-center rounded-lg border border-white/24 px-6 text-sm font-bold text-white transition hover:border-[#B8944F] hover:bg-white/6">
                {localize("Tra cứu đơn", "Find a booking")}
              </Link>
            </div>
          </div>

          <div className="grid gap-10 py-10 sm:grid-cols-2 lg:grid-cols-[1.3fr_0.8fr_0.8fr_1fr]">
            <div className="max-w-sm space-y-4">
              <HotelBrand tone="inverse" descriptor="DIRECT BOOKING" markClassName="h-14 w-14" wordmarkClassName="text-2xl font-semibold" />
              <p className="text-sm leading-7 text-white/64">
                {localize("Nền tảng đặt phòng trực tiếp của khách sạn, kết nối từ tìm phòng, SePay VietQR đến nhận phòng và trả phòng.", "The hotel’s direct booking experience, connecting room search and SePay VietQR with check-in and checkout.")}
              </p>
              <div className="flex flex-wrap gap-2 text-xs font-semibold text-white/70">
                <span className="rounded-md border border-white/12 px-2.5 py-1.5">SePay VietQR</span>
                <span className="rounded-md border border-white/12 px-2.5 py-1.5">50% / 100%</span>
                <span className="rounded-md border border-white/12 px-2.5 py-1.5">{localize("Theo dõi trực tuyến", "Online tracking")}</span>
              </div>
            </div>

            <nav aria-label={localize("Khám phá khách sạn", "Explore the hotel")} className="flex flex-col gap-3">
              <h3 className="text-xs font-bold uppercase tracking-[0.18em] text-[#B8944F]">{localize("Khám phá", "Explore")}</h3>
              <Link href="/rooms" className="w-fit text-sm text-white/66 transition hover:text-white">{localize("Hạng phòng", "Room types")}</Link>
              <Link href="/facilities" className="w-fit text-sm text-white/66 transition hover:text-white">{localize("Tiện ích", "Facilities")}</Link>
              <Link href="/about" className="w-fit text-sm text-white/66 transition hover:text-white">{t("about")}</Link>
              <Link href="/#gallery" className="w-fit text-sm text-white/66 transition hover:text-white">{localize("Không gian", "Gallery")}</Link>
              <Link href="/contact" className="w-fit text-sm text-white/66 transition hover:text-white">{localize("Liên hệ", "Contact")}</Link>
            </nav>

            <nav aria-label={localize("Hỗ trợ đặt phòng", "Booking support")} className="flex flex-col gap-3">
              <h3 className="text-xs font-bold uppercase tracking-[0.18em] text-[#B8944F]">{localize("Đặt phòng", "Booking")}</h3>
              <Link href="/reservation" className="w-fit text-sm text-white/66 transition hover:text-white">{localize("Kiểm tra phòng trống", "Check availability")}</Link>
              <Link href="/booking/lookup" className="w-fit text-sm text-white/66 transition hover:text-white">{localize("Tra cứu đơn", "Find a booking")}</Link>
              <Link href="/#booking-guide" className="w-fit text-sm text-white/66 transition hover:text-white">{localize("Hướng dẫn thanh toán", "Payment guide")}</Link>
            </nav>

            <nav aria-label={localize("Thông tin và chính sách", "Information and policies")} className="flex flex-col gap-3">
              <h3 className="text-xs font-bold uppercase tracking-[0.18em] text-[#B8944F]">{localize("Thông tin", "Information")}</h3>
              <Link href="/support" className="w-fit text-sm text-white/66 transition hover:text-white">{localize("Trung tâm hỗ trợ", "Support center")}</Link>
              <Link href="/booking-policy" className="w-fit text-sm text-white/66 transition hover:text-white">{localize("Chính sách đặt phòng", "Booking policy")}</Link>
              <Link href="/cancellation-policy" className="w-fit text-sm text-white/66 transition hover:text-white">{localize("Chính sách hủy lịch", "Cancellation policy")}</Link>
              <Link href="/terms" className="w-fit text-sm text-white/66 transition hover:text-white">{localize("Điều khoản sử dụng", "Terms of use")}</Link>
              <Link href="/privacy" className="w-fit text-sm text-white/66 transition hover:text-white">{localize("Chính sách bảo mật", "Privacy policy")}</Link>
              <Link href="/data-deletion" className="w-fit text-sm text-white/66 transition hover:text-white">{localize("Yêu cầu xóa dữ liệu", "Data deletion")}</Link>
            </nav>
          </div>

          <div className="flex flex-col gap-4 border-t border-white/10 pt-6 text-xs text-white/46 sm:flex-row sm:items-center sm:justify-between">
            <p>© 2026 {siteConfig.name}. {localize("Đã đăng ký bản quyền.", "All rights reserved.")}</p>
            <div className="flex flex-wrap items-center gap-3 text-white/70">
              <span>{t("language")}</span>
              <LanguageSwitcher compact />
              <span className="text-white/46">{localize("Đặt phòng trực tiếp · Đối soát minh bạch", "Direct booking · Transparent payment tracking")}</span>
            </div>
          </div>
        </div>
      </footer>
    </div>
  );
}
