"use client";

import React, { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { cachedGet } from "@/lib/api";
import { useLanguage } from "@/components/i18n/LanguageProvider";

type DashboardRole = "ADMIN" | "STAFF" | null;

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
  pendingServiceRequests: number;
  openContactMessages: number;
  totalRooms: number;
  availableRooms: number;
  occupiedRooms: number;
  maintenanceRooms: number;
  dirtyRooms: number;
  cleaningRooms: number;
  occupancyRate: number;
  customerAccounts: number;
  customerProfiles: number;
};

type MoneySnapshot = {
  cashIncome: number;
  transferIncome: number;
  totalIncome: number;
  cashRefund: number;
  transferRefund: number;
  totalRefund: number;
  netRevenue: number;
  paymentCount: number;
  refundCount: number;
};

type CashierShiftSnapshot = {
  id: number;
  shiftCode: string;
  status: string;
  openedAtUtc?: string;
  movementCount: number;
  cashIncomeAmount: number;
  transferIncomeAmount: number;
  totalIncomeAmount: number;
  cashRefundAmount: number;
  transferRefundAmount: number;
  totalRefundAmount: number;
  netAmount: number;
};

const emptySummary: DashboardSummary = {
  arrivalsToday: 0,
  departuresToday: 0,
  activeStays: 0,
  bookingsCreatedToday: 0,
  pendingConfirmations: 0,
  cancellationRequests: 0,
  pendingServiceRequests: 0,
  openContactMessages: 0,
  totalRooms: 0,
  availableRooms: 0,
  occupiedRooms: 0,
  maintenanceRooms: 0,
  dirtyRooms: 0,
  cleaningRooms: 0,
  occupancyRate: 0,
  customerAccounts: 0,
  customerProfiles: 0,
};

const normalizeRole = (profile?: { type?: string; role?: string }): DashboardRole => {
  const value = String(profile?.role || profile?.type || "")
    .replace("ROLE_", "")
    .toUpperCase();
  return value === "ADMIN" || value === "STAFF" ? value : null;
};

const localDateKey = (date = new Date()) => {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
};

