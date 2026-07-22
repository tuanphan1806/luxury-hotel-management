"use client";

import React, { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { cachedGet } from "@/lib/api";
import { useLanguage } from "@/components/i18n/LanguageProvider";

type ReservationItem = {
  id?: number;
  reservationCode?: string;
  status?: string;
  checkIn?: string;
  checkOut?: string;
  totalAmount?: number;
  customerName?: string;
};

type AttentionType =
  | "CANCELLATION_REQUEST"
  | "REFUND_OVERDUE"
  | "NO_SHOW_CANDIDATE"
  | "CHECK_OUT_OVERDUE"
  | "DRAFT_PENDING"
  | "DRAFT_OVERDUE"
  | "CHECK_IN_LATE"
  | "REFUND_PENDING"
  | "ARRIVING_SOON";

type AttentionItem = {
  type: AttentionType;
  severity: "INFO" | "WARNING" | "DANGER";
  reservationId: number;
  reservationCode: string;
  customerName: string;
  title: string;
  detail: string;
  dueAt?: string;
  amount?: number;
};

type DashboardSummary = {
  generatedAt?: string;
  arrivalsToday: number;
  departuresToday: number;
  activeStays: number;
  bookingsCreatedToday: number;
  pendingConfirmations: number;
  cancellationRequests: number;
  totalRooms: number;
  availableRooms: number;
  occupiedRooms: number;
  maintenanceRooms: number;
  dirtyRooms: number;
  occupancyRate: number;
  customerAccounts: number;
  customerProfiles: number;
};

const emptySummary: DashboardSummary = {
  arrivalsToday: 0,
  departuresToday: 0,
  activeStays: 0,
  bookingsCreatedToday: 0,
  pendingConfirmations: 0,
  cancellationRequests: 0,
  totalRooms: 0,
  availableRooms: 0,
  occupiedRooms: 0,
  maintenanceRooms: 0,
  dirtyRooms: 0,
  occupancyRate: 0,
  customerAccounts: 0,
  customerProfiles: 0,
};

const sameDay = (value?: string, target = new Date()) => {
  if (!value) return false;
  const date = new Date(value);
  return date.getFullYear() === target.getFullYear()
    && date.getMonth() === target.getMonth()
    && date.getDate() === target.getDate();
};

const getWeekDays = () => {
  const today = new Date();
  const monday = new Date(today);
  const day = today.getDay() || 7;
  monday.setDate(today.getDate() - day + 1);
  return Array.from({ length: 7 }, (_, index) => {
    const date = new Date(monday);
    date.setDate(monday.getDate() + index);
    return date;
  });
};

const reservationPriority: Record<string, number> = {
  CANCELLATION_PENDING: 0,
  CHECKED_IN: 1,
  CONFIRMED: 2,
  DRAFT: 3,
  NO_SHOW: 4,
  CHECKED_OUT: 5,
  CANCELLED: 6,
};

export default function DashboardOverview() {
  const { localeTag, localize } = useLanguage();
  const [summary, setSummary] = useState<DashboardSummary>(emptySummary);
  const [reservations, setReservations] = useState<ReservationItem[]>([]);
  const [attentionItems, setAttentionItems] = useState<AttentionItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState("");
  const [userName, setUserName] = useState(localize("Nhân viên", "Staff"));

  const loadDashboard = async (refresh = false) => {
    if (refresh) setIsRefreshing(true);
    else setIsLoading(true);
    setError("");
    const results = await Promise.allSettled([
      cachedGet("/api/operations/summary"),
      cachedGet("/api/operations/attention"),
      cachedGet("/api/reservations"),
    ]);

    const [summaryResult, attentionResult, reservationResult] = results;
    if (summaryResult.status === "fulfilled") {
      setSummary({ ...emptySummary, ...summaryResult.value.data?.data });
    }
    if (attentionResult.status === "fulfilled") {
      setAttentionItems(attentionResult.value.data?.data?.items || []);
    }
    if (reservationResult.status === "fulfilled") {
      setReservations(reservationResult.value.data?.data || []);
    }
    if (results.some((result) => result.status === "rejected")) {
      setError(localize(
        "Một phần dữ liệu vận hành chưa tải được. Các khu vực còn lại vẫn hiển thị dữ liệu mới nhất.",
        "Some operations data could not be loaded. Other sections still show the latest available data.",
      ));
    }
    setIsLoading(false);
    setIsRefreshing(false);
  };

  useEffect(() => {
    const storedUser = localStorage.getItem("user");
    if (storedUser) {
      try {
        const parsed = JSON.parse(storedUser);
        const name = parsed.fullName || parsed.username;
        if (name) setUserName(name.split(" ").filter(Boolean).slice(-1)[0]);
      } catch {
        // Dashboard layout đã xử lý session; tên mặc định vẫn dùng được.
      }
    }
    void loadDashboard();
    // Chỉ tải lại khi dashboard được mở; nút Làm mới xử lý các lần tiếp theo.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const attentionCopy = (item: AttentionItem) => {
    const copy: Record<AttentionType, { title: string; detail: string }> = {
      CANCELLATION_REQUEST: {
        title: localize("Yêu cầu hủy chờ duyệt", "Cancellation request awaiting review"),
        detail: localize("Quyết định xác nhận hoặc từ chối hủy", "Approve or reject the cancellation request"),
      },
      REFUND_OVERDUE: {
        title: localize("Hoàn tiền đã quá hạn", "Refund is overdue"),
        detail: localize("Ưu tiên đối chiếu và hoàn tất giao dịch", "Prioritize reconciliation and completion"),
      },
      NO_SHOW_CANDIDATE: {
        title: localize("Cần xác minh no-show", "Potential no-show"),
        detail: localize("Liên hệ khách trước khi đánh dấu không đến", "Contact the guest before marking no-show"),
      },
      CHECK_OUT_OVERDUE: {
        title: localize("Khách quá giờ trả phòng", "Guest is past checkout time"),
        detail: localize("Mở đối soát và xử lý phụ phí nếu có", "Open reconciliation and apply fees if needed"),
      },
      DRAFT_OVERDUE: {
        title: localize("Đơn cọc lâu chưa xác nhận", "Deposit booking awaiting confirmation"),
        detail: localize("Kiểm tra tiền cọc và xác nhận đơn", "Verify the deposit and confirm the booking"),
      },
      DRAFT_PENDING: {
        title: localize("Đơn chờ xác nhận", "Reservation awaiting confirmation"),
        detail: localize("Kiểm tra tiền cọc và xác nhận trong thời gian quy định", "Verify the deposit and confirm within the allowed time"),
      },
      CHECK_IN_LATE: {
        title: localize("Khách check-in trễ", "Guest is late for check-in"),
        detail: localize("Liên hệ khách trong ngày", "Contact the guest today"),
      },
      REFUND_PENDING: {
        title: localize("Hoàn tiền chờ xử lý", "Refund awaiting processing"),
        detail: localize("Hoàn tất trước khi chuyển thành quá hạn", "Complete it before it becomes overdue"),
      },
      ARRIVING_SOON: {
        title: localize("Khách sắp đến", "Guest arriving soon"),
        detail: localize("Kiểm tra phòng sạch và thông tin check-in", "Check room readiness and guest details"),
      },
    };
    return copy[item.type] || { title: item.title, detail: item.detail };
  };

  const formatDateTime = (value?: string) => {
    if (!value) return localize("Chưa có hạn", "No deadline");
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return date.toLocaleString(localeTag, {
      day: "2-digit",
      month: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    });
  };

  const formatMoney = (value?: number) => Number(value || 0).toLocaleString(localeTag, {
    style: "currency",
    currency: "VND",
    maximumFractionDigits: 0,
  });

  const severityStyle = {
    DANGER: {
      label: localize("Khẩn cấp", "Urgent"),
      badge: "border-rose-200 bg-rose-50 text-rose-700",
      border: "border-l-rose-600",
    },
    WARNING: {
      label: localize("Cần xử lý", "Action needed"),
      badge: "border-amber-200 bg-amber-50 text-amber-800",
      border: "border-l-amber-500",
    },
    INFO: {
      label: localize("Theo dõi", "Monitor"),
      badge: "border-blue-200 bg-blue-50 text-blue-700",
      border: "border-l-blue-600",
    },
  } as const;

  const queueCounts = useMemo(() => ({
    urgent: attentionItems.filter((item) => item.severity === "DANGER").length,
    warning: attentionItems.filter((item) => item.severity === "WARNING").length,
    monitoring: attentionItems.filter((item) => item.severity === "INFO").length,
  }), [attentionItems]);

  const pendingConfirmationItems = attentionItems.filter((item) => item.type === "DRAFT_PENDING" || item.type === "DRAFT_OVERDUE");
  const cancellationRequestItems = attentionItems.filter((item) => item.type === "CANCELLATION_REQUEST");
  const arrivingSoonItems = attentionItems.filter((item) => item.type === "ARRIVING_SOON");
  const queueBusinessGroups = [
    {
      label: localize("Đơn chờ xác nhận", "Awaiting confirmation"),
      value: summary.pendingConfirmations,
      items: pendingConfirmationItems,
      empty: localize("Không có đơn DRAFT", "No DRAFT reservations"),
      tone: "border-amber-200 bg-amber-50/60 text-amber-900",
    },
    {
      label: localize("Yêu cầu hủy chờ duyệt", "Cancellation awaiting review"),
      value: summary.cancellationRequests,
      items: cancellationRequestItems,
      empty: localize("Không có yêu cầu hủy", "No cancellation requests"),
      tone: "border-rose-200 bg-rose-50/60 text-rose-900",
    },
    {
      label: localize("Khách sắp nhận phòng", "Arriving soon"),
      value: arrivingSoonItems.length,
      items: arrivingSoonItems,
      empty: localize("Không có khách trong 2 giờ tới", "No arrivals in the next 2 hours"),
      tone: "border-blue-200 bg-blue-50/60 text-blue-900",
    },
  ];

  const weekStats = useMemo(() => {
    const activeReservations = reservations.filter((item) => !["CANCELLED", "NO_SHOW"].includes(item.status || ""));
    const days = getWeekDays();
    const max = Math.max(1, ...days.map((day) => activeReservations.filter((item) => sameDay(item.checkIn, day)).length));
    return days.map((day) => {
      const count = activeReservations.filter((item) => sameDay(item.checkIn, day)).length;
      return {
        label: day.toLocaleDateString(localeTag, { weekday: "short" }),
        date: day.toLocaleDateString(localeTag, { day: "2-digit", month: "2-digit" }),
        count,
        height: count === 0 ? 4 : Math.max(12, Math.round((count / max) * 100)),
      };
    });
  }, [reservations, localeTag]);

  const recentReservations = useMemo(() => [...reservations]
    .sort((a, b) => {
      const statusDifference = (reservationPriority[a.status || ""] ?? 9) - (reservationPriority[b.status || ""] ?? 9);
      return statusDifference || Number(b.id || 0) - Number(a.id || 0);
    })
    .slice(0, 6), [reservations]);

  const metrics = [
    {
      label: localize("Khách đến hôm nay", "Arrivals today"),
      value: summary.arrivalsToday,
      detail: localize(`${summary.pendingConfirmations} đơn đang chờ xác nhận`, `${summary.pendingConfirmations} awaiting confirmation`),
      href: "/dashboard/reservations",
    },
    {
      label: localize("Khách trả phòng hôm nay", "Departures today"),
      value: summary.departuresToday,
      detail: localize(`${summary.activeStays} đơn đang lưu trú`, `${summary.activeStays} active stays`),
      href: "/dashboard/reservations",
    },
    {
      label: localize("Công suất thực tế", "Actual occupancy"),
      value: `${summary.occupancyRate}%`,
      detail: localize(`${summary.occupiedRooms}/${Math.max(0, summary.totalRooms - summary.maintenanceRooms)} phòng trống`, `${summary.occupiedRooms}/${Math.max(0, summary.totalRooms - summary.maintenanceRooms)} available rooms`),
      href: "/dashboard/rooms",
    },
    {
      label: localize("Đơn tạo hôm nay", "Bookings created today"),
      value: summary.bookingsCreatedToday,
      detail: localize(`${summary.cancellationRequests} yêu cầu hủy chờ duyệt`, `${summary.cancellationRequests} cancellation requests`),
      href: "/dashboard/reservations",
    },
    {
      label: localize("Hồ sơ khách hàng", "Customer profiles"),
      value: summary.customerProfiles,
      detail: localize(`${summary.customerAccounts} tài khoản khách có đăng nhập`, `${summary.customerAccounts} customer login accounts`),
      href: "/dashboard/guest",
    },
  ];

  const roomHealth = [
    { label: localize("Sẵn sàng", "Ready"), value: summary.availableRooms, tone: "text-emerald-700", bar: "bg-emerald-600" },
    { label: localize("Đang có khách", "Occupied"), value: summary.occupiedRooms, tone: "text-blue-700", bar: "bg-blue-600" },
    { label: localize("Cần dọn", "Needs cleaning"), value: summary.dirtyRooms, tone: "text-amber-800", bar: "bg-amber-500" },
    { label: localize("Bảo trì", "Maintenance"), value: summary.maintenanceRooms, tone: "text-rose-700", bar: "bg-rose-600" },
  ];

  return (
    <div className="ops-page mx-auto w-full max-w-[1600px] space-y-6 p-4 sm:p-6 lg:p-8">
      <header className="flex flex-col gap-4 border-b border-[#0F2A43]/10 pb-5 md:flex-row md:items-end md:justify-between">
        <div>
          <p className="text-sm font-semibold text-[#80632F]">{localize(`Chào ${userName}, đây là tình hình ca trực hiện tại.`, `Hello ${userName}, here is the current shift status.`)}</p>
          <h1 className="mt-1 text-3xl font-bold tracking-tight text-[#0F2A43]">{localize("Tổng quan vận hành", "Operations overview")}</h1>
          <p className="mt-2 text-sm text-[#66727C]">{localize("Ưu tiên việc cần xử lý trước, sau đó theo dõi khách đến–đi và tình trạng phòng.", "Handle urgent work first, then monitor arrivals, departures and room readiness.")}</p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <p className="text-xs font-medium text-[#66727C]">{localize("Cập nhật", "Updated")}: {formatDateTime(summary.generatedAt)}</p>
          <button type="button" disabled={isRefreshing} aria-busy={isRefreshing || undefined} onClick={() => void loadDashboard(true)} className="ops-panel-strong inline-flex min-h-11 items-center justify-center gap-2 rounded-lg border px-4 text-sm font-bold text-[#0F2A43] hover:bg-[var(--ops-surface-muted)] disabled:opacity-50">
            {isRefreshing && <span aria-hidden="true" className="h-4 w-4 animate-spin rounded-full border-2 border-current border-r-transparent" />}
            {isRefreshing ? localize("Đang làm mới...", "Refreshing...") : localize("Làm mới dữ liệu", "Refresh data")}
          </button>
          <Link href="/dashboard/reservations" className="inline-flex min-h-11 items-center rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white hover:bg-[#091E30]">{localize("Mở đặt phòng", "Open reservations")}</Link>
        </div>
      </header>

      {error && <div className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm font-semibold text-amber-900" role="alert">{error}</div>}

      <section className="ops-panel overflow-hidden rounded-xl border">
        <div className="ops-section-header flex flex-col gap-4 border-b px-5 py-5 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <div className="flex items-center gap-3">
              <h2 className="text-xl font-bold">{localize("Hàng đợi lễ tân", "Front desk queue")}</h2>
              <span className="rounded-md bg-white/10 px-2.5 py-1 text-xs font-bold tabular-nums">{attentionItems.length}</span>
            </div>
            <p className="mt-1 text-sm text-white/65">{localize("Sắp xếp theo mức độ khẩn cấp và hạn xử lý gần nhất.", "Sorted by urgency and nearest deadline.")}</p>
          </div>
          <div className="grid w-full gap-2 text-xs lg:w-auto lg:grid-cols-3">
            <div className="rounded-lg border border-rose-300/30 bg-rose-400/10 px-3 py-2"><p className="font-bold text-rose-100"><span className="mr-1 tabular-nums">{queueCounts.urgent}</span> {localize("khẩn cấp", "urgent")}</p><p className="mt-0.5 text-[11px] text-white/55">{localize("Quá hạn hoặc rủi ro cao", "Overdue or high risk")}</p></div>
            <div className="rounded-lg border border-amber-300/30 bg-amber-400/10 px-3 py-2"><p className="font-bold text-amber-100"><span className="mr-1 tabular-nums">{queueCounts.warning}</span> {localize("cần xử lý", "action needed")}</p><p className="mt-0.5 text-[11px] text-white/55">{localize("Đã đến hạn xử lý", "Action is now due")}</p></div>
            <div className="rounded-lg border border-blue-300/30 bg-blue-400/10 px-3 py-2"><p className="font-bold text-blue-100"><span className="mr-1 tabular-nums">{queueCounts.monitoring}</span> {localize("cần theo dõi", "to monitor")}</p><p className="mt-0.5 text-[11px] text-white/55">{localize("Chưa quá hạn", "Not overdue yet")}</p></div>
          </div>
        </div>

        {!isLoading && (
          <div className="ops-panel-muted grid gap-3 border-b p-4 md:grid-cols-3">
            {queueBusinessGroups.map((group) => (
              <Link key={group.label} href="/dashboard/reservations" className={`rounded-lg border p-4 ${group.tone} hover:bg-white`}>
                <div className="flex items-center justify-between gap-3"><h3 className="text-sm font-bold">{group.label}</h3><span className="text-2xl font-bold tabular-nums">{group.value}</span></div>
                {group.items.length > 0 ? (
                  <p className="mt-2 text-xs font-semibold leading-5">{localize("Đơn", "Reservations")}: {group.items.slice(0, 3).map((item) => item.reservationCode || `#${item.reservationId}`).join(" · ")}{group.items.length > 3 ? ` +${group.items.length - 3}` : ""}</p>
                ) : (
                  <p className="mt-2 text-xs opacity-70">{group.empty}</p>
                )}
              </Link>
            ))}
          </div>
        )}

        {isLoading ? (
          <div className="grid gap-3 p-5 lg:grid-cols-2">
            {[0, 1, 2, 3].map((item) => <div key={item} className="h-28 animate-pulse rounded-lg bg-[var(--ops-surface-muted)]" />)}
          </div>
        ) : attentionItems.length ? (
          <div className="grid gap-3 p-4 lg:grid-cols-2">
            {attentionItems.slice(0, 8).map((item, index) => {
              const copy = attentionCopy(item);
              const style = severityStyle[item.severity];
              return (
                <Link key={`${item.type}-${item.reservationId}-${index}`} href={`/dashboard/reservations?reservationId=${item.reservationId}`} className={`ops-panel-strong group border-l-4 ${style.border} rounded-lg border-y border-r p-4 hover:border-r-[#B8944F]/50 hover:bg-white`}>
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className={`rounded-md border px-2 py-1 text-[11px] font-bold ${style.badge}`}>{style.label}</span>
                        <span className="text-xs font-bold text-[#66727C]">{item.reservationCode}</span>
                      </div>
                      <h3 className="mt-2 text-sm font-bold text-[#0F2A43]">{copy.title}</h3>
                      <p className="mt-1 text-xs leading-5 text-[#66727C]">{copy.detail}</p>
                    </div>
                    <span className="text-sm font-bold text-[#0F2A43] group-hover:text-[#80632F]">→</span>
                  </div>
                  <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-[#0F2A43]/8 pt-3 text-xs">
                    <span className="font-semibold text-[#0F2A43]">{item.customerName || localize("Khách hàng", "Guest")}</span>
                    <span className="font-medium tabular-nums text-[#66727C]">{localize("Hạn", "Due")}: {formatDateTime(item.dueAt)}</span>
                    {Number(item.amount || 0) > 0 && <span className="font-bold tabular-nums text-rose-700">{formatMoney(item.amount)}</span>}
                  </div>
                </Link>
              );
            })}
          </div>
        ) : (
          <div className="p-8 text-center">
            <div className="mx-auto flex h-11 w-11 items-center justify-center rounded-lg bg-emerald-50 text-xl font-bold text-emerald-700">✓</div>
            <h3 className="mt-3 text-base font-bold text-[#0F2A43]">{localize("Không có việc cần theo dõi", "No work to monitor")}</h3>
            <p className="mt-1 text-sm text-[#66727C]">{localize("Không có đơn chờ xác nhận, yêu cầu hủy, khách sắp đến hoặc công việc quá hạn.", "There are no pending confirmations, cancellation requests, upcoming arrivals or overdue tasks.")}</p>
          </div>
        )}

        {attentionItems.length > 8 && <div className="border-t border-[#0F2A43]/10 px-5 py-3 text-right"><Link href="/dashboard/reservations" className="text-sm font-bold text-[#0F2A43] hover:text-[#80632F]">{localize(`Xem thêm ${attentionItems.length - 8} việc`, `View ${attentionItems.length - 8} more items`)}</Link></div>}
      </section>

      <section aria-labelledby="today-title">
        <div className="mb-3 flex items-end justify-between">
          <div><h2 id="today-title" className="text-xl font-bold text-[#0F2A43]">{localize("Vận hành hôm nay", "Today's operations")}</h2><p className="mt-1 text-sm text-[#66727C]">{localize("Số liệu theo lịch dự kiến và trạng thái thực tế.", "Figures combine planned schedules and actual statuses.")}</p></div>
        </div>
        {isLoading ? (
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">{[0, 1, 2, 3, 4].map((item) => <div key={item} className="ops-panel-strong h-32 animate-pulse rounded-xl" />)}</div>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
            {metrics.map((metric) => <Link key={metric.label} href={metric.href} className="ops-panel-strong rounded-xl border p-5 hover:border-[#B8944F]/60">
              <div className="flex items-start justify-between gap-3"><p className="text-sm font-semibold text-[#66727C]">{metric.label}</p><span className="text-[#80632F]">→</span></div>
              <p className="mt-3 text-3xl font-bold tabular-nums text-[#0F2A43]">{metric.value}</p>
              <p className="mt-3 border-t border-[#0F2A43]/8 pt-3 text-xs font-medium leading-5 text-[#66727C]">{metric.detail}</p>
            </Link>)}
          </div>
        )}
      </section>

      <div className="grid gap-5 xl:grid-cols-[0.85fr_1.15fr]">
        <section className="ops-panel-strong rounded-xl border p-5">
          <div className="flex items-center justify-between border-b border-[#0F2A43]/10 pb-4"><div><h2 className="text-lg font-bold text-[#0F2A43]">{localize("Sức khỏe phòng", "Room health")}</h2><p className="mt-1 text-xs text-[#66727C]">{summary.totalRooms} {localize("phòng vật lý", "physical rooms")}</p></div><Link href="/dashboard/rooms" className="text-sm font-bold text-[#0F2A43]">{localize("Mở sơ đồ phòng", "Open room map")}</Link></div>
          <div className="mt-4 space-y-4">
            {roomHealth.map((item) => {
              const percentage = summary.totalRooms ? Math.round(item.value * 100 / summary.totalRooms) : 0;
              return <div key={item.label}>
                <div className="flex items-center justify-between text-sm"><span className="font-semibold text-[#0F2A43]">{item.label}</span><span className={`font-bold tabular-nums ${item.tone}`}>{item.value} · {percentage}%</span></div>
                <div className="mt-2 h-2 overflow-hidden rounded-sm bg-[#0F2A43]/8"><div className={`h-full ${item.bar}`} style={{ width: `${Math.min(100, percentage)}%` }} /></div>
              </div>;
            })}
          </div>
        </section>

        <section className="ops-panel rounded-xl border p-5">
          <div className="flex items-center justify-between border-b border-[#0F2A43]/10 pb-4"><div><h2 className="text-lg font-bold text-[#0F2A43]">{localize("Khách đến trong tuần", "Weekly arrivals")}</h2><p className="mt-1 text-xs text-[#66727C]">{localize("Không tính đơn hủy và no-show", "Excludes cancelled and no-show reservations")}</p></div><span className="text-xs font-bold text-[#80632F]">{localize("Tuần này", "This week")}</span></div>
          <div className="mt-5 grid h-48 grid-cols-7 items-end gap-2">
            {weekStats.map((day) => <div key={day.date} className="flex h-full min-w-0 flex-col items-center justify-end gap-2">
              <span className="text-xs font-bold tabular-nums text-[#0F2A43]">{day.count}</span>
              <div className="flex h-28 w-full max-w-8 items-end overflow-hidden rounded-sm bg-[#0F2A43]/8"><div className="w-full bg-[#B8944F]" style={{ height: `${day.height}%` }} /></div>
              <span className="truncate text-[11px] font-semibold text-[#66727C]">{day.label}</span>
              <span className="hidden text-[10px] tabular-nums text-[#66727C] sm:block">{day.date}</span>
            </div>)}
          </div>
        </section>
      </div>

      <section className="ops-panel-strong rounded-xl border">
        <div className="flex items-center justify-between border-b border-[#0F2A43]/10 px-5 py-4"><div><h2 className="text-lg font-bold text-[#0F2A43]">{localize("Đơn cần theo dõi", "Reservations to monitor")}</h2><p className="mt-1 text-xs text-[#66727C]">{localize("Ưu tiên trạng thái đang cần hành động thay vì chỉ lấy đơn mới nhất.", "Prioritized by actionable status rather than creation time alone.")}</p></div><Link href="/dashboard/reservations" className="text-sm font-bold text-[#0F2A43]">{localize("Xem tất cả", "View all")}</Link></div>
        <div className="grid gap-3 p-4 md:grid-cols-2 xl:grid-cols-3">
          {recentReservations.map((item) => <Link key={item.id || item.reservationCode} href={`/dashboard/reservations?reservationId=${item.id}`} className="ops-panel rounded-lg border p-4 hover:border-[#B8944F]/60">
            <div className="flex items-center justify-between gap-3"><span className="truncate text-sm font-bold text-[#0F2A43]">{item.reservationCode || `#${item.id}`}</span><span className="rounded-md bg-[#0F2A43]/8 px-2 py-1 text-[10px] font-bold text-[#0F2A43]">{item.status || "UNKNOWN"}</span></div>
            <p className="mt-2 truncate text-sm font-medium text-[#66727C]">{item.customerName || localize("Chưa có tên khách", "Guest name unavailable")}</p>
            <div className="mt-3 flex justify-between gap-3 border-t border-[#0F2A43]/8 pt-3 text-xs tabular-nums text-[#66727C]"><span>{formatDateTime(item.checkIn)}</span><span className="font-semibold text-[#0F2A43]">{formatMoney(item.totalAmount)}</span></div>
          </Link>)}
          {!recentReservations.length && !isLoading && <p className="p-4 text-sm text-[#66727C]">{localize("Chưa có đơn đặt phòng để theo dõi.", "No reservations require monitoring.")}</p>}
        </div>
      </section>
    </div>
  );
}
