"use client";

import React, { useState, useEffect, useRef } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { apiClient, authSession, cachedGet } from "@/lib/api";
import { LanguageSwitcher, useLanguage } from "@/components/i18n/LanguageProvider";
import HotelBrand from "@/components/HotelBrand";
import { scheduleIdleTask, shouldConserveData } from "@/lib/performance";
import { siteConfig } from "@/lib/siteConfig";

interface SidebarItemProps {
  href: string;
  icon: React.ReactNode;
  label: string;
  badge?: string;
  active: boolean;
  isCollapsed?: boolean;
  onNavigate?: () => void;
}

interface DashboardUserProfile {
  fullName?: string;
  username?: string;
  imageUrl?: string;
  type?: string;
  role?: string;
}

const SidebarItem = ({ href, icon, label, badge, active, isCollapsed, onNavigate }: SidebarItemProps) => {
  const router = useRouter();
  const warmRoute = () => {
    if (!shouldConserveData()) router.prefetch(href);
  };

  return (
    <Link
      href={href}
      prefetch={false}
      onClick={onNavigate}
      onPointerEnter={warmRoute}
      onFocus={warmRoute}
      aria-current={active ? "page" : undefined}
      aria-label={isCollapsed ? label : undefined}
      title={isCollapsed ? label : undefined}
      className={`group relative flex min-h-11 items-center ${isCollapsed ? "justify-center" : "justify-between"} rounded-lg px-4 py-3 transition-colors duration-150 ${
        active
          ? "border-l-4 border-[#B8944F] bg-[var(--ops-sidebar-active)] pl-3 text-white"
          : "border-l-4 border-transparent text-[#F1F0EA]/82 hover:bg-[var(--ops-sidebar-hover)] hover:text-white"
      }`}
    >
      <div className="flex items-center gap-3.5">
        <span aria-hidden="true" className={`flex h-5 w-5 items-center justify-center ${active ? "text-[#B8944F]" : "text-[#F1F0EA]/68 group-hover:text-white"}`}>
          {icon}
        </span>
        {!isCollapsed && <span className="text-sm font-medium tracking-wide">{label}</span>}
      </div>
      {badge && (
        isCollapsed ? (
          <span className="absolute top-1.5 right-1.5 flex items-center justify-center text-[9px] font-bold w-4.5 h-4.5 rounded-full bg-[#B8944F] text-[#0F2A43]">
            {badge}
          </span>
        ) : (
          <span className="flex items-center justify-center text-xs font-bold w-5 h-5 rounded-full bg-[#B8944F] text-[#0F2A43]">
            {badge}
          </span>
        )
      )}
    </Link>
  );
};

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const pathname = usePathname();
  const router = useRouter();
  const { t, localize } = useLanguage();
  const [isMobileOpen, setIsMobileOpen] = useState(false);
  const [isCollapsed, setIsCollapsed] = useState(false);
  const [isAccountMenuOpen, setIsAccountMenuOpen] = useState(false);
  const [user, setUser] = useState({ fullName: "Alexandre Martin", role: "General Manager", initials: "AM" });
  const [mounted, setMounted] = useState(false);
  const [accessChecked, setAccessChecked] = useState(false);
  const mobileMenuButtonRef = useRef<HTMLButtonElement>(null);
  const mobileDrawerRef = useRef<HTMLElement>(null);
  const mobileCloseButtonRef = useRef<HTMLButtonElement>(null);
  const wasMobileOpenRef = useRef(false);

  useEffect(() => {
    setMounted(true);
    let active = true;

    const applyUser = (profile: DashboardUserProfile) => {
      const name = profile.fullName || profile.username || "Nhân viên";
      const role = String(profile.type || profile.role || "CUSTOMER").replace("ROLE_", "").toUpperCase();
      const initials = name.split(" ").filter(Boolean).map((part: string) => part[0]).join("").toUpperCase().slice(0, 2);
      setUser({ fullName: name, role, initials });
      return role;
    };

    const validateDashboardAccess = async () => {
      try {
        // Backend là nguồn xác thực role; localStorage chỉ dùng để hiển thị nhanh.
        const response = await cachedGet("/api/user/me", { ttlMs: 5_000 });
        const profile = response.data?.data;
        const role = applyUser(profile || {});
        localStorage.setItem("user", JSON.stringify({
          fullName: profile?.fullName,
          username: profile?.username,
          imageUrl: profile?.imageUrl,
          type: role,
          role,
        }));

        if (role !== "ADMIN" && role !== "STAFF") {
          router.replace("/account");
          return;
        }
        if (active) setAccessChecked(true);
      } catch {
        if (active) router.replace("/login");
      }
    };

    void validateDashboardAccess();
    const handleStorage = () => void validateDashboardAccess();
    window.addEventListener("storage", handleStorage);
    return () => {
      active = false;
      window.removeEventListener("storage", handleStorage);
    };
  }, [router]);

  useEffect(() => {
    setIsMobileOpen(false);
    setIsAccountMenuOpen(false);
  }, [pathname]);

  useEffect(() => {
    if (!isAccountMenuOpen) return;
    const closeAccountMenu = (event: MouseEvent) => {
      const target = event.target as HTMLElement | null;
      if (!target?.closest("[data-dashboard-account-menu]")) setIsAccountMenuOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setIsAccountMenuOpen(false);
    };
    document.addEventListener("mousedown", closeAccountMenu);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("mousedown", closeAccountMenu);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [isAccountMenuOpen]);

  useEffect(() => {
    if (!accessChecked) return;
    const warmDashboardRoutes = () => {
      if (shouldConserveData()) return;
      [
        "/dashboard",
        "/dashboard/rooms",
        "/dashboard/reservations",
      ].forEach((href) => router.prefetch(href));
    };
    return scheduleIdleTask(warmDashboardRoutes);
  }, [accessChecked, router]);

  useEffect(() => {
    const desktopBreakpoint = window.matchMedia("(min-width: 768px)");
    const closeDrawerOnDesktop = (event: MediaQueryListEvent) => {
      if (event.matches) setIsMobileOpen(false);
    };

    desktopBreakpoint.addEventListener("change", closeDrawerOnDesktop);
    return () => desktopBreakpoint.removeEventListener("change", closeDrawerOnDesktop);
  }, []);

  useEffect(() => {
    if (!isMobileOpen) return;

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const focusFrame = window.requestAnimationFrame(() => mobileCloseButtonRef.current?.focus());

    const handleDrawerKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        setIsMobileOpen(false);
        return;
      }

      if (event.key !== "Tab" || !mobileDrawerRef.current) return;

      const focusableElements = Array.from(
        mobileDrawerRef.current.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), select:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ),
      );
      const firstElement = focusableElements[0];
      const lastElement = focusableElements.at(-1);
      if (!firstElement || !lastElement) return;

      if (event.shiftKey && document.activeElement === firstElement) {
        event.preventDefault();
        lastElement.focus();
      } else if (!event.shiftKey && document.activeElement === lastElement) {
        event.preventDefault();
        firstElement.focus();
      }
    };

    document.addEventListener("keydown", handleDrawerKeyDown);
    return () => {
      window.cancelAnimationFrame(focusFrame);
      document.body.style.overflow = previousOverflow;
      document.removeEventListener("keydown", handleDrawerKeyDown);
    };
  }, [isMobileOpen]);

  useEffect(() => {
    if (wasMobileOpenRef.current && !isMobileOpen) {
      mobileMenuButtonRef.current?.focus();
    }
    wasMobileOpenRef.current = isMobileOpen;
  }, [isMobileOpen]);

  const displayedRole = user.role === "ADMIN"
    ? localize("Quản trị viên", "Administrator")
    : user.role === "STAFF"
      ? localize("Nhân viên vận hành", "Operations staff")
      : user.role === "CUSTOMER"
        ? localize("Khách hàng", "Customer")
        : user.role;

  const handleSignOut = async () => {
    try {
      await apiClient.post("/auth/logout");
    } catch (error) {
      console.warn("Logout request failed, clearing local session", error);
    }
    authSession.clear();
    setIsAccountMenuOpen(false);
    router.replace("/login");
  };

  if (!accessChecked) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-[#F1F0EA]" role="status" aria-live="polite">
        <div className="flex items-center gap-3 text-sm font-semibold text-[#66727C]">
          <span className="h-5 w-5 animate-spin rounded-full border-2 border-[#0F2A43]/20 border-t-[#0F2A43]" />
          {localize("Đang kiểm tra quyền truy cập...", "Checking access...")}
        </div>
      </div>
    );
  }

  const navigationItems: Array<Omit<SidebarItemProps, "active" | "isCollapsed">> = [
    {
      href: "/dashboard",
      label: t("overview"),
      icon: (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="w-full h-full">
          <rect x="3" y="3" width="7" height="7" />
          <rect x="14" y="3" width="7" height="7" />
          <rect x="14" y="14" width="7" height="7" />
          <rect x="3" y="14" width="7" height="7" />
        </svg>
      ),
    },
    {
      href: "/dashboard/rooms",
      label: t("rooms"),
      icon: (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="w-full h-full">
          <path d="M2 22v-3a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v3" />
          <path d="M4 17V7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v10" />
          <path d="M8 9h8" />
          <path d="M8 13h8" />
        </svg>
      ),
    },
    {
      href: "/dashboard/reservations",
      label: t("reservation"),
      icon: (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="w-full h-full">
          <rect x="3" y="4" width="18" height="18" rx="2" ry="2" />
          <line x1="16" y1="2" x2="16" y2="6" />
          <line x1="8" y1="2" x2="8" y2="6" />
          <line x1="3" y1="10" x2="21" y2="10" />
        </svg>
      ),
    },
    {
      href: "/dashboard/users",
      label: t("users"),
      icon: (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="w-full h-full">
          <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
          <circle cx="9" cy="7" r="4" />
          <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
          <path d="M16 3.13a4 4 0 0 1 0 7.75" />
        </svg>
      ),
    },
    {
      href: "/dashboard/contact-messages",
      label: localize("Liên hệ", "Contact messages"),
      icon: (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="w-full h-full">
          <path d="M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z" />
          <path d="m22 6-10 7L2 6" />
        </svg>
      ),
    },
    {
      href: "/dashboard/guest",
      label: t("guests"),
      icon: (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="w-full h-full">
          <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
          <circle cx="12" cy="7" r="4" />
          <path d="M8 11h8" />
        </svg>
      ),
    },
    {
      href: "/dashboard/facilities",
      label: t("facilities"),
      icon: (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="w-full h-full">
          <path d="M4 21V8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v13" />
          <path d="M9 21v-7h6v7" />
          <path d="M8 10h.01" />
          <path d="M12 10h.01" />
          <path d="M16 10h.01" />
          <path d="M3 21h18" />
        </svg>
      ),
    },
    {
      href: "/dashboard/room-types",
      label: t("roomTypes"),
      icon: (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="w-full h-full">
          <line x1="12" y1="1" x2="12" y2="23" />
          <path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6" />
        </svg>
      ),
    },
    ...(user.role === "ADMIN" ? [{
      href: "/dashboard/audit-logs",
      label: localize("Nhật ký hệ thống", "System audit log"),
      icon: (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="h-full w-full">
          <path d="M12 3 4 6v5c0 5 3.4 8.7 8 10 4.6-1.3 8-5 8-10V6l-8-3Z" />
          <path d="M9 12h6" />
          <path d="M12 9v6" />
        </svg>
      ),
    }, {
      href: "/dashboard/reconciliation-requests",
      label: localize("Yêu cầu đối soát", "Reconciliation requests"),
      icon: (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="h-full w-full">
          <path d="M4 4h16v16H4z" />
          <path d="m8 12 2.5 2.5L16 9" />
        </svg>
      ),
    }] : []),
  ];

  const isRouteActive = (href: string) => (
    href === "/dashboard"
      ? pathname === href
      : pathname === href || pathname.startsWith(`${href}/`)
  );
  const currentNavigationItem = [...navigationItems]
    .sort((first, second) => second.href.length - first.href.length)
    .find((item) => isRouteActive(item.href)) ?? navigationItems[0];

  const getSidebarContent = (collapsed: boolean, closeOnNavigate = false) => (
    <div className="dashboard-sidebar flex h-full flex-col text-[#F1F0EA]">
      {/* Brand Logo Header */}
      <div className={`p-6 flex items-center ${collapsed ? "justify-center" : "justify-between"} border-b border-[#F1F0EA]/10 h-20`}>
        <Link
          href="/"
          onClick={closeOnNavigate ? () => setIsMobileOpen(false) : undefined}
          aria-label={localize("Về trang chủ", "Go to homepage")}
          className="flex min-h-11 items-center gap-3 rounded-lg transition hover:opacity-90 focus:outline-none focus:ring-2 focus:ring-[#B8944F]/70"
        >
          <HotelBrand
            compact={collapsed}
            tone="inverse"
            descriptor={localize("VẬN HÀNH", "OPERATIONS")}
            markClassName="h-10 w-10"
            wordmarkClassName="text-base whitespace-nowrap"
          />
        </Link>
        {closeOnNavigate && (
          <button
            ref={mobileCloseButtonRef}
            type="button"
            onClick={() => setIsMobileOpen(false)}
            aria-label={localize("Đóng menu điều hướng", "Close navigation menu")}
            className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg text-[#F1F0EA]/75 hover:bg-white/10 hover:text-[#F1F0EA]"
          >
            <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6">
              <line x1="18" y1="6" x2="6" y2="18" />
              <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
        )}
      </div>

      {/* Main Nav Links */}
      <nav aria-label={localize("Điều hướng dashboard", "Dashboard navigation")} className="lux-scrollbar flex-1 space-y-1.5 overflow-y-auto px-4 py-6">
        {[
          { title: t("operations"), items: navigationItems.filter((item) => ["/dashboard", "/dashboard/rooms", "/dashboard/reservations", "/dashboard/reconciliation-requests", "/dashboard/contact-messages", "/dashboard/guest"].includes(item.href)) },
          { title: t("management"), items: navigationItems.filter((item) => ["/dashboard/users", "/dashboard/facilities", "/dashboard/room-types", "/dashboard/audit-logs"].includes(item.href)) },
        ].map((group, groupIndex) => (
          <div key={group.title} className={groupIndex ? "pt-5" : ""}>
            {!collapsed && <p className="mb-2 px-4 text-[0.6rem] font-bold uppercase tracking-[0.24em] text-[#B8944F]/75">{group.title}</p>}
            <div className="space-y-1.5">
              {group.items.map((item) => (
                <SidebarItem
                  key={item.href}
                  href={item.href}
                  label={item.label}
                  icon={item.icon}
                  badge={item.badge}
                  active={mounted && isRouteActive(item.href)}
                  isCollapsed={collapsed}
                  onNavigate={closeOnNavigate ? () => setIsMobileOpen(false) : undefined}
                />
              ))}
            </div>
          </div>
        ))}
      </nav>

      {/* Compact account menu — personal actions stay out of operations nav. */}
      <div data-dashboard-account-menu className="relative border-t border-[#F1F0EA]/12 p-4">
        {isAccountMenuOpen && (
          <div className={`absolute bottom-full z-[70] mb-3 overflow-hidden rounded-xl border border-[#0F2A43]/14 bg-[#FBFAF6] text-[#0F2A43] shadow-[0_22px_60px_rgba(4,15,25,0.32)] ${collapsed ? "left-3 w-64" : "inset-x-4"}`}>
            <div className="bg-[#0F2A43] px-4 py-4 text-white">
              <p className="truncate text-sm font-bold">{user.fullName}</p>
              <p className="mt-1 text-[10px] font-bold uppercase tracking-[0.14em] text-[#D8C398]">{displayedRole}</p>
            </div>
            <div className="space-y-1 p-2">
              <Link href="/dashboard/settings" onClick={() => { setIsAccountMenuOpen(false); if (closeOnNavigate) setIsMobileOpen(false); }} className="flex min-h-11 items-center justify-between rounded-lg px-3 text-sm font-bold transition hover:bg-[#F0EADF]">
                <span>{localize("Cài đặt tài khoản", "Account settings")}</span><span aria-hidden="true">›</span>
              </Link>
              <Link href="/" onClick={() => { setIsAccountMenuOpen(false); if (closeOnNavigate) setIsMobileOpen(false); }} className="flex min-h-11 items-center justify-between rounded-lg px-3 text-sm font-bold transition hover:bg-[#F0EADF]">
                <span>{localize("Trang khách sạn", "Guest website")}</span><span aria-hidden="true">↗</span>
              </Link>
              <div className="rounded-lg bg-[#F1F0EA] p-2"><LanguageSwitcher /></div>
            </div>
            <button type="button" onClick={() => void handleSignOut()} className="flex min-h-12 w-full items-center gap-3 border-t border-[#0F2A43]/10 px-4 text-left text-sm font-bold text-rose-700 transition hover:bg-rose-50">
              <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-4 w-4"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" /><path d="m16 17 5-5-5-5M21 12H9" /></svg>
              {t("logout")}
            </button>
          </div>
        )}
        <button
          type="button"
          onClick={() => setIsAccountMenuOpen((open) => !open)}
          aria-expanded={isAccountMenuOpen}
          aria-haspopup="menu"
          className={`flex min-h-12 w-full items-center rounded-xl border border-white/10 bg-white/[0.04] px-2 text-left transition hover:border-[#B8944F]/50 hover:bg-white/[0.08] ${collapsed ? "justify-center" : "gap-3"}`}
        >
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-[#B8944F] font-serif text-sm font-bold text-[#0F2A43] shadow-sm">{user.initials}</span>
          {!collapsed && <><span className="min-w-0 flex-1"><span className="block truncate text-sm font-semibold text-[#F1F0EA]">{user.fullName}</span><span className="mt-0.5 block truncate text-xs font-medium text-[#B7C0C8]">{displayedRole}</span></span><svg aria-hidden="true" viewBox="0 0 20 20" className={`h-4 w-4 shrink-0 text-[#B7C0C8] transition ${isAccountMenuOpen ? "rotate-180" : ""}`} fill="none" stroke="currentColor" strokeWidth="1.8"><path d="m6 8 4 4 4-4" /></svg></>}
        </button>
      </div>
    </div>
  );

  return (
    <div className="dashboard-shell flex min-h-screen font-sans text-[#0F2A43]">
      {/* Mobile Drawer Overlay */}
      {isMobileOpen && (
        <div
          role="presentation"
          aria-hidden="true"
          onClick={() => setIsMobileOpen(false)}
          className="fixed inset-0 z-40 bg-[#0F2A43]/60 md:hidden"
        />
      )}

      {/* Desktop Sidebar (Collapsible) */}
      <aside
        id="dashboard-desktop-navigation"
        aria-label={localize("Điều hướng dashboard", "Dashboard navigation")}
        className={`sticky top-0 z-30 hidden h-dvh shrink-0 border-r border-black/20 transition-[width] duration-200 md:block ${isCollapsed ? "w-20" : "w-72"}`}
      >
        {getSidebarContent(isCollapsed)}
        {/* Collapse Toggle Button */}
        <button
          type="button"
          onClick={() => setIsCollapsed(!isCollapsed)}
          aria-controls="dashboard-desktop-navigation"
          aria-expanded={!isCollapsed}
          aria-label={isCollapsed
            ? localize("Mở rộng thanh điều hướng", "Expand navigation")
            : localize("Thu gọn thanh điều hướng", "Collapse navigation")}
          className="ops-panel absolute -right-5 top-[72px] z-50 flex h-10 w-10 cursor-pointer items-center justify-center rounded-full border shadow-sm hover:bg-white"
        >
          <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="#66727C" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4">
            {isCollapsed ? (
              <polyline points="9 18 15 12 9 6" />
            ) : (
              <polyline points="15 18 9 12 15 6" />
            )}
          </svg>
        </button>
      </aside>

      {/* Mobile Sidebar (Drawer) */}
      <aside
        ref={mobileDrawerRef}
        id="dashboard-mobile-navigation"
        role="dialog"
        aria-modal="true"
        aria-label={localize("Menu điều hướng dashboard", "Dashboard navigation menu")}
        aria-hidden={!isMobileOpen}
        inert={!isMobileOpen}
        className={`fixed inset-y-0 left-0 z-50 h-dvh w-[min(20rem,calc(100vw-2rem))] transform shadow-2xl transition-transform duration-200 ease-out md:hidden ${
          isMobileOpen ? "translate-x-0" : "pointer-events-none -translate-x-full"
        }`}
      >
        {getSidebarContent(false, true)}
      </aside>

      {/* Main Container */}
      <div className="flex min-h-screen min-w-0 flex-1 flex-col">
        {/* Mobile Header Bar */}
        <header className="dashboard-sidebar sticky top-0 z-20 flex h-[4.5rem] items-center justify-between gap-3 border-b border-white/10 px-4 text-white md:hidden">
          <div className="flex min-w-0 flex-1 items-center gap-3">
            <button
              ref={mobileMenuButtonRef}
              type="button"
              onClick={() => setIsMobileOpen(true)}
              aria-controls="dashboard-mobile-navigation"
              aria-expanded={isMobileOpen}
              aria-label={localize("Mở menu điều hướng", "Open navigation menu")}
              className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg border border-white/15 text-white hover:bg-white/10"
            >
            <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="h-6 w-6">
              <line x1="3" y1="12" x2="21" y2="12" />
              <line x1="3" y1="6" x2="21" y2="6" />
              <line x1="3" y1="18" x2="21" y2="18" />
            </svg>
            </button>
            <div className="min-w-0 leading-tight">
              <p className="truncate text-[0.65rem] font-semibold uppercase tracking-[0.14em] text-[#B8944F]">
                {localize(`${siteConfig.name} · Vận hành`, `${siteConfig.name} · Operations`)}
              </p>
              <p className="mt-1 truncate text-sm font-bold text-white">
                {currentNavigationItem?.label ?? localize("Tổng quan", "Overview")}
              </p>
            </div>
          </div>

          <div
            role="img"
            aria-label={`${user.fullName}, ${displayedRole}`}
            className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-[#B8944F] text-xs font-bold text-[#0F2A43]"
          >
            {user.initials}
          </div>
        </header>

        {/* Content Body */}
        <main id="dashboard-main-content" className="flex flex-1 flex-col bg-[var(--ops-canvas)]">
          {children}
        </main>
      </div>
    </div>
  );
}
