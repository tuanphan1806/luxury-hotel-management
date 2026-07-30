"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import CashierShiftPanel from "@/components/dashboard/CashierShiftPanel";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { useDashboardRole } from "@/hooks/use-dashboard-role";
import { cachedGet, getApiErrorMessage, getApiErrorStatus } from "@/lib/api";
import {
  apiData,
  financeWorkspaceFromQuery,
  type FinanceWorkspaceView,
  type MoneyBreakdown,
  type MoneyReport,
  monthToDatePreset,
  type ReservationMoneyPage,
  type StatisticsGranularity,
  statisticsPreset,
  suggestedStatisticsGranularity,
} from "@/lib/business-statistics";

type DateRange = { from: string; to: string };
type RangePreset = "today" | "7" | "30" | "month" | "custom";

const initialRange = statisticsPreset(30);
const emptyReservations: ReservationMoneyPage = {
  content: [],
  page: 0,
  size: 12,
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
  const [error, setError] = useState("");
  const [lastUpdatedAt, setLastUpdatedAt] = useState<Date | null>(null);

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
              q: query || undefined,
              page: reservationPage,
              size: 12,
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
  };

  const refresh = () => {
    setRefreshing(true);
    void loadReport(true);
  };

  const applySearch = () => {
    setReservationPage(0);
    setQuery(queryDraft.trim());
  };

  const totals = report?.totals;
  const dateSummary = useMemo(
    () => `${range.from.split("-").reverse().join("/")} – ${range.to.split("-").reverse().join("/")}`,
    [range],
  );

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
    <main className="mx-auto w-full max-w-[1500px] px-4 py-6 sm:px-6 lg:py-8">
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
          <div className="flex flex-wrap items-center gap-3 text-xs text-white/70">
            <span>{dateSummary}</span>
            {lastUpdatedAt && (
              <span>
                Cập nhật {lastUpdatedAt.toLocaleTimeString(localeTag, {
                  hour: "2-digit",
                  minute: "2-digit",
                })}
              </span>
            )}
            <button
              type="button"
              onClick={refresh}
              disabled={refreshing}
              className="min-h-11 rounded-lg border border-white/20 px-4 font-bold text-white transition hover:bg-white/10 disabled:opacity-50"
            >
              {refreshing ? "Đang cập nhật…" : "Cập nhật"}
            </button>
          </div>
        </div>
      </header>

      <nav
        aria-label="Khu vực tài chính"
        className={`${panelClass} mt-5 grid gap-2 p-2 sm:grid-cols-2`}
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
          <section className={`${panelClass} mt-5 p-4 sm:p-5`}>
            <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_auto] lg:items-end">
              <div className="grid gap-3 sm:grid-cols-2">
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
                <label className="text-xs font-bold text-[#0F2A43]">
                  Hiển thị theo
                  <select
                    value={granularity}
                    onChange={(event) =>
                      setGranularity(event.target.value as StatisticsGranularity)}
                    className="mt-2 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20"
                  >
                    <option value="day">Theo ngày</option>
                    <option value="week">Theo tuần</option>
                    <option value="month">Theo tháng</option>
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
                <div className="border-b border-[#0F2A43]/10 px-5 py-4 sm:px-6">
                  <p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#B8944F]">
                    Theo thời gian
                  </p>
                  <h2 className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">
                    Thu, hoàn tiền và doanh thu thực nhận
                  </h2>
                </div>
                <div className="space-y-3 px-4 py-4 md:hidden">
                  {report.periods.map((point) => (
                    <article
                      key={point.period}
                      className="rounded-xl border border-[#0F2A43]/10 bg-[#F8F6F0] p-4"
                    >
                      <div className="flex items-start justify-between gap-3">
                        <p className="font-bold text-[#0F2A43]">
                          {reportPeriodLabel(
                            point.period,
                            point.periodEndExclusive,
                            granularity,
                            localeTag,
                          )}
                        </p>
                        <p
                          className={`text-right font-serif text-lg font-bold ${
                            point.amounts.netRevenue < 0
                              ? "text-rose-700"
                              : "text-[#0F2A43]"
                          }`}
                        >
                          {vnd(point.amounts.netRevenue, localeTag)}
                        </p>
                      </div>
                      <div className="mt-4 grid grid-cols-2 gap-3 text-xs">
                        <div>
                          <p className="text-[#66727C]">Thu tiền mặt</p>
                          <p className="mt-1 font-bold text-emerald-700">
                            {vnd(point.amounts.cashIncome, localeTag)}
                          </p>
                        </div>
                        <div>
                          <p className="text-[#66727C]">Thu chuyển khoản</p>
                          <p className="mt-1 font-bold text-emerald-700">
                            {vnd(point.amounts.transferIncome, localeTag)}
                          </p>
                        </div>
                        <div>
                          <p className="text-[#66727C]">Hoàn tiền mặt</p>
                          <p className="mt-1 font-bold text-rose-700">
                            {vnd(point.amounts.cashRefund, localeTag)}
                          </p>
                        </div>
                        <div>
                          <p className="text-[#66727C]">Hoàn chuyển khoản</p>
                          <p className="mt-1 font-bold text-rose-700">
                            {vnd(point.amounts.transferRefund, localeTag)}
                          </p>
                        </div>
                      </div>
                    </article>
                  ))}
                </div>
                <div className="lux-scrollbar hidden overflow-x-auto md:block">
                  <table className="min-w-[900px] w-full text-left text-sm">
                    <thead className="bg-[#F8F6F0] text-[10px] uppercase tracking-[0.13em] text-[#66727C]">
                      <tr>
                        <th className="px-5 py-3">Kỳ</th>
                        <th className="px-4 py-3 text-right">Thu bằng tiền mặt</th>
                        <th className="px-4 py-3 text-right">Thu bằng chuyển khoản</th>
                        <th className="px-4 py-3 text-right">Hoàn bằng tiền mặt</th>
                        <th className="px-4 py-3 text-right">Hoàn bằng chuyển khoản</th>
                        <th className="px-5 py-3 text-right">Doanh thu thực nhận</th>
                      </tr>
                    </thead>
                    <tbody>
                      {report.periods.map((point) => (
                        <tr key={point.period} className="border-t border-[#0F2A43]/8 transition hover:bg-[#F8F6F0]/70">
                          <td className="px-5 py-4 font-bold text-[#0F2A43]">
                            {reportPeriodLabel(
                              point.period,
                              point.periodEndExclusive,
                              granularity,
                              localeTag,
                            )}
                          </td>
                          <td className="px-4 py-4 text-right text-emerald-700">{vnd(point.amounts.cashIncome, localeTag)}</td>
                          <td className="px-4 py-4 text-right text-emerald-700">{vnd(point.amounts.transferIncome, localeTag)}</td>
                          <td className="px-4 py-4 text-right text-rose-700">{vnd(point.amounts.cashRefund, localeTag)}</td>
                          <td className="px-4 py-4 text-right text-rose-700">{vnd(point.amounts.transferRefund, localeTag)}</td>
                          <td className="px-5 py-4 text-right font-bold text-[#0F2A43]">{vnd(point.amounts.netRevenue, localeTag)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>

              <section className={`${panelClass} mt-5 overflow-hidden`}>
                <div className="flex flex-col gap-4 border-b border-[#0F2A43]/10 px-5 py-4 sm:flex-row sm:items-end sm:justify-between sm:px-6">
                  <div>
                    <p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#B8944F]">
                      Nguồn số liệu
                    </p>
                    <h2 className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">
                      Chi tiết theo đơn
                    </h2>
                  </div>
                  <div className="flex w-full max-w-lg gap-2">
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
                  <div className="divide-y divide-[#0F2A43]/8">
                    {reservations.content.map((item) => (
                      <article
                        key={item.reservationId}
                        className="grid gap-4 px-5 py-4 transition hover:bg-[#F8F6F0]/70 lg:grid-cols-[minmax(180px,1.2fr)_repeat(3,minmax(130px,0.8fr))_auto] lg:items-center sm:px-6"
                      >
                        <div className="min-w-0">
                          <Link
                            href={`/dashboard/reservations?reservationCode=${encodeURIComponent(item.reservationCode)}`}
                            className="font-bold text-[#0F2A43] hover:text-[#8E6B2E]"
                          >
                            {item.reservationCode}
                          </Link>
                          <p className="mt-1 text-xs text-[#66727C]">
                            {reservationStatusLabel(item.reservationStatus)}
                            {" · "}Phát sinh gần nhất{" "}
                            {new Date(item.lastMovementAtUtc).toLocaleString(localeTag, {
                              dateStyle: "short",
                              timeStyle: "short",
                              timeZone: "Asia/Ho_Chi_Minh",
                            })}
                          </p>
                        </div>
                        <div>
                          <p className="text-[10px] font-bold uppercase tracking-[0.12em] text-[#7A858E]">Đã thu</p>
                          <p className="mt-1 font-bold text-emerald-700">{vnd(item.amounts.totalIncome, localeTag)}</p>
                          <p className="mt-1 text-[11px] leading-5 text-[#66727C]">
                            Tiền mặt {vnd(item.amounts.cashIncome, localeTag)}
                            {" · "}Chuyển khoản {vnd(item.amounts.transferIncome, localeTag)}
                          </p>
                        </div>
                        <div>
                          <p className="text-[10px] font-bold uppercase tracking-[0.12em] text-[#7A858E]">Đã hoàn</p>
                          <p className="mt-1 font-bold text-rose-700">{vnd(item.amounts.totalRefund, localeTag)}</p>
                          <p className="mt-1 text-[11px] leading-5 text-[#66727C]">
                            Tiền mặt {vnd(item.amounts.cashRefund, localeTag)}
                            {" · "}Chuyển khoản {vnd(item.amounts.transferRefund, localeTag)}
                          </p>
                        </div>
                        <div>
                          <p className="text-[10px] font-bold uppercase tracking-[0.12em] text-[#7A858E]">Doanh thu thực nhận</p>
                          <p className="mt-1 font-bold text-[#0F2A43]">{vnd(item.amounts.netRevenue, localeTag)}</p>
                        </div>
                        <Link
                          href={`/dashboard/reservations?reservationCode=${encodeURIComponent(item.reservationCode)}`}
                          className="inline-flex min-h-10 items-center justify-center rounded-lg border border-[#0F2A43]/15 px-3 text-xs font-bold text-[#0F2A43] transition hover:bg-[#F1F0EA]"
                        >
                          Xem đơn
                        </Link>
                      </article>
                    ))}
                  </div>
                )}
                {reservations.totalPages > 1 && (
                  <div className="flex items-center justify-between border-t border-[#0F2A43]/10 px-5 py-4 text-sm">
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
            </>
          ) : null}
        </>
      )}
    </main>
  );
}
