"use client";

import dynamic from "next/dynamic";
import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import CashierShiftPanel from "@/components/dashboard/CashierShiftPanel";
import DashboardTimeGroupingControl from "@/components/dashboard/DashboardTimeGroupingControl";
import type { FinanceReservationDetail } from "@/components/dashboard/FinanceReservationDetailModal";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { useDashboardRole } from "@/hooks/use-dashboard-role";
import type { DashboardTimeGrouping } from "@/lib/dashboard-time";
import {
  apiClient,
  cachedGet,
  getApiErrorMessage,
  getApiErrorStatus,
} from "@/lib/api";
import {
  apiData,
  financeWorkspaceFromQuery,
  type FinanceWorkspaceView,
  type MoneyBreakdown,
  type MoneyReport,
  monthToDatePreset,
  type ReservationMoneyEntry,
  type ReservationMoneyPage,
  type StatisticsGranularity,
  statisticsPreset,
  suggestedStatisticsGranularity,
} from "@/lib/business-statistics";

const FinanceReservationDetailModal = dynamic(
  () => import("@/components/dashboard/FinanceReservationDetailModal"),
  { ssr: false },
);

type DateRange = { from: string; to: string };
type RangePreset = "today" | "7" | "30" | "month" | "custom";

const initialRange = statisticsPreset(30);
const emptyReservations: ReservationMoneyPage = {
  content: [],
  page: 0,
  size: 100,
  totalElements: 0,
  totalPages: 0,
};
const panelClass =
  "rounded-2xl border border-[#0F2A43]/10 bg-white shadow-[0_12px_34px_rgba(15,42,67,0.07)]";

function vnd(value: number, locale = "vi-VN") {
  return new Intl.NumberFormat(locale, {
    style: "currency",
    currency: "VND",
    maximumFractionDigits: 0,
  }).format(Number(value || 0));
}

function reportPeriodLabel(
  start: string,
  endExclusive: string,
  granularity: StatisticsGranularity,
  localeTag: string,
) {
  const from = new Date(`${start}T00:00:00`);
  if (granularity === "month") {
    const end = new Date(`${endExclusive}T00:00:00`);
    const fullMonthEnd = new Date(from.getFullYear(), from.getMonth() + 1, 1);
    if (from.getDate() === 1 && end.getTime() === fullMonthEnd.getTime()) {
      return new Intl.DateTimeFormat(localeTag, {
        month: "2-digit",
        year: "numeric",
      }).format(from);
    }
  }
  if (granularity === "week" || granularity === "month") {
    const to = new Date(`${endExclusive}T00:00:00`);
    to.setDate(to.getDate() - 1);
    return `${from.toLocaleDateString(localeTag)} – ${to.toLocaleDateString(localeTag)}`;
  }
  return from.toLocaleDateString(localeTag);
}

function reservationStatusLabel(status: string) {
  return {
    DRAFT: "Chờ xác nhận",
    PAYMENT_PENDING: "Chờ thanh toán",
    CONFIRMED: "Đã xác nhận",
    CANCELLATION_PENDING: "Chờ duyệt hủy",
    CANCELLED: "Đã hủy",
    CHECKED_IN: "Đang lưu trú",
    CHECKED_OUT: "Đã trả phòng",
    NO_SHOW: "Không đến",
  }[status] || status.replaceAll("_", " ");
}

function reservationStatusClass(status: string) {
  return {
    CANCELLED: "border-rose-200 bg-rose-50 text-rose-700",
    CHECKED_OUT: "border-emerald-200 bg-emerald-50 text-emerald-700",
    CHECKED_IN: "border-amber-200 bg-amber-50 text-amber-800",
    CONFIRMED: "border-sky-200 bg-sky-50 text-sky-700",
  }[status] || "border-[#0F2A43]/12 bg-[#F1F0EA] text-[#0F2A43]";
}

function sumMoney(items: ReservationMoneyEntry[]): MoneyBreakdown {
  return items.reduce<MoneyBreakdown>((total, item) => ({
    cashIncome: total.cashIncome + item.amounts.cashIncome,
    transferIncome: total.transferIncome + item.amounts.transferIncome,
    totalIncome: total.totalIncome + item.amounts.totalIncome,
    cashRefund: total.cashRefund + item.amounts.cashRefund,
    transferRefund: total.transferRefund + item.amounts.transferRefund,
    totalRefund: total.totalRefund + item.amounts.totalRefund,
    netRevenue: total.netRevenue + item.amounts.netRevenue,
    paymentCount: total.paymentCount + item.amounts.paymentCount,
    refundCount: total.refundCount + item.amounts.refundCount,
  }), {
    cashIncome: 0,
    transferIncome: 0,
    totalIncome: 0,
    cashRefund: 0,
    transferRefund: 0,
    totalRefund: 0,
    netRevenue: 0,
    paymentCount: 0,
    refundCount: 0,
  });
}