export default function DashboardOverview() {
  const { localeTag, localize } = useLanguage();
  const [summary, setSummary] = useState<DashboardSummary>(emptySummary);
  const [attentionItems, setAttentionItems] = useState<AttentionItem[]>([]);
  const [moneySnapshot, setMoneySnapshot] = useState<MoneySnapshot | null>(null);
  const [cashierShift, setCashierShift] = useState<CashierShiftSnapshot | null>(null);
  const [role, setRole] = useState<DashboardRole>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState("");
  const [userName, setUserName] = useState("Nhân viên");

  const loadDashboard = async (refresh = false, knownRole: DashboardRole = role) => {
    if (refresh) setIsRefreshing(true);
    else setIsLoading(true);
    setError("");

    let effectiveRole = knownRole;
    try {
      if (!effectiveRole) {
        const profileResponse = await cachedGet("/api/user/me", { ttlMs: 5_000, force: refresh });
        const profile = profileResponse.data?.data || {};
        effectiveRole = normalizeRole(profile);
        setRole(effectiveRole);
        const name = profile.fullName || profile.username;
        if (name) setUserName(name.split(" ").filter(Boolean).slice(-1)[0]);
      }

      const today = localDateKey();
      const roleRequest = effectiveRole === "ADMIN"
        ? cachedGet(`/api/admin/statistics/money?from=${today}&to=${today}&granularity=day`, {
          ttlMs: 15_000,
          force: refresh,
        })
        : effectiveRole === "STAFF"
          ? cachedGet("/api/accounting/cashier-shifts/current", { ttlMs: 5_000, force: refresh })
          : Promise.resolve(null);

      const [summaryResult, attentionResult, roleResult] = await Promise.allSettled([
        cachedGet("/api/operations/summary", { ttlMs: 10_000, force: refresh }),
        cachedGet("/api/operations/attention", { ttlMs: 10_000, force: refresh }),
        roleRequest,
      ]);

      if (summaryResult.status === "fulfilled") {
        setSummary({ ...emptySummary, ...summaryResult.value.data?.data });
      }
      if (attentionResult.status === "fulfilled") {
        setAttentionItems(attentionResult.value.data?.data?.items || []);
      }
      if (roleResult.status === "fulfilled" && roleResult.value) {
        if (effectiveRole === "ADMIN") {
          setMoneySnapshot(roleResult.value.data?.data?.totals || null);
          setCashierShift(null);
        } else if (effectiveRole === "STAFF") {
          setCashierShift(roleResult.value.data?.data || null);
          setMoneySnapshot(null);
        }
      }

      if ([summaryResult, attentionResult, roleResult].some((result) => result.status === "rejected")) {
        setError(localize(
          "Một phần dữ liệu chưa tải được. Các khu vực còn lại vẫn dùng số liệu mới nhất.",
          "Some data could not be loaded. The remaining sections still use the latest available figures.",
        ));
      }
    } catch {
      setError(localize(
        "Không thể tải dữ liệu Tổng quan. Vui lòng thử làm mới.",
        "The overview could not be loaded. Please refresh and try again.",
      ));
    } finally {
      setIsLoading(false);
      setIsRefreshing(false);
    }
  };

  useEffect(() => {
    void loadDashboard(false, null);
    // Dashboard chỉ tự tải khi mở; người vận hành chủ động làm mới khi cần.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const attentionCopy = (item: AttentionItem) => {
    const copy: Record<AttentionType, { title: string; detail: string }> = {
      CANCELLATION_REQUEST: {
        title: localize("Yêu cầu hủy chờ duyệt", "Cancellation awaiting review"),
        detail: localize("Xác nhận hoặc từ chối yêu cầu hủy", "Approve or reject the cancellation"),
      },
      REFUND_OVERDUE: {
        title: localize("Hoàn tiền đã quá hạn", "Refund is overdue"),
        detail: localize("Ưu tiên đối chiếu và hoàn tất", "Prioritize reconciliation and completion"),
      },
      NO_SHOW_CANDIDATE: {
        title: localize("Cần xác minh no-show", "Potential no-show"),
        detail: localize("Liên hệ khách trước khi xử lý", "Contact the guest before processing"),
      },
      CHECK_OUT_OVERDUE: {
        title: localize("Khách quá giờ trả phòng", "Checkout is overdue"),
        detail: localize("Đối soát, liên hệ khách và xử lý phụ phí", "Reconcile, contact the guest and apply fees"),
      },
      DRAFT_OVERDUE: {
        title: localize("Đơn cọc lâu chưa xác nhận", "Deposit booking awaiting confirmation"),
        detail: localize("Kiểm tra tiền cọc và xác nhận đơn", "Verify the deposit and confirm the booking"),
      },
      DRAFT_PENDING: {
        title: localize("Đơn chờ xác nhận", "Reservation awaiting confirmation"),
        detail: localize("Kiểm tra tiền cọc trong thời gian quy định", "Verify the deposit within the allowed time"),
      },
      CHECK_IN_LATE: {
        title: localize("Khách check-in trễ", "Guest is late for check-in"),
        detail: localize("Liên hệ khách trong ngày", "Contact the guest today"),
      },
      REFUND_PENDING: {
        title: localize("Hoàn tiền chờ xử lý", "Refund awaiting processing"),
        detail: localize("Hoàn tất trước khi quá hạn", "Complete it before it becomes overdue"),
      },
      ARRIVING_SOON: {
        title: localize("Khách sắp đến", "Guest arriving soon"),
        detail: localize("Kiểm tra phòng và thông tin nhận phòng", "Check room readiness and arrival details"),
      },
    };
    return copy[item.type] || { title: item.title, detail: item.detail };
  };

  const formatDateTime = (value?: string) => {
    if (!value) return localize("Chưa có", "Not available");
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
      dot: "bg-rose-600",
    },
    WARNING: {
      label: localize("Cần xử lý", "Action needed"),
      badge: "border-amber-200 bg-amber-50 text-amber-800",
      dot: "bg-amber-500",
    },
    INFO: {
      label: localize("Theo dõi", "Monitor"),
      badge: "border-blue-200 bg-blue-50 text-blue-700",
      dot: "bg-blue-600",
    },
  } as const;

  const queueCounts = useMemo(() => ({
    urgent: attentionItems.filter((item) => item.severity === "DANGER").length,
    warning: attentionItems.filter((item) => item.severity === "WARNING").length,
    monitoring: attentionItems.filter((item) => item.severity === "INFO").length,
  }), [attentionItems]);

  const primaryMetrics = [
    {
      label: localize("Khách đến hôm nay", "Arrivals today"),
      value: summary.arrivalsToday,
      detail: localize(`${summary.pendingConfirmations} đơn chờ xác nhận`, `${summary.pendingConfirmations} awaiting confirmation`),
      href: "/dashboard/reservations",
      accent: "border-t-blue-500",
    },
    {
      label: localize("Đang lưu trú", "Active stays"),
      value: summary.activeStays,
      detail: localize(`${summary.occupiedRooms} phòng đang có khách`, `${summary.occupiedRooms} occupied rooms`),
      href: "/dashboard/guest",
      accent: "border-t-emerald-500",
    },
    {
      label: localize("Trả phòng hôm nay", "Departures today"),
      value: summary.departuresToday,
      detail: localize("Kiểm tra tiền và dịch vụ trước khi trả", "Check settlement and services before checkout"),
      href: "/dashboard/reservations",
      accent: "border-t-[#B8944F]",
    },
    {
      label: localize("Phòng cần xử lý", "Rooms needing action"),
      value: summary.dirtyRooms + summary.cleaningRooms + summary.maintenanceRooms,
      detail: localize(`${summary.dirtyRooms} cần dọn · ${summary.cleaningRooms} đang dọn · ${summary.maintenanceRooms} bảo trì`, `${summary.dirtyRooms} dirty · ${summary.cleaningRooms} cleaning · ${summary.maintenanceRooms} maintenance`),
      href: "/dashboard/rooms",
      accent: "border-t-rose-500",
    },
  ];

  const operationalQueues = [
    {
      label: localize("Đơn chờ xác nhận", "Awaiting confirmation"),
      value: summary.pendingConfirmations,
      href: "/dashboard/reservations",
      tone: "bg-amber-50 text-amber-900 border-amber-200",
    },
    {
      label: localize("Yêu cầu hủy", "Cancellation requests"),
      value: summary.cancellationRequests,
      href: "/dashboard/reservations",
      tone: "bg-rose-50 text-rose-900 border-rose-200",
    },
    {
      label: localize("Dịch vụ chờ phục vụ", "Services awaiting fulfilment"),
      value: summary.pendingServiceRequests,
      href: "/dashboard/services",
      tone: "bg-blue-50 text-blue-900 border-blue-200",
    },
    {
      label: localize("Liên hệ chưa hoàn tất", "Open contact requests"),
      value: summary.openContactMessages,
      href: "/dashboard/contact-messages",
      tone: "bg-violet-50 text-violet-900 border-violet-200",
    },
  ];

  const roomHealth = [
    { label: localize("Sẵn sàng", "Ready"), value: summary.availableRooms, dot: "bg-emerald-500" },
    { label: localize("Đang có khách", "Occupied"), value: summary.occupiedRooms, dot: "bg-blue-500" },
    { label: localize("Cần dọn", "Needs cleaning"), value: summary.dirtyRooms, dot: "bg-amber-500" },
    { label: localize("Đang dọn", "Cleaning"), value: summary.cleaningRooms, dot: "bg-violet-500" },
    { label: localize("Bảo trì", "Maintenance"), value: summary.maintenanceRooms, dot: "bg-rose-500" },
  ];

  const quickLinks = [
    ["/dashboard/reservations", localize("Đặt phòng", "Reservations"), localize("Xác nhận, check-in và checkout", "Confirm, check in and check out")],
    ["/dashboard/rooms", localize("Sơ đồ phòng", "Room board"), localize("Xem phòng trống và tình trạng dọn", "View availability and cleaning")],
    ["/dashboard/guest", localize("Khách lưu trú", "In-house guests"), localize("Tra cứu khách theo từng đơn", "Find guests by reservation")],
    ["/dashboard/services", localize("Dịch vụ thêm", "Add-on services"), localize("Xác nhận và hoàn tất phục vụ", "Confirm and fulfil service orders")],
    ["/dashboard/contact-messages", localize("Liên hệ", "Contact requests"), localize("Theo dõi yêu cầu hỗ trợ", "Track support requests")],
    role === "ADMIN"
      ? ["/dashboard/statistics", localize("Báo cáo thu chi", "Money report"), localize("Thu, hoàn và doanh thu thực nhận", "Income, refunds and net revenue")]
      : ["/dashboard/work-schedules", localize("Ca làm việc", "Work shift"), localize("Check-in, điểm danh và bàn giao cuối ca", "Check in, attendance and end-of-shift handover")],
  ];

  const occupancyAngle = Math.max(0, Math.min(100, summary.occupancyRate)) * 3.6;

  return (
    <div className="ops-page mx-auto w-full max-w-[1600px] space-y-5 p-4 sm:p-6 lg:p-8">
      <header className="ops-section-header overflow-hidden rounded-2xl border px-5 py-6 sm:px-7">
        <div className="flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between">
          <div className="max-w-3xl">
            <p className="text-xs font-bold uppercase tracking-[0.2em] text-[#E4C780]">
              {localize("Trung tâm điều hành", "Operations command center")}
            </p>
            <h1 className="mt-2 text-3xl font-bold tracking-tight text-white sm:text-4xl">
              {localize(`Chào ${userName}, hôm nay cần làm gì?`, `Hello ${userName}, what needs attention today?`)}
            </h1>
            <p className="mt-2 max-w-2xl text-sm leading-6 text-white/68">
              {localize(
                "Ưu tiên công việc đang chờ, theo dõi khách đến–đi và mở đúng màn hình nghiệp vụ để xử lý.",
                "Prioritize pending work, monitor arrivals and departures, and open the right operational workspace.",
              )}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <div className="rounded-lg border border-white/15 bg-white/5 px-4 py-2.5 text-xs text-white/65">
              <span className="block font-bold uppercase tracking-[0.14em] text-white/45">{localize("Cập nhật", "Updated")}</span>
              <span className="mt-1 block font-semibold tabular-nums text-white">{formatDateTime(summary.generatedAt)}</span>
            </div>
            <button
              type="button"
              disabled={isRefreshing}
              aria-busy={isRefreshing || undefined}
              onClick={() => void loadDashboard(true, role)}
              className="inline-flex min-h-11 items-center justify-center gap-2 rounded-lg border border-white/20 bg-white/8 px-4 text-sm font-bold text-white transition hover:bg-white/15 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#E4C780] disabled:cursor-not-allowed disabled:opacity-55"
            >
              {isRefreshing && <span aria-hidden="true" className="h-4 w-4 animate-spin rounded-full border-2 border-current border-r-transparent" />}
              {isRefreshing ? localize("Đang làm mới...", "Refreshing...") : localize("Làm mới", "Refresh")}
            </button>
            <Link href="/dashboard/reservations" className="inline-flex min-h-11 items-center rounded-lg bg-[#B8944F] px-5 text-sm font-bold text-[#0F2A43] transition hover:bg-[#C8A65F] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white">
              {localize("Mở đặt phòng", "Open reservations")}
            </Link>
          </div>
        </div>
      </header>

      {error && <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm font-semibold text-amber-900" role="alert">{error}</div>}

      <section aria-labelledby="today-operations-title">
        <div className="mb-3 flex items-end justify-between gap-4">
          <div>
            <h2 id="today-operations-title" className="text-xl font-bold text-[#0F2A43]">{localize("Tình hình hôm nay", "Today at a glance")}</h2>
            <p className="mt-1 text-sm text-[#66727C]">{localize("Số liệu vận hành thực tế, không thay thế trang nghiệp vụ chi tiết.", "Live operational figures; detailed work remains in each workspace.")}</p>
          </div>
          <span className="hidden text-xs font-bold uppercase tracking-[0.14em] text-[#80632F] sm:block">{role === "ADMIN" ? "ADMIN" : role === "STAFF" ? "STAFF" : ""}</span>
        </div>
        {isLoading ? (
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">{[0, 1, 2, 3].map((item) => <div key={item} className="ops-panel-strong h-32 animate-pulse rounded-xl" />)}</div>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            {primaryMetrics.map((metric) => (
              <Link key={metric.label} href={metric.href} className={`ops-panel-strong group rounded-xl border border-t-4 ${metric.accent} p-5 transition hover:-translate-y-0.5 hover:border-[#B8944F]/55 hover:shadow-lg`}>
                <div className="flex items-start justify-between gap-3">
                  <p className="text-sm font-semibold text-[#66727C]">{metric.label}</p>
                  <span aria-hidden="true" className="text-[#80632F] transition group-hover:translate-x-0.5">→</span>
                </div>
                <p className="mt-2 text-3xl font-bold tabular-nums text-[#0F2A43]">{metric.value}</p>
                <p className="mt-3 border-t border-[#0F2A43]/8 pt-3 text-xs font-medium leading-5 text-[#66727C]">{metric.detail}</p>
              </Link>
            ))}
          </div>
        )}
      </section>

      <div className="grid gap-5 xl:grid-cols-[1.45fr_0.75fr]">
        <section className="ops-panel-strong overflow-hidden rounded-xl border" aria-labelledby="attention-title">
          <div className="flex flex-col gap-4 border-b border-[#0F2A43]/10 px-5 py-5 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <div className="flex items-center gap-3">
                <h2 id="attention-title" className="text-xl font-bold text-[#0F2A43]">{localize("Việc cần xử lý", "Work requiring attention")}</h2>
                <span className="rounded-md bg-[#0F2A43] px-2.5 py-1 text-xs font-bold tabular-nums text-white">{attentionItems.length}</span>
              </div>
              <p className="mt-1 text-sm text-[#66727C]">{localize("Đã sắp xếp theo mức độ rủi ro và hạn gần nhất.", "Sorted by risk and nearest deadline.")}</p>
            </div>
            <div className="flex flex-wrap gap-2 text-xs font-bold">
              <span className="rounded-md border border-rose-200 bg-rose-50 px-2.5 py-1.5 text-rose-700">{queueCounts.urgent} {localize("khẩn cấp", "urgent")}</span>
              <span className="rounded-md border border-amber-200 bg-amber-50 px-2.5 py-1.5 text-amber-800">{queueCounts.warning} {localize("cần xử lý", "action")}</span>
              <span className="rounded-md border border-blue-200 bg-blue-50 px-2.5 py-1.5 text-blue-700">{queueCounts.monitoring} {localize("theo dõi", "monitor")}</span>
            </div>
          </div>

          <div className="grid gap-2 border-b border-[#0F2A43]/10 bg-[#F5F2EA] p-3 sm:grid-cols-2 lg:grid-cols-4">
            {operationalQueues.map((item) => (
              <Link key={item.label} href={item.href} className={`rounded-lg border p-3 transition hover:-translate-y-0.5 hover:bg-white ${item.tone}`}>
                <div className="flex items-center justify-between gap-2"><span className="text-xs font-bold leading-4">{item.label}</span><span className="text-xl font-bold tabular-nums">{item.value}</span></div>
              </Link>
            ))}
          </div>

          {isLoading ? (
            <div className="space-y-2 p-4">{[0, 1, 2, 3].map((item) => <div key={item} className="h-20 animate-pulse rounded-lg bg-[var(--ops-surface-muted)]" />)}</div>
          ) : attentionItems.length ? (
            <div className="divide-y divide-[#0F2A43]/8">
              {attentionItems.slice(0, 6).map((item, index) => {
                const copy = attentionCopy(item);
                const style = severityStyle[item.severity];
                return (
                  <Link key={`${item.type}-${item.reservationId}-${index}`} href={`/dashboard/reservations?reservationId=${item.reservationId}`} className="group grid gap-3 px-5 py-4 transition hover:bg-[#F8F5EE] sm:grid-cols-[auto_1fr_auto] sm:items-center">
                    <span aria-hidden="true" className={`h-2.5 w-2.5 rounded-full ${style.dot}`} />
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className={`rounded-md border px-2 py-0.5 text-[10px] font-bold ${style.badge}`}>{style.label}</span>
                        <span className="text-xs font-bold text-[#0F2A43]">{item.reservationCode}</span>
                        <span className="truncate text-xs text-[#66727C]">{item.customerName || localize("Khách hàng", "Guest")}</span>
                      </div>
                      <p className="mt-1 text-sm font-bold text-[#0F2A43]">{copy.title}</p>
                      <p className="mt-0.5 text-xs leading-5 text-[#66727C]">{copy.detail}</p>
                    </div>
                    <div className="flex items-center justify-between gap-3 text-right sm:block">
                      <p className="text-[10px] font-bold uppercase tracking-[0.12em] text-[#88929B]">{localize("Hạn", "Due")}</p>
                      <p className="mt-1 text-xs font-semibold tabular-nums text-[#0F2A43]">{formatDateTime(item.dueAt)}</p>
                      {Number(item.amount || 0) > 0 && <p className="mt-1 text-xs font-bold tabular-nums text-rose-700">{formatMoney(item.amount)}</p>}
                    </div>
                  </Link>
                );
              })}
            </div>
          ) : (
            <div className="px-5 py-10 text-center">
              <div className="mx-auto flex h-11 w-11 items-center justify-center rounded-full bg-emerald-50 text-lg font-bold text-emerald-700">✓</div>
              <h3 className="mt-3 text-base font-bold text-[#0F2A43]">{localize("Không có việc tồn đọng", "No outstanding work")}</h3>
              <p className="mt-1 text-sm text-[#66727C]">{localize("Các hàng đợi vận hành hiện đã được xử lý.", "The operational queues are currently clear.")}</p>
            </div>
          )}
          {attentionItems.length > 6 && <div className="border-t border-[#0F2A43]/10 px-5 py-3 text-right"><Link href="/dashboard/reservations" className="text-sm font-bold text-[#0F2A43] hover:text-[#80632F]">{localize(`Xem thêm ${attentionItems.length - 6} việc`, `View ${attentionItems.length - 6} more`)}</Link></div>}
        </section>

        <div className="space-y-5">
          <section className="ops-panel-strong rounded-xl border p-5" aria-labelledby="room-status-title">
            <div className="flex items-start justify-between gap-3">
              <div><h2 id="room-status-title" className="text-lg font-bold text-[#0F2A43]">{localize("Tình trạng phòng", "Room readiness")}</h2><p className="mt-1 text-xs text-[#66727C]">{summary.totalRooms} {localize("phòng vật lý", "physical rooms")}</p></div>
              <Link href="/dashboard/rooms" className="text-xs font-bold text-[#80632F] hover:text-[#0F2A43]">{localize("Mở sơ đồ", "Open board")} →</Link>
            </div>
            <div className="mt-5 flex items-center gap-5">
              <div className="relative h-28 w-28 shrink-0 rounded-full" style={{ background: `conic-gradient(#B8944F ${occupancyAngle}deg, rgba(15,42,67,0.09) 0deg)` }}>
                <div className="absolute inset-3 flex flex-col items-center justify-center rounded-full bg-[#FBFAF6]"><span className="text-2xl font-bold tabular-nums text-[#0F2A43]">{summary.occupancyRate}%</span><span className="text-[10px] font-bold uppercase text-[#66727C]">{localize("công suất", "occupied")}</span></div>
              </div>
              <div className="grid flex-1 grid-cols-2 gap-x-4 gap-y-3">
                {roomHealth.map((item) => <div key={item.label}><div className="flex items-center gap-2"><span className={`h-2 w-2 rounded-full ${item.dot}`} /><span className="text-[11px] font-semibold text-[#66727C]">{item.label}</span></div><p className="mt-1 pl-4 text-lg font-bold tabular-nums text-[#0F2A43]">{item.value}</p></div>)}
              </div>
            </div>
            <p className="mt-4 border-t border-[#0F2A43]/8 pt-3 text-[11px] leading-5 text-[#66727C]">{localize("Phòng cần dọn có thể vẫn thuộc trạng thái sẵn sàng; kiểm tra sơ đồ phòng trước khi gán.", "A room needing cleaning may still be marked available; check the room board before assignment.")}</p>
          </section>

          {role === "ADMIN" ? (
            <section className="overflow-hidden rounded-xl border border-[#0F2A43]/15 bg-[#0F2A43] p-5 text-white" aria-labelledby="money-today-title">
              <div className="flex items-start justify-between gap-3"><div><p className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#E4C780]">{localize("ADMIN · Hôm nay", "ADMIN · Today")}</p><h2 id="money-today-title" className="mt-1 text-lg font-bold">{localize("Thu chi nhanh", "Money snapshot")}</h2></div><Link href="/dashboard/statistics" className="text-xs font-bold text-[#E4C780] hover:text-white">{localize("Xem báo cáo", "Open report")} →</Link></div>
              <div className="mt-4 grid grid-cols-3 gap-2">
                <div className="rounded-lg bg-white/7 p-3"><p className="text-[10px] font-bold uppercase text-white/55">{localize("Tổng thu", "Income")}</p><p className="mt-1 text-base font-bold tabular-nums">{formatMoney(moneySnapshot?.totalIncome)}</p></div>
                <div className="rounded-lg bg-white/7 p-3"><p className="text-[10px] font-bold uppercase text-white/55">{localize("Hoàn", "Refund")}</p><p className="mt-1 text-base font-bold tabular-nums text-rose-200">{formatMoney(moneySnapshot?.totalRefund)}</p></div>
                <div className="rounded-lg bg-[#B8944F]/20 p-3"><p className="text-[10px] font-bold uppercase text-[#E4C780]">{localize("Thực nhận", "Net")}</p><p className="mt-1 text-base font-bold tabular-nums text-[#F8E7B4]">{formatMoney(moneySnapshot?.netRevenue)}</p></div>
              </div>
              <p className="mt-3 text-[11px] leading-5 text-white/55">{localize("Tự động tính từ payment và refund hoàn tất; không gồm giao dịch ngân hàng chưa ghép đơn.", "Calculated from completed payments and refunds; unmatched bank events are excluded.")}</p>
            </section>
          ) : (
            <section className="ops-panel-strong rounded-xl border p-5" aria-labelledby="cashier-shift-title">
              <div className="flex items-start justify-between gap-3"><div><p className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#80632F]">{localize("STAFF · Ca làm việc", "STAFF · Work shift")}</p><h2 id="cashier-shift-title" className="mt-1 text-lg font-bold text-[#0F2A43]">{cashierShift ? localize("Đã check-in ca", "Checked in") : localize("Chưa check-in ca", "Not checked in")}</h2></div><Link href="/dashboard/work-schedules" className="text-xs font-bold text-[#80632F] hover:text-[#0F2A43]">{localize("Mở ca làm việc", "Open work shift")} →</Link></div>
              {cashierShift ? <><p className="mt-3 text-xs font-bold text-[#66727C]">{cashierShift.shiftCode} · {cashierShift.movementCount} {localize("phát sinh", "movements")}</p><div className="mt-4 grid grid-cols-3 gap-2"><div className="rounded-lg bg-emerald-50 p-3"><p className="text-[10px] font-bold uppercase text-emerald-700">{localize("Thu", "Income")}</p><p className="mt-1 text-sm font-bold tabular-nums text-emerald-900">{formatMoney(cashierShift.totalIncomeAmount)}</p></div><div className="rounded-lg bg-rose-50 p-3"><p className="text-[10px] font-bold uppercase text-rose-700">{localize("Hoàn", "Refund")}</p><p className="mt-1 text-sm font-bold tabular-nums text-rose-900">{formatMoney(cashierShift.totalRefundAmount)}</p></div><div className="rounded-lg bg-[#0F2A43]/6 p-3"><p className="text-[10px] font-bold uppercase text-[#66727C]">{localize("Thực nhận", "Net")}</p><p className="mt-1 text-sm font-bold tabular-nums text-[#0F2A43]">{formatMoney(cashierShift.netAmount)}</p></div></div></> : <p className="mt-3 text-sm leading-6 text-[#66727C]">{localize("Check-in ca làm việc để bắt đầu điểm danh và mở ca thu ngân tự động. ADMIN không cần thao tác này.", "Check in to start attendance and automatically open the cashier shift. ADMIN does not need this step.")}</p>}
            </section>
          )}
        </div>
      </div>

      <section className="ops-panel-strong rounded-xl border p-5" aria-labelledby="quick-workspaces-title">
        <div className="flex flex-col gap-2 border-b border-[#0F2A43]/10 pb-4 sm:flex-row sm:items-end sm:justify-between"><div><h2 id="quick-workspaces-title" className="text-lg font-bold text-[#0F2A43]">{localize("Mở nhanh khu vực làm việc", "Quick workspaces")}</h2><p className="mt-1 text-xs text-[#66727C]">{localize("Tổng quan chỉ để định hướng; dữ liệu được xử lý tại trang nghiệp vụ tương ứng.", "The overview guides work; changes happen in the corresponding workspace.")}</p></div><p className="text-xs font-semibold text-[#66727C]">{localize(`${summary.bookingsCreatedToday} đơn tạo hôm nay`, `${summary.bookingsCreatedToday} bookings created today`)}</p></div>
        <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {quickLinks.map(([href, label, detail]) => <Link key={href} href={href} className="ops-panel group rounded-lg border p-4 transition hover:-translate-y-0.5 hover:border-[#B8944F]/60 hover:bg-white"><div className="flex items-start justify-between gap-3"><div><h3 className="text-sm font-bold text-[#0F2A43]">{label}</h3><p className="mt-1 text-xs leading-5 text-[#66727C]">{detail}</p></div><span aria-hidden="true" className="text-[#80632F] transition group-hover:translate-x-0.5">→</span></div></Link>)}
        </div>
      </section>
    </div>
  );
}