function MoneyCard({
  label,
  value,
  detail,
  tone = "navy",
}: {
  label: string;
  value: string;
  detail: string;
  tone?: "navy" | "green" | "red" | "gold";
}) {
  const styles = {
    navy: "border-[#0F2A43]/10 bg-white text-[#0F2A43]",
    green: "border-emerald-200 bg-emerald-50 text-emerald-950",
    red: "border-rose-200 bg-rose-50 text-rose-950",
    gold: "border-[#B8944F]/25 bg-[#F8F3E8] text-[#0F2A43]",
  }[tone];
  return (
    <article className={`rounded-2xl border p-5 ${styles}`}>
      <p className="text-[10px] font-extrabold uppercase tracking-[0.18em] opacity-70">
        {label}
      </p>
      <p className="mt-3 font-serif text-2xl font-bold sm:text-3xl">{value}</p>
      <p className="mt-2 text-xs leading-5 opacity-70">{detail}</p>
    </article>
  );
}

function BreakdownGrid({
  totals,
  localeTag,
}: {
  totals: MoneyBreakdown;
  localeTag: string;
}) {
  const items = [
    {
      label: "Thu bằng tiền mặt",
      value: totals.cashIncome,
      detail: "Tiền mặt đã thu và ghi nhận vào đơn",
      tone: "green" as const,
    },
    {
      label: "Thu bằng chuyển khoản",
      value: totals.transferIncome,
      detail: "Chuyển khoản SePay đã ghép đúng đơn",
      tone: "green" as const,
    },
    {
      label: "Hoàn bằng tiền mặt",
      value: totals.cashRefund,
      detail: "Tiền mặt đã hoàn cho khách",
      tone: "red" as const,
    },
    {
      label: "Hoàn bằng chuyển khoản",
      value: totals.transferRefund,
      detail: "Chuyển khoản hoàn đã được xác nhận",
      tone: "red" as const,
    },
  ];
  return (
    <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4" aria-label="Chi tiết nguồn tiền">
      {items.map((item) => (
        <MoneyCard
          key={item.label}
          label={item.label}
          value={vnd(item.value, localeTag)}
          detail={item.detail}
          tone={item.tone}
        />
      ))}
    </section>
  );
}

export default function BusinessStatisticsPage() {
  const { localeTag, localize } = useLanguage();
  const { role, isAdmin } = useDashboardRole();
  const [activeView, setActiveView] = useState<FinanceWorkspaceView>("overview");
  const [preset, setPreset] = useState<RangePreset>("30");
  const [range, setRange] = useState<DateRange>(initialRange);
  const [draftRange, setDraftRange] = useState<DateRange>(initialRange);
  const [granularity, setGranularity] = useState<StatisticsGranularity>("day");
  const [report, setReport] = useState<MoneyReport | null>(null);
  const [reservations, setReservations] =
    useState<ReservationMoneyPage>(emptyReservations);
  const [reservationPage, setReservationPage] = useState(0);
  const [queryDraft, setQueryDraft] = useState("");
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState("");
  const [lastUpdatedAt, setLastUpdatedAt] = useState<Date | null>(null);
  const [selectedMoneyReservation, setSelectedMoneyReservation] =
    useState<ReservationMoneyEntry | null>(null);
  const [reservationDetail, setReservationDetail] =
    useState<FinanceReservationDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState("");
  const [expandedMoneyKey, setExpandedMoneyKey] = useState<string | null>(null);

  useEffect(() => {
    const sync = () => {
      const requested = new URLSearchParams(window.location.search).get("tab");
      setActiveView(financeWorkspaceFromQuery(requested));
    };
    sync();
    window.addEventListener("popstate", sync);
    return () => window.removeEventListener("popstate", sync);
  }, []);

  const selectView = (view: FinanceWorkspaceView) => {
    setActiveView(view);
    const url = new URL(window.location.href);
    if (view === "cashier") url.searchParams.set("tab", "cashier");
    else url.searchParams.delete("tab");
    window.history.replaceState(
      window.history.state,
      "",
      `${url.pathname}${url.search}${url.hash}`,
    );
  };

  const loadReport = useCallback(async (force = false) => {
    setError("");
    try {
      const [moneyResponse, reservationResponse] = await Promise.all([
        cachedGet<{ data: MoneyReport }>("/api/admin/statistics/money", {
          ttlMs: 30_000,
          force,
          config: { params: { ...range, granularity } },
        }),
        cachedGet<{ data: ReservationMoneyPage }>("/api/admin/statistics/money/reservations", {
          ttlMs: 30_000,
          force,
          config: {
            params: {
              ...range,
              granularity,
              q: query || undefined,
              page: reservationPage,
              size: 100,
            },
          },
        }),
      ]);
      setReport(apiData(moneyResponse));
      setReservations(apiData(reservationResponse));
      setLastUpdatedAt(new Date());
    } catch (requestError) {
      setError(
        getApiErrorStatus(requestError) === 403
          ? localize(
            "Chỉ ADMIN được xem báo cáo tài chính.",
            "Only ADMIN can access financial reports.",
          )
          : getApiErrorMessage(
            requestError,
            localize(
              "Không thể tải số liệu thu chi. Vui lòng thử lại.",
              "Unable to load money data. Please try again.",
            ),
          ),
      );
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [granularity, localize, query, range, reservationPage]);

  useEffect(() => {
    if (isAdmin && activeView === "overview") void loadReport();
  }, [activeView, isAdmin, loadReport]);

  useEffect(() => {
    if (!isAdmin || activeView !== "overview") return;
    const refreshVisible = () => {
      if (document.visibilityState === "visible") void loadReport(true);
    };
    const timer = window.setInterval(refreshVisible, 60_000);
    window.addEventListener("focus", refreshVisible);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener("focus", refreshVisible);
    };
  }, [activeView, isAdmin, loadReport]);

  const applyPreset = (nextPreset: Exclude<RangePreset, "custom">) => {
    const next = nextPreset === "today"
      ? statisticsPreset(1)
      : nextPreset === "7"
        ? statisticsPreset(7)
        : nextPreset === "30"
          ? statisticsPreset(30)
          : monthToDatePreset();
    setPreset(nextPreset);
    setRange(next);
    setDraftRange(next);
    setGranularity(suggestedStatisticsGranularity(next));
    setReservationPage(0);
    setExpandedMoneyKey(null);
  };

  const applyCustomRange = () => {
    if (!draftRange.from || !draftRange.to || draftRange.from > draftRange.to) {
      setError("Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.");
      return;
    }
    setPreset("custom");
    setRange(draftRange);
    setGranularity(suggestedStatisticsGranularity(draftRange));
    setReservationPage(0);
    setExpandedMoneyKey(null);
  };

  const refresh = () => {
    setRefreshing(true);
    void loadReport(true);
  };

  const applySearch = () => {
    setReservationPage(0);
    setQuery(queryDraft.trim());
  };

  const openReservationDetail = async (item: ReservationMoneyEntry) => {
    setSelectedMoneyReservation(item);
    setReservationDetail(null);
    setDetailError("");
    setDetailLoading(true);
    try {
      const response = await cachedGet<{ data: FinanceReservationDetail }>(
        `/api/reservations/${item.reservationId}`,
        { ttlMs: 30_000 },
      );
      setReservationDetail(apiData(response));
    } catch (requestError) {
      setDetailError(
        getApiErrorMessage(
          requestError,
          "Không thể tải chi tiết đơn. Vui lòng thử lại.",
        ),
      );
    } finally {
      setDetailLoading(false);
    }
  };

  const closeReservationDetail = () => {
    setSelectedMoneyReservation(null);
    setReservationDetail(null);
    setDetailError("");
  };

  const exportMoneyReport = async () => {
    setExporting(true);
    setError("");
    try {
      const response = await apiClient.get("/api/admin/statistics/export", {
        params: {
          report: "money",
          ...range,
          granularity,
          q: query || undefined,
        },
        responseType: "blob",
      });
      const disposition = String(response.headers["content-disposition"] || "");
      const encodedName = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
      const plainName = disposition.match(/filename=\"?([^\";]+)\"?/i)?.[1];
      const fileName = encodedName
        ? decodeURIComponent(encodedName)
        : plainName || `luxury-hotel-bao-cao-thu-chi-${range.from}_${range.to}.csv`;
      const url = window.URL.createObjectURL(response.data);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = fileName;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      window.URL.revokeObjectURL(url);
    } catch (requestError) {
      setError(
        getApiErrorMessage(
          requestError,
          "Không thể xuất file báo cáo. Vui lòng thử lại.",
        ),
      );
    } finally {
      setExporting(false);
    }
  };

  const totals = report?.totals;
  const dateSummary = useMemo(
    () => `${range.from.split("-").reverse().join("/")} – ${range.to.split("-").reverse().join("/")}`,
    [range],
  );
  const timelineGroups = useMemo(() => {
    if (!report) return [];
    const groups = new Map<string, {
      period: string;
      periodEndExclusive: string;
      items: ReservationMoneyEntry[];
      amounts: MoneyBreakdown;
    }>();
    reservations.content.forEach((item) => {
      let matchingPeriod = item.period
        ? report.periods.find((point) => point.period === item.period)
        : undefined;
      if (!matchingPeriod) {
        const movementDate = new Intl.DateTimeFormat("en-CA", {
          year: "numeric",
          month: "2-digit",
          day: "2-digit",
          timeZone: "Asia/Ho_Chi_Minh",
        }).format(new Date(item.lastMovementAtUtc));
        matchingPeriod = report.periods.find((point) =>
          point.period <= movementDate
          && movementDate < point.periodEndExclusive);
      }
      const period = item.period || matchingPeriod?.period;
      const periodEndExclusive = item.periodEndExclusive
        || matchingPeriod?.periodEndExclusive;
      if (!period || !periodEndExclusive) return;
      const current = groups.get(period);
      if (current) current.items.push(item);
      else {
        groups.set(period, {
          period,
          periodEndExclusive,
          items: [item],
          amounts: matchingPeriod?.amounts || sumMoney([item]),
        });
      }
    });
    return Array.from(groups.values())
      .map((group) => ({
        ...group,
        amounts: query ? sumMoney(group.items) : group.amounts,
      }))
      .sort((left, right) => right.period.localeCompare(left.period));
  }, [query, report, reservations.content]);

  if (role && !isAdmin) {
    return (
      <main className="mx-auto flex min-h-[70vh] max-w-3xl items-center px-5 py-12">
        <section className={`${panelClass} w-full p-8 text-center`}>
          <h1 className="font-serif text-3xl font-bold text-[#0F2A43]">
            Báo cáo tài chính chỉ dành cho ADMIN
          </h1>
          <p className="mx-auto mt-3 max-w-xl text-sm leading-6 text-[#66727C]">
            STAFF sử dụng Ca thu ngân để bắt đầu, kết thúc và xem các khoản hệ thống tự ghi nhận trong ca.
          </p>
          <Link
            href="/dashboard/cashier-shifts"
            className="mt-6 inline-flex min-h-11 items-center rounded-lg bg-[#0F2A43] px-5 font-bold text-white transition hover:bg-[#173D5F]"
          >
            Mở ca thu ngân
          </Link>
        </section>
      </main>
    );
  }

  if (!role) {
    return (
      <main className="mx-auto max-w-[1500px] px-4 py-8 sm:px-6">
        <div className="h-64 animate-pulse rounded-2xl bg-[#0F2A43]/7" />
      </main>
    );
  }

  return (
    <main className="finance-report-print-root mx-auto w-full max-w-[1500px] px-4 py-6 sm:px-6 lg:py-8">
      <header className="overflow-hidden rounded-3xl bg-[#0F2A43] text-white shadow-[0_18px_48px_rgba(15,42,67,0.18)]">
        <div className="grid gap-6 px-6 py-7 lg:grid-cols-[1fr_auto] lg:items-end lg:px-8">
          <div>
            <p className="text-[10px] font-extrabold uppercase tracking-[0.24em] text-[#D8C398]">
              Quản lý tài chính · ADMIN
            </p>
            <h1 className="mt-2 font-serif text-3xl font-bold sm:text-4xl">
              Báo cáo thu chi
            </h1>
            <p className="mt-2 max-w-3xl text-sm leading-6 text-white/72">
              Số liệu được tự động tính từ payment và refund đã hoàn tất của từng đơn đặt phòng.
              Không nhập lại doanh thu và không cộng giao dịch ngân hàng chưa ghép đơn.
            </p>
          </div>
          <div className="space-y-3 text-xs text-white/70 lg:text-right">
            <div className="flex flex-wrap items-center gap-3 lg:justify-end">
              <span>{dateSummary}</span>
              {lastUpdatedAt && (
                <span>
                  Cập nhật {lastUpdatedAt.toLocaleTimeString(localeTag, {
                    hour: "2-digit",
                    minute: "2-digit",
                  })}
                </span>
              )}
            </div>
            {activeView === "overview" && (
              <div className="finance-report-no-print flex flex-wrap gap-2 lg:justify-end">
                <button
                  type="button"
                  onClick={() => window.print()}
                  className="min-h-11 rounded-lg border border-white/20 px-4 font-bold text-white transition hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#D8C398]"
                >
                  In / Lưu PDF
                </button>
                <button
                  type="button"
                  onClick={() => void exportMoneyReport()}
                  disabled={exporting}
                  className="min-h-11 rounded-lg border border-[#D8C398]/50 bg-[#D8C398] px-4 font-bold text-[#0F2A43] transition hover:bg-[#E7D8B8] disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {exporting ? "Đang xuất…" : "Xuất Excel (CSV)"}
                </button>
                <button
                  type="button"
                  onClick={refresh}
                  disabled={refreshing}
                  className="min-h-11 rounded-lg border border-white/20 px-4 font-bold text-white transition hover:bg-white/10 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {refreshing ? "Đang cập nhật…" : "Cập nhật"}
                </button>
              </div>
            )}
          </div>
        </div>
      </header>

      <nav
        aria-label="Khu vực tài chính"
        className={`${panelClass} finance-report-no-print mt-5 grid gap-2 p-2 sm:grid-cols-2`}
      >
        {([
          ["overview", "Báo cáo thu chi", "Số tiền tự động theo ngày, tuần hoặc tháng"],
          ["cashier", "Quản lý ca thu ngân", "Xem ca của toàn bộ nhân viên"],
        ] as const).map(([view, label, detail]) => (
          <button
            key={view}
            type="button"
            onClick={() => selectView(view)}
            className={`min-h-14 rounded-xl px-4 text-left transition ${
              activeView === view
                ? "bg-[#0F2A43] text-white shadow-md"
                : "text-[#0F2A43] hover:bg-[#F1F0EA]"
            }`}
          >
            <span className="block text-sm font-extrabold">{label}</span>
            <span className={`mt-0.5 block text-[11px] ${activeView === view ? "text-white/65" : "text-[#66727C]"}`}>
              {detail}
            </span>
          </button>
        ))}
      </nav>

      {activeView === "cashier" ? (
        <section className={`${panelClass} mt-5 p-4 sm:p-6`}>
          <CashierShiftPanel embedded adminView />
        </section>
      ) : (
        <>
          <section className={`${panelClass} finance-report-no-print mt-5 p-4 sm:p-5`}>
            <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_auto] lg:items-end">
              <div className="max-w-md">
                <label className="text-xs font-bold text-[#0F2A43]">
                  Khoảng báo cáo
                  <select
                    value={preset}
                    onChange={(event) => {
                      const value = event.target.value as RangePreset;
                      if (value === "custom") setPreset("custom");
                      else applyPreset(value);
                    }}
                    className="mt-2 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20"
                  >
                    <option value="today">Hôm nay</option>
                    <option value="7">7 ngày gần nhất</option>
                    <option value="30">30 ngày gần nhất</option>
                    <option value="month">Tháng này</option>
                    <option value="custom">Tùy chọn ngày</option>
                  </select>
                </label>
              </div>
              {preset === "custom" && (
                <div className="flex flex-wrap items-end gap-2">
                  <label className="text-xs font-bold text-[#0F2A43]">
                    Từ ngày
                    <input
                      type="date"
                      value={draftRange.from}
                      onChange={(event) =>
                        setDraftRange((current) => ({ ...current, from: event.target.value }))}
                      className="mt-2 block min-h-11 rounded-lg border border-[#0F2A43]/15 px-3 text-sm"
                    />
                  </label>
                  <label className="text-xs font-bold text-[#0F2A43]">
                    Đến ngày
                    <input
                      type="date"
                      value={draftRange.to}
                      onChange={(event) =>
                        setDraftRange((current) => ({ ...current, to: event.target.value }))}
                      className="mt-2 block min-h-11 rounded-lg border border-[#0F2A43]/15 px-3 text-sm"
                    />
                  </label>
                  <button
                    type="button"
                    onClick={applyCustomRange}
                    className="min-h-11 rounded-lg bg-[#0F2A43] px-4 text-sm font-bold text-white transition hover:bg-[#173D5F]"
                  >
                    Áp dụng
                  </button>
                </div>
              )}
            </div>
          </section>

          {error && (
            <p role="alert" className="mt-4 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-semibold text-rose-700">
              {error}
            </p>
          )}

          {loading && !report ? (
            <div className="mt-5 grid gap-4 md:grid-cols-3">
              {[1, 2, 3].map((item) => (
                <div key={item} className="h-36 animate-pulse rounded-2xl bg-[#0F2A43]/7" />
              ))}
            </div>
          ) : totals ? (
            <>
              <section className="mt-5 grid gap-4 md:grid-cols-3" aria-label="Tổng hợp thu chi">
                <MoneyCard
                  label="Tổng thu"
                  value={vnd(totals.totalIncome, localeTag)}
                  detail={`${totals.paymentCount.toLocaleString(localeTag)} giao dịch đã ghi nhận vào đơn`}
                  tone="green"
                />
                <MoneyCard
                  label="Tổng hoàn tiền"
                  value={vnd(totals.totalRefund, localeTag)}
                  detail={`${totals.refundCount.toLocaleString(localeTag)} khoản hoàn đã hoàn tất`}
                  tone="red"
                />
                <MoneyCard
                  label="Doanh thu thực nhận"
                  value={vnd(totals.netRevenue, localeTag)}
                  detail="Tổng thu trừ tổng hoàn trong kỳ"
                  tone={totals.netRevenue < 0 ? "red" : "gold"}
                />
              </section>

              <div className="mt-4">
                <BreakdownGrid totals={totals} localeTag={localeTag} />
              </div>

              {report.unmatchedTransferCount > 0 && (
                <aside className="mt-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-950">
                  <strong>{report.unmatchedTransferCount} chuyển khoản chưa ghép đơn</strong>
                  {" · "}
                  {vnd(report.unmatchedTransferAmount, localeTag)} chưa được cộng vào tổng thu.
                  ADMIN cần xử lý tại danh sách giao dịch SePay.
                </aside>
              )}

              <section className={`${panelClass} mt-5 overflow-hidden`}>
                <div className="flex flex-col gap-4 border-b border-[#0F2A43]/10 px-5 py-5 sm:px-6 lg:flex-row lg:items-end lg:justify-between">
                  <div>
                    <p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#B8944F]">
                      Sổ thu chi
                    </p>
                    <h2 className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">
                      Thu chi theo thời gian và đơn đặt phòng
                    </h2>
                    <p className="mt-2 max-w-3xl text-sm leading-6 text-[#66727C]">
                      Mỗi nhóm là một kỳ báo cáo; mở từng đơn để xem rõ tiền mặt, chuyển khoản và khoản hoàn tạo nên số liệu.
                    </p>
                  </div>
                  <div className="finance-report-no-print shrink-0">
                    <DashboardTimeGroupingControl
                      value={granularity.toUpperCase() as DashboardTimeGrouping}
                      onChange={(value) => {
                        setGranularity(value.toLowerCase() as StatisticsGranularity);
                        setReservationPage(0);
                        setExpandedMoneyKey(null);
                      }}
                      title="Nhóm theo thời gian phát sinh"
                      ariaLabel="Nhóm thu chi theo ngày, tuần hoặc tháng"
                      labels={{ day: "Ngày", week: "Tuần", month: "Tháng" }}
                    />
                  </div>
                </div>
                <div className="flex flex-col gap-3 border-b border-[#0F2A43]/10 bg-[#FBFAF6] px-5 py-3 sm:flex-row sm:items-center sm:justify-between sm:px-6">
                  <div>
                    <p className="font-bold text-[#0F2A43]">
                      {reservations.totalElements.toLocaleString(localeTag)} dòng đơn phát sinh tiền
                    </p>
                    <p className="mt-0.5 text-xs text-[#66727C]">
                      Một đơn có thể xuất hiện ở nhiều kỳ nếu ngày thu và ngày hoàn khác nhau.
                    </p>
                  </div>
                  <div className="finance-report-no-print flex w-full max-w-lg gap-2">
                    <label className="sr-only" htmlFor="finance-reservation-search">
                      Tìm mã đơn
                    </label>
                    <input
                      id="finance-reservation-search"
                      value={queryDraft}
                      onChange={(event) => setQueryDraft(event.target.value)}
                      onKeyDown={(event) => {
                        if (event.key === "Enter") applySearch();
                      }}
                      placeholder="Nhập mã đơn…"
                      className="min-h-11 min-w-0 flex-1 rounded-lg border border-[#0F2A43]/15 px-3 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20"
                    />
                    <button
                      type="button"
                      onClick={applySearch}
                      className="min-h-11 rounded-lg bg-[#0F2A43] px-4 text-sm font-bold text-white transition hover:bg-[#173D5F]"
                    >
                      Tìm
                    </button>
                    {(query || queryDraft) && (
                      <button
                        type="button"
                        onClick={() => {
                          setQueryDraft("");
                          setQuery("");
                          setReservationPage(0);
                        }}
                        className="min-h-11 rounded-lg border border-[#0F2A43]/15 px-3 text-sm font-bold text-[#0F2A43] transition hover:bg-[#F1F0EA]"
                      >
                        Xóa lọc
                      </button>
                    )}
                  </div>
                </div>
                {reservations.content.length === 0 ? (
                  <div className="px-6 py-12 text-center text-sm text-[#66727C]">
                    Chưa có đơn phát sinh tiền trong khoảng đã chọn.
                  </div>
                ) : (
                  <div className="space-y-4 bg-[#F7F4EC]/55 p-3">
                    {timelineGroups.map((group) => (
                      <section key={group.period} aria-labelledby={`finance-period-${group.period}`}>
                        <div className="mb-2 flex flex-col gap-2 rounded-md border border-[#0F2A43]/8 bg-[#EAE2D2]/70 px-3 py-2 lg:flex-row lg:items-center lg:justify-between">
                          <div className="flex items-center gap-3">
                            <h3 id={`finance-period-${group.period}`} className="text-[11px] font-black uppercase tracking-[0.12em] text-[#80632F]">
                              {reportPeriodLabel(group.period, group.periodEndExclusive, granularity, localeTag)}
                            </h3>
                            <span className="text-[11px] font-semibold text-[#66727C]">
                              {group.items.length} đơn
                            </span>
                          </div>
                          <div className="grid grid-cols-3 gap-x-4 text-[11px] sm:flex sm:flex-wrap sm:items-center sm:justify-end">
                            <span className="text-[#66727C]">Thu <b className="ml-1 text-emerald-700">{vnd(group.amounts.totalIncome, localeTag)}</b></span>
                            <span className="text-[#66727C]">Hoàn <b className="ml-1 text-rose-700">{vnd(group.amounts.totalRefund, localeTag)}</b></span>
                            <span className="text-[#66727C]">Thực nhận <b className={group.amounts.netRevenue < 0 ? "ml-1 text-rose-700" : "ml-1 text-[#0F2A43]"}>{vnd(group.amounts.netRevenue, localeTag)}</b></span>
                          </div>
                        </div>
                        <div className="space-y-2">
                          {group.items.map((item) => {
                            const rowKey = `${group.period}-${item.reservationId}`;
                            const isExpanded = expandedMoneyKey === rowKey;
                            const detailsId = `finance-money-${rowKey}`;
                            return (
                              <article key={rowKey} className="overflow-hidden rounded-lg border border-[#0F2A43]/12 bg-white shadow-[0_4px_14px_rgba(15,42,67,0.04)]">
                                <button
                                  type="button"
                                  aria-expanded={isExpanded}
                                  aria-controls={detailsId}
                                  onClick={() => setExpandedMoneyKey((current) => current === rowKey ? null : rowKey)}
                                  className="group grid min-h-[72px] w-full cursor-pointer grid-cols-[minmax(0,1fr)_auto] gap-3 px-4 py-3 text-left transition hover:bg-[#F1F0EA]/65 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[#B8944F] xl:grid-cols-[minmax(12rem,1.2fr)_repeat(3,minmax(8rem,0.75fr))_auto] xl:items-center"
                                >
                                  <div className="min-w-0">
                                    <div className="flex flex-wrap items-center gap-2">
                                      <span className="truncate font-mono text-sm font-black text-[#0F2A43]">{item.reservationCode}</span>
                                      <span className={`rounded-md border px-2 py-0.5 text-[10px] font-bold ${reservationStatusClass(item.reservationStatus)}`}>
                                        {reservationStatusLabel(item.reservationStatus)}
                                      </span>
                                    </div>
                                    <p className="mt-1 text-[11px] text-[#66727C]">
                                      {item.amounts.paymentCount} khoản thu · {item.amounts.refundCount} khoản hoàn · {new Date(item.lastMovementAtUtc).toLocaleTimeString(localeTag, { hour: "2-digit", minute: "2-digit", timeZone: "Asia/Ho_Chi_Minh" })}
                                    </p>
                                  </div>
                                  <div className="text-xs">
                                    <p className="text-[10px] font-semibold uppercase tracking-[0.08em] text-[#66727C]">Đã thu</p>
                                    <p className="mt-1 font-bold text-emerald-700">{vnd(item.amounts.totalIncome, localeTag)}</p>
                                  </div>
                                  <div className="text-xs">
                                    <p className="text-[10px] font-semibold uppercase tracking-[0.08em] text-[#66727C]">Đã hoàn</p>
                                    <p className="mt-1 font-bold text-rose-700">{vnd(item.amounts.totalRefund, localeTag)}</p>
                                  </div>
                                  <div className="text-xs">
                                    <p className="text-[10px] font-semibold uppercase tracking-[0.08em] text-[#66727C]">Thực nhận</p>
                                    <p className={`mt-1 font-bold ${item.amounts.netRevenue < 0 ? "text-rose-700" : "text-[#0F2A43]"}`}>{vnd(item.amounts.netRevenue, localeTag)}</p>
                                  </div>
                                  <span className={`col-start-2 row-start-1 flex h-9 w-9 items-center justify-center justify-self-end rounded-full border border-[#0F2A43]/15 bg-white text-[#0F2A43] transition group-hover:border-[#B8944F] group-hover:bg-[#FBFAF6] xl:col-auto xl:row-auto ${isExpanded ? "rotate-180" : ""}`} aria-hidden="true">
                                    <svg viewBox="0 0 24 24" className="h-4 w-4 fill-none stroke-current" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6" /></svg>
                                  </span>
                                </button>
                                {isExpanded && (
                                  <div id={detailsId} className="border-t border-[#0F2A43]/10 bg-[#FBFAF6]">
                                    <dl className="grid gap-px bg-[#0F2A43]/8 sm:grid-cols-2 lg:grid-cols-4">
                                      {[
                                        ["Thu tiền mặt", item.amounts.cashIncome, "text-emerald-700"],
                                        ["Thu chuyển khoản", item.amounts.transferIncome, "text-emerald-700"],
                                        ["Hoàn tiền mặt", item.amounts.cashRefund, "text-rose-700"],
                                        ["Hoàn chuyển khoản", item.amounts.transferRefund, "text-rose-700"],
                                      ].map(([label, value, color]) => (
                                        <div key={String(label)} className="bg-white px-4 py-3 text-xs">
                                          <dt className="text-[#66727C]">{label}</dt>
                                          <dd className={`mt-1 font-bold ${color}`}>{vnd(Number(value), localeTag)}</dd>
                                        </div>
                                      ))}
                                    </dl>
                                    <div className="finance-report-no-print flex flex-wrap items-center justify-end gap-2 border-t border-[#0F2A43]/8 px-4 py-3">
                                      <Link
                                        href={`/dashboard/reservations?reservationCode=${encodeURIComponent(item.reservationCode)}`}
                                        className="inline-flex min-h-10 items-center rounded-lg border border-[#0F2A43]/15 bg-white px-4 text-xs font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F8F6F0]"
                                      >
                                        Mở đơn vận hành
                                      </Link>
                                      <button
                                        type="button"
                                        onClick={() => void openReservationDetail(item)}
                                        className="min-h-10 rounded-lg bg-[#0F2A43] px-4 text-xs font-bold text-white transition hover:bg-[#173D5F] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]"
                                      >
                                        Xem đầy đủ chi phí
                                      </button>
                                    </div>
                                  </div>
                                )}
                              </article>
                            );
                          })}
                        </div>
                      </section>
                    ))}
                  </div>
                )}
                {reservations.totalPages > 1 && (
                  <div className="finance-report-no-print flex items-center justify-between border-t border-[#0F2A43]/10 px-5 py-4 text-sm">
                    <button
                      type="button"
                      onClick={() => setReservationPage((page) => Math.max(0, page - 1))}
                      disabled={reservationPage === 0}
                      className="min-h-10 rounded-lg border border-[#0F2A43]/15 px-4 font-bold disabled:opacity-40"
                    >
                      Trước
                    </button>
                    <span className="text-[#66727C]">
                      Trang {reservationPage + 1}/{reservations.totalPages}
                    </span>
                    <button
                      type="button"
                      onClick={() =>
                        setReservationPage((page) =>
                          Math.min(reservations.totalPages - 1, page + 1))}
                      disabled={reservationPage + 1 >= reservations.totalPages}
                      className="min-h-10 rounded-lg border border-[#0F2A43]/15 px-4 font-bold disabled:opacity-40"
                    >
                      Sau
                    </button>
                  </div>
                )}
              </section>

              <details className="finance-report-no-print mt-4 ml-auto w-fit max-w-full text-right text-xs text-[#66727C]">
                <summary className="cursor-pointer list-none rounded-lg px-2 py-2 font-semibold transition hover:bg-[#F1F0EA] hover:text-[#0F2A43] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">
                  Công cụ xử lý sự cố hiếm gặp
                </summary>
                <div className="mt-1 rounded-xl border border-[#0F2A43]/10 bg-white p-3 text-left shadow-sm">
                  <p className="max-w-md leading-5">
                    Chỉ dùng khi payment, refund và sửa phí hợp lệ vẫn không thể đưa checkout về trạng thái khớp.
                  </p>
                  <Link
                    href="/dashboard/reconciliation-requests"
                    className="mt-2 inline-flex min-h-10 items-center font-bold text-[#80632F] hover:text-[#0F2A43]"
                  >
                    Mở ngoại lệ đối soát →
                  </Link>
                </div>
              </details>
            </>
          ) : null}
        </>
      )}

      <FinanceReservationDetailModal
        selected={selectedMoneyReservation}
        detail={reservationDetail}
        loading={detailLoading}
        error={detailError}
        localeTag={localeTag}
        periodLabel={selectedMoneyReservation?.period && selectedMoneyReservation.periodEndExclusive
          ? reportPeriodLabel(
            selectedMoneyReservation.period,
            selectedMoneyReservation.periodEndExclusive,
            granularity,
            localeTag,
          )
          : dateSummary}
        onClose={closeReservationDetail}
        onRetry={() => {
          if (selectedMoneyReservation) void openReservationDetail(selectedMoneyReservation);
        }}
      />
    </main>
  );
}
