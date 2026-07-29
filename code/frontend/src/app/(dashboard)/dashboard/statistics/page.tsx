"use client";

import Link from "next/link";
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import BusinessDayPanel from "@/components/dashboard/BusinessDayPanel";
import CashierShiftPanel from "@/components/dashboard/CashierShiftPanel";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { useDashboardRole } from "@/hooks/use-dashboard-role";
import { apiClient, cachedGet, getApiErrorMessage, getApiErrorStatus } from "@/lib/api";
import {
  apiData,
  type CashFlowPoint,
  type FinanceWorkspaceView,
  financeWorkspaceFromQuery,
  type LedgerPage,
  monthToDatePreset,
  type ReservationRevenuePage,
  type RevenuePoint,
  type RoomTypePerformance,
  type StatisticsGranularity,
  type StatisticsKpi,
  type StatisticsOverview,
  statisticsPreset,
  suggestedStatisticsGranularity,
} from "@/lib/business-statistics";

type PeriodFilter = { from: string; to: string };
type PeriodPreset = "7" | "30" | "month" | "custom";
type LedgerFilters = { eventType: string; provider: string; status: string; q: string };
type ReservationFilters = { q: string; status: string };
type ExportReport = "revenue" | "cash-flow" | "bookings" | "occupancy" | "room-types" | "reservations" | "ledger";

const initialPeriod = statisticsPreset(30);
const emptyLedger: LedgerPage = {
  content: [],
  page: 0,
  size: 25,
  totalElements: 0,
  totalPages: 0,
};

const emptyReservationRevenue: ReservationRevenuePage = {
  content: [],
  page: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
};

const sectionClass = "rounded-2xl border border-[#0F2A43]/10 bg-white shadow-[0_12px_34px_rgba(15,42,67,0.07)]";
const financeViews: FinanceWorkspaceView[] = ["overview", "cashier", "close"];

function ChangeBadge({ value, localeTag }: { value: number | null; localeTag: string }) {
  if (value == null) {
    return <span className="rounded-full bg-[#F1F0EA] px-2.5 py-1 text-[11px] font-bold text-[#66727C]">Mới</span>;
  }
  const positive = value >= 0;
  return (
    <span className={`rounded-full px-2.5 py-1 text-[11px] font-bold ${positive ? "bg-emerald-50 text-emerald-700" : "bg-rose-50 text-rose-700"}`}>
      {positive ? "↑" : "↓"} {Math.abs(value).toLocaleString(localeTag, { maximumFractionDigits: 1 })}%
    </span>
  );
}

function MetricCard({
  label,
  value,
  detail,
  kpi,
  status,
  localeTag,
}: {
  label: string;
  value: string;
  detail: string;
  kpi?: StatisticsKpi;
  status?: { label: string; tone: "neutral" | "good" | "warning" };
  localeTag: string;
}) {
  const statusClass = status?.tone === "good"
    ? "bg-emerald-50 text-emerald-700"
    : status?.tone === "warning"
      ? "bg-amber-100 text-amber-800"
      : "bg-[#F1F0EA] text-[#66727C]";
  return (
    <article className={`${sectionClass} min-w-0 p-5 transition duration-200 hover:-translate-y-0.5 hover:shadow-[0_16px_40px_rgba(15,42,67,0.11)]`}>
      <div className="flex items-start justify-between gap-3">
        <p className="text-[11px] font-extrabold uppercase tracking-[0.16em] text-[#66727C]">{label}</p>
        {kpi
          ? <ChangeBadge value={kpi.changePercent} localeTag={localeTag} />
          : status && <span className={`rounded-full px-2.5 py-1 text-[10px] font-bold ${statusClass}`}>{status.label}</span>}
      </div>
      <p className="mt-4 break-words font-serif text-2xl font-bold text-[#0F2A43] sm:text-3xl">{value}</p>
      <p className="mt-2 text-xs leading-5 text-[#66727C]">{detail}</p>
    </article>
  );
}

function LoadingPanel() {
  return <div className="h-72 animate-pulse rounded-2xl border border-[#0F2A43]/8 bg-gradient-to-r from-white via-[#F1F0EA] to-white bg-[length:200%_100%]" />;
}

export default function BusinessStatisticsPage() {
  const { localeTag, localize } = useLanguage();
  const { role, isAdmin } = useDashboardRole();
  const [activeView, setActiveView] = useState<FinanceWorkspaceView>("overview");
  const [periodPreset, setPeriodPreset] = useState<PeriodPreset>("30");
  const [draftPeriod, setDraftPeriod] = useState<PeriodFilter>(initialPeriod);
  const [period, setPeriod] = useState<PeriodFilter>(initialPeriod);
  const [granularity, setGranularity] = useState<StatisticsGranularity>("day");
  const [overview, setOverview] = useState<StatisticsOverview | null>(null);
  const [revenue, setRevenue] = useState<RevenuePoint[]>([]);
  const [cashFlow, setCashFlow] = useState<CashFlowPoint[]>([]);
  const cashProvider = "";
  const [roomTypes, setRoomTypes] = useState<RoomTypePerformance[]>([]);
  const [reservationRevenue, setReservationRevenue] = useState<ReservationRevenuePage>(emptyReservationRevenue);
  const [reservationDraftFilters, setReservationDraftFilters] = useState<ReservationFilters>({ q: "", status: "" });
  const [reservationFilters, setReservationFilters] = useState<ReservationFilters>({ q: "", status: "" });
  const [reservationPage, setReservationPage] = useState(0);
  const [ledger, setLedger] = useState<LedgerPage>(emptyLedger);
  const [ledgerFilters, setLedgerFilters] = useState<LedgerFilters>({ eventType: "", provider: "", status: "", q: "" });
  const [ledgerQueryDraft, setLedgerQueryDraft] = useState("");
  const [ledgerPage, setLedgerPage] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [isCashLoading, setIsCashLoading] = useState(true);
  const [isReservationLoading, setIsReservationLoading] = useState(true);
  const [isLedgerLoading, setIsLedgerLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isExporting, setIsExporting] = useState("");
  const [error, setError] = useState("");
  const [cashFlowError, setCashFlowError] = useState("");
  const [reservationError, setReservationError] = useState("");
  const [ledgerError, setLedgerError] = useState("");
  const [notice, setNotice] = useState("");
  const [selectedExportReport, setSelectedExportReport] = useState<ExportReport>("revenue");
  const [cashFlowEnabled, setCashFlowEnabled] = useState(false);
  const [reservationRevenueEnabled, setReservationRevenueEnabled] = useState(false);
  const [ledgerEnabled, setLedgerEnabled] = useState(false);
  const cashFlowSectionRef = useRef<HTMLElement | null>(null);
  const reservationSectionRef = useRef<HTMLDetailsElement | null>(null);
  const ledgerSectionRef = useRef<HTMLDetailsElement | null>(null);
  const summaryRequestRef = useRef(0);
  const cashFlowRequestRef = useRef(0);
  const reservationRequestRef = useRef(0);
  const ledgerRequestRef = useRef(0);

  useEffect(() => {
    const syncViewFromUrl = () => {
      const requested = new URLSearchParams(window.location.search).get("tab");
      setActiveView(financeWorkspaceFromQuery(requested));
    };
    syncViewFromUrl();
    window.addEventListener("popstate", syncViewFromUrl);
    return () => window.removeEventListener("popstate", syncViewFromUrl);
  }, []);

  const selectView = (view: FinanceWorkspaceView) => {
    setActiveView(view);
    const url = new URL(window.location.href);
    if (view === "overview") url.searchParams.delete("tab");
    else url.searchParams.set("tab", view);
    window.history.replaceState(window.history.state, "", `${url.pathname}${url.search}${url.hash}`);
  };

  const money = useMemo(() => new Intl.NumberFormat(localeTag, { style: "currency", currency: "VND", maximumFractionDigits: 0 }), [localeTag]);
  const number = useMemo(() => new Intl.NumberFormat(localeTag, { maximumFractionDigits: 1 }), [localeTag]);
  const granularityLabel = granularity === "day"
    ? localize("theo ngày", "by day")
    : granularity === "week"
      ? localize("theo tuần", "by week")
      : localize("theo tháng", "by month");
  const formatMoney = useCallback((value: number) => money.format(Number(value || 0)), [money]);
  const formatPercent = useCallback((value: number) => `${number.format(Number(value || 0))}%`, [number]);
  const formatPeriod = useCallback((value: string) => new Date(`${value}T00:00:00`).toLocaleDateString(localeTag, { day: "2-digit", month: "2-digit", ...(granularity === "month" ? { year: "numeric" } : {}) }), [granularity, localeTag]);
  const formatReservationPeriod = useCallback((start: string, endExclusive: string) => {
    const startDate = new Date(`${start}T00:00:00`);
    if (granularity === "month") {
      const month = String(startDate.getMonth() + 1).padStart(2, "0");
      const label = `${month}/${startDate.getFullYear()}`;
      return localize(`Tháng ${label}`, `Month ${label}`);
    }
    if (granularity === "week") {
      const endDate = new Date(`${endExclusive}T00:00:00`);
      endDate.setDate(endDate.getDate() - 1);
      const label = `${startDate.toLocaleDateString(localeTag, { day: "2-digit", month: "2-digit" })} – ${endDate.toLocaleDateString(localeTag, { day: "2-digit", month: "2-digit", year: "numeric" })}`;
      return localize(`Tuần ${label}`, `Week ${label}`);
    }
    const label = startDate.toLocaleDateString(localeTag, { day: "2-digit", month: "2-digit", year: "numeric" });
    return localize(`Ngày ${label}`, `Day ${label}`);
  }, [granularity, localeTag, localize]);
  const formatHotelDateTime = useCallback((value: string) => {
    const instant = new Date(value);
    return {
      date: instant.toLocaleDateString(localeTag, { timeZone: "Asia/Ho_Chi_Minh" }),
      time: instant.toLocaleTimeString(localeTag, { timeZone: "Asia/Ho_Chi_Minh", hour: "2-digit", minute: "2-digit", second: "2-digit" }),
    };
  }, [localeTag]);
  const formatHotelLocalDateTime = useCallback((value?: string) => {
    if (!value) return { date: "—", time: "" };
    const normalized = /(?:Z|[+-]\d{2}:\d{2})$/i.test(value)
      ? value
      : `${value.replace(" ", "T")}+07:00`;
    return formatHotelDateTime(normalized);
  }, [formatHotelDateTime]);
  const revenueTotals = useMemo(() => revenue.reduce((totals, point) => ({
    invoices: totals.invoices + Number(point.invoiceCount || 0),
    addOns: totals.addOns + Number(point.addOnServiceRevenue || 0),
    additionalFees: totals.additionalFees + Number(point.additionalFee || 0),
    lateFees: totals.lateFees + Number(point.lateCheckoutFee || 0),
  }), { invoices: 0, addOns: 0, additionalFees: 0, lateFees: 0 }), [revenue]);
  const cashFlowTotals = useMemo(() => cashFlow.reduce((totals, point) => ({
    unmatchedIn: totals.unmatchedIn + Number(point.unmatchedCashInEventCount || 0),
    unclassifiedOut: totals.unclassifiedOut + Number(point.unclassifiedCashOutEventCount || 0),
    unclassifiedOutAmount: totals.unclassifiedOutAmount + Number(point.unclassifiedCashOutflow || 0),
  }), { unmatchedIn: 0, unclassifiedOut: 0, unclassifiedOutAmount: 0 }), [cashFlow]);

  const loadSummary = useCallback(async (force = false) => {
    const requestId = ++summaryRequestRef.current;
    setIsLoading(true);
    setError("");
    const params = { ...period, granularity };
    try {
      const [overviewResponse, revenueResponse, roomTypeResponse] = await Promise.all([
        cachedGet<{ data: StatisticsOverview }>("/api/admin/statistics/overview", { ttlMs: 30_000, force, config: { params: period } }),
        cachedGet<{ data: RevenuePoint[] }>("/api/admin/statistics/revenue", { ttlMs: 30_000, force, config: { params } }),
        cachedGet<{ data: RoomTypePerformance[] }>("/api/admin/statistics/room-types", { ttlMs: 30_000, force, config: { params: period } }),
      ]);
      if (requestId !== summaryRequestRef.current) return;
      setOverview(apiData(overviewResponse));
      setRevenue(apiData(revenueResponse));
      setRoomTypes(apiData(roomTypeResponse));
    } catch (requestError) {
      if (requestId !== summaryRequestRef.current) return;
      setError(getApiErrorStatus(requestError) === 403
        ? localize("Chỉ ADMIN được xem báo cáo tài chính.", "Only ADMIN can access financial reports.")
        : getApiErrorMessage(requestError, localize("Không thể tải báo cáo. Vui lòng thử lại.", "Unable to load reports. Please try again.")));
    } finally {
      if (requestId === summaryRequestRef.current) setIsLoading(false);
    }
  }, [granularity, localize, period]);

  const loadCashFlow = useCallback(async (force = false) => {
    const requestId = ++cashFlowRequestRef.current;
    setIsCashLoading(true);
    setCashFlowError("");
    try {
      const response = await cachedGet<{ data: CashFlowPoint[] }>("/api/admin/statistics/cash-flow", {
        ttlMs: 20_000,
        force,
        config: { params: { ...period, granularity, provider: cashProvider || undefined } },
      });
      if (requestId !== cashFlowRequestRef.current) return;
      setCashFlow(apiData(response));
    } catch (requestError) {
      if (requestId !== cashFlowRequestRef.current) return;
      setCashFlowError(getApiErrorMessage(requestError, localize("Không thể tải dòng tiền theo kênh.", "Unable to load cash flow by provider.")));
    } finally {
      if (requestId === cashFlowRequestRef.current) setIsCashLoading(false);
    }
  }, [cashProvider, granularity, localize, period]);

  const loadReservationRevenue = useCallback(async (force = false) => {
    const requestId = ++reservationRequestRef.current;
    setIsReservationLoading(true);
    setReservationError("");
    try {
      const response = await cachedGet<{ data: ReservationRevenuePage }>("/api/admin/statistics/reservations", {
        ttlMs: 20_000,
        force,
        config: {
          params: {
            ...period,
            granularity,
            q: reservationFilters.q || undefined,
            status: reservationFilters.status || undefined,
            page: reservationPage,
            size: 20,
          },
        },
      });
      if (requestId !== reservationRequestRef.current) return;
      setReservationRevenue(apiData(response));
    } catch (requestError) {
      if (requestId !== reservationRequestRef.current) return;
      setReservationError(getApiErrorMessage(requestError, localize("Không thể tải doanh thu theo đơn.", "Unable to load reservation revenue.")));
    } finally {
      if (requestId === reservationRequestRef.current) setIsReservationLoading(false);
    }
  }, [granularity, localize, period, reservationFilters, reservationPage]);

  const loadLedger = useCallback(async (force = false) => {
    const requestId = ++ledgerRequestRef.current;
    setIsLedgerLoading(true);
    setLedgerError("");
    try {
      const response = await cachedGet<{ data: LedgerPage }>("/api/admin/statistics/ledger", {
        ttlMs: 20_000,
        force,
        config: { params: { ...period, ...ledgerFilters, page: ledgerPage, size: 25 } },
      });
      if (requestId !== ledgerRequestRef.current) return;
      setLedger(apiData(response));
    } catch (requestError) {
      if (requestId !== ledgerRequestRef.current) return;
      setLedgerError(getApiErrorMessage(requestError, localize("Không thể tải sổ giao dịch.", "Unable to load the transaction ledger.")));
    } finally {
      if (requestId === ledgerRequestRef.current) setIsLedgerLoading(false);
    }
  }, [ledgerFilters, ledgerPage, localize, period]);

  useEffect(() => {
    if (isAdmin && activeView === "overview") void loadSummary();
  }, [activeView, isAdmin, loadSummary]);

  useEffect(() => {
    if (!isAdmin || activeView !== "overview") return;
    const refreshVisibleData = () => {
      if (document.visibilityState !== "visible") return;
      void loadSummary(true);
      if (cashFlowEnabled) void loadCashFlow(true);
      if (reservationRevenueEnabled) void loadReservationRevenue(true);
      if (ledgerEnabled) void loadLedger(true);
    };
    const intervalId = window.setInterval(refreshVisibleData, 60_000);
    window.addEventListener("focus", refreshVisibleData);
    return () => {
      window.clearInterval(intervalId);
      window.removeEventListener("focus", refreshVisibleData);
    };
  }, [
    activeView,
    cashFlowEnabled,
    isAdmin,
    ledgerEnabled,
    loadCashFlow,
    loadLedger,
    loadReservationRevenue,
    loadSummary,
    reservationRevenueEnabled,
  ]);

  useEffect(() => {
    if (isAdmin && ledgerEnabled) void loadLedger();
  }, [isAdmin, ledgerEnabled, loadLedger]);

  useEffect(() => {
    if (isAdmin && cashFlowEnabled) void loadCashFlow();
  }, [cashFlowEnabled, isAdmin, loadCashFlow]);

  useEffect(() => {
    if (isAdmin && reservationRevenueEnabled) void loadReservationRevenue();
  }, [isAdmin, loadReservationRevenue, reservationRevenueEnabled]);

  useEffect(() => {
    if (!isAdmin || activeView !== "overview" || !overview) return;
    if (typeof IntersectionObserver === "undefined") {
      setCashFlowEnabled(true);
      setReservationRevenueEnabled(true);
      setLedgerEnabled(true);
      return;
    }
    const targets = [
      [cashFlowSectionRef.current, setCashFlowEnabled],
      [reservationSectionRef.current, setReservationRevenueEnabled],
      [ledgerSectionRef.current, setLedgerEnabled],
    ] as const;
    const observer = new IntersectionObserver((entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        const match = targets.find(([element]) => element === entry.target);
        if (match) match[1](true);
        observer.unobserve(entry.target);
      });
    }, { rootMargin: "320px 0px" });
    targets.forEach(([element]) => { if (element) observer.observe(element); });
    return () => observer.disconnect();
  }, [activeView, isAdmin, overview]);

  const applyPeriod = () => {
    if (!draftPeriod.from || !draftPeriod.to || draftPeriod.from > draftPeriod.to) {
      setNotice(localize("Khoảng ngày không hợp lệ: ngày bắt đầu phải trước hoặc bằng ngày kết thúc.", "Invalid date range: the start date must be on or before the end date."));
      return;
    }
    setNotice("");
    setLedgerPage(0);
    setReservationPage(0);
    setPeriod(draftPeriod);
    setPeriodPreset("custom");
    setGranularity(suggestedStatisticsGranularity(draftPeriod));
  };

  const applyPreset = (preset: Exclude<PeriodPreset, "custom">) => {
    const next = preset === "7"
      ? statisticsPreset(7)
      : preset === "30"
        ? statisticsPreset(30)
        : monthToDatePreset();
    setPeriodPreset(preset);
    setDraftPeriod(next);
    setPeriod(next);
    setGranularity(suggestedStatisticsGranularity(next));
    setLedgerPage(0);
    setReservationPage(0);
    setNotice("");
  };

  const refresh = () => {
    setIsRefreshing(true);
    const requests: Promise<void>[] = [loadSummary(true)];
    if (cashFlowEnabled) requests.push(loadCashFlow(true));
    if (reservationRevenueEnabled) requests.push(loadReservationRevenue(true));
    if (ledgerEnabled) requests.push(loadLedger(true));
    void Promise.all(requests).finally(() => setIsRefreshing(false));
  };

  const updateLedgerFilter = (key: keyof LedgerFilters, value: string) => {
    setLedgerPage(0);
    setLedgerFilters((current) => ({ ...current, [key]: value }));
  };

  const applyReservationFilters = () => {
    setReservationPage(0);
    setReservationFilters({
      q: reservationDraftFilters.q.trim(),
      status: reservationDraftFilters.status,
    });
  };

  const resetReservationFilters = () => {
    const empty = { q: "", status: "" };
    setReservationDraftFilters(empty);
    setReservationFilters(empty);
    setReservationPage(0);
  };

  const applyLedgerQuery = () => {
    setLedgerPage(0);
    setLedgerFilters((current) => ({ ...current, q: ledgerQueryDraft.trim() }));
  };

  const openLedgerDetails = () => {
    setLedgerEnabled(true);
    if (!ledgerSectionRef.current) return;
    ledgerSectionRef.current.open = true;
    window.requestAnimationFrame(() => ledgerSectionRef.current?.scrollIntoView({ behavior: "smooth", block: "start" }));
  };

  const downloadReport = async (report: ExportReport) => {
    setIsExporting(report);
    setNotice("");
    try {
      const response = await apiClient.get<Blob>("/api/admin/statistics/export", {
        params: {
          report,
          ...period,
          granularity,
          ...(report === "ledger" ? ledgerFilters : {}),
          ...(report === "reservations" ? reservationFilters : {}),
          ...(report === "cash-flow" ? { provider: cashProvider || undefined } : {}),
        },
        responseType: "blob",
      });
      const disposition = String(response.headers["content-disposition"] || "");
      const matchedName = disposition.match(/filename\*?=(?:UTF-8''|\")?([^";]+)/i)?.[1];
      const fileName = matchedName ? decodeURIComponent(matchedName.replace(/\"/g, "")) : `luxury-hotel-${report}.csv`;
      const objectUrl = URL.createObjectURL(response.data);
      const anchor = document.createElement("a");
      anchor.href = objectUrl;
      anchor.download = fileName;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(objectUrl);
      setNotice(response.headers["x-export-truncated"] === "true"
        ? localize("Đã xuất 10.000 dòng đầu. Hãy thu hẹp khoảng ngày để lấy đầy đủ.", "The first 10,000 rows were exported. Narrow the date range for a complete export.")
        : localize("Đã tải báo cáo CSV.", "CSV report downloaded."));
    } catch (requestError) {
      setNotice(getApiErrorMessage(requestError, localize("Xuất báo cáo thất bại.", "Report export failed.")));
    } finally {
      setIsExporting("");
    }
  };

  if (role && !isAdmin) {
    return (
      <main className="mx-auto flex min-h-[70vh] max-w-3xl items-center px-5 py-12">
        <section className={`${sectionClass} w-full p-8 text-center`}>
          <span className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-[#F1F0EA] text-2xl text-[#B8944F]">$</span>
          <h1 className="mt-5 font-serif text-3xl font-bold text-[#0F2A43]">{localize("Báo cáo tài chính chỉ dành cho ADMIN", "Financial reports are ADMIN-only")}</h1>
          <p className="mx-auto mt-3 max-w-xl text-sm leading-6 text-[#66727C]">{localize("Nhân viên vận hành vẫn xem được chỉ số nhận/trả phòng hôm nay tại Tổng quan, nhưng không có quyền đọc doanh thu.", "Operations staff can still see today's arrival and departure metrics on Overview, without financial access.")}</p>
          <Link href="/dashboard" className="mt-6 inline-flex min-h-11 items-center rounded-lg bg-[#0F2A43] px-5 font-bold text-white transition hover:bg-[#173B5B] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">{localize("Về Tổng quan", "Back to Overview")}</Link>
        </section>
      </main>
    );
  }

  const exportLabels: Record<ExportReport, string> = {
    revenue: localize("doanh thu", "revenue"),
    "cash-flow": localize("dòng tiền", "cash flow"),
    bookings: localize("đặt phòng", "bookings"),
    occupancy: localize("công suất", "occupancy"),
    "room-types": localize("hạng phòng", "room types"),
    reservations: localize("doanh thu theo đơn", "reservation revenue"),
    ledger: localize("sổ giao dịch", "ledger"),
  };

  const kpiCards = overview ? [
    { label: localize("Doanh thu ghi nhận", "Recognized revenue"), value: formatMoney(overview.recognizedRevenue.current), detail: localize("Theo hóa đơn bất biến khi checkout", "From immutable checkout invoices"), kpi: overview.recognizedRevenue },
    {
      label: localize("Dòng tiền ròng", "Net cash flow"),
      value: formatMoney(overview.netCashFlow),
      detail: `${localize("Đã thu", "Received")} ${formatMoney(overview.grossCashInflow)} · ${localize("đã hoàn", "refunded")} ${formatMoney(overview.refundOutflow)}`,
      status: { label: localize("Thu − hoàn", "In − out"), tone: "neutral" as const },
    },
    {
      label: localize("Còn cần thu", "Outstanding"),
      value: formatMoney(overview.outstandingReceivables),
      detail: localize("Công nợ của các đơn đang lưu trú trước checkout", "Active-stay receivables due before checkout"),
      status: overview.outstandingReceivables > 0
        ? { label: localize("Cần xử lý", "Action needed"), tone: "warning" as const }
        : { label: localize("Đã cân", "Balanced"), tone: "good" as const },
    },
    {
      label: localize("Công suất phòng", "Occupancy"),
      value: formatPercent(overview.occupancyRate.current),
      detail: `${number.format(overview.bookings.current)} ${localize("đơn được tạo trong kỳ", "bookings created in period")}`,
      kpi: overview.occupancyRate,
    },
  ] : [];

  return (
    <main className="min-h-screen bg-[#F1F0EA] px-4 py-6 text-[#0F2A43] sm:px-6 lg:px-8">
      <div className="mx-auto max-w-[1500px] space-y-6">
        <header className="overflow-hidden rounded-3xl bg-[#0F2A43] text-white shadow-[0_18px_48px_rgba(15,42,67,0.2)]">
          <div className="relative px-6 py-8 lg:px-10">
            <div className="pointer-events-none absolute -right-20 -top-28 h-72 w-72 rounded-full border border-[#B8944F]/25" />
            <div className="pointer-events-none absolute -right-8 -top-12 h-44 w-44 rounded-full border border-white/10" />
            <div className="relative">
              <p className="text-[11px] font-extrabold uppercase tracking-[0.24em] text-[#D8C398]">{localize("Trung tâm tài chính · ADMIN", "Finance center · ADMIN")}</p>
              <h1 className="mt-3 max-w-3xl font-serif text-3xl font-bold leading-tight sm:text-4xl lg:text-5xl">{localize("Một nơi để theo dõi tiền và chốt vận hành.", "One place to monitor money and close operations.")}</h1>
              <p className="mt-4 max-w-3xl text-sm leading-6 text-white/72">{localize("Hệ thống tự tổng hợp doanh thu, dòng tiền và journal. Màn hình chính chỉ nêu số quan trọng và việc cần xử lý; dữ liệu chi tiết mở khi cần tra cứu.", "Revenue, cash flow, and journal entries are summarized automatically. The main screen shows only essential figures and required actions; details open on demand.")}</p>
            </div>
          </div>
        </header>

        <nav aria-label={localize("Khu vực tài chính", "Finance sections")} className={`${sectionClass} grid gap-2 p-2 sm:grid-cols-3`}>
          {financeViews.map((view) => {
            const label = view === "overview"
              ? localize("Tổng quan tài chính", "Finance overview")
              : view === "cashier"
                ? localize("Ca thu ngân", "Cashier shift")
                : localize("Khóa ngày", "Close business day");
            const description = view === "overview"
              ? localize("Chỉ số quan trọng và ngoại lệ", "Essential metrics and exceptions")
              : view === "cashier"
                ? localize("Tiền mặt hệ thống tính và thực đếm", "System cash and physical count")
                : localize("Kiểm tra tự động rồi tạo snapshot", "Automated checks, then snapshot");
            return <button key={view} type="button" onClick={() => selectView(view)} aria-current={activeView === view ? "page" : undefined} className={`min-h-16 rounded-xl px-4 py-3 text-left transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] ${activeView === view ? "bg-[#0F2A43] text-white shadow-md" : "text-[#0F2A43] hover:bg-[#F8F3E8]"}`}><span className="block text-sm font-extrabold">{label}</span><span className={`mt-1 block text-[11px] ${activeView === view ? "text-white/65" : "text-[#66727C]"}`}>{description}</span></button>;
          })}
        </nav>

        <p className="rounded-xl border border-[#B8944F]/20 bg-[#F8F3E8] px-4 py-3 text-center text-[11px] font-semibold leading-5 text-[#52616D]">{localize("Payment, refund và invoice được ghi journal tự động. Không nhập lại doanh thu hay số journal; chỉ xác nhận tiền mặt thực tế và khóa ngày khi hệ thống báo sẵn sàng.", "Payments, refunds, and invoices are journaled automatically. Revenue and journal totals are never re-entered; only physical cash and a ready day close require confirmation.")}</p>

        {activeView === "overview" && <>
        <section className={`${sectionClass} p-4 sm:p-5`} aria-label={localize("Bộ lọc báo cáo", "Report filters")}>
          <div className="grid gap-3 lg:grid-cols-[220px_minmax(0,1fr)_auto] lg:items-end">
            <label className="grid gap-1.5 text-xs font-bold text-[#52616D]">
              {localize("Khoảng báo cáo", "Reporting period")}
              <select value={periodPreset} onChange={(event) => {
                const value = event.target.value as PeriodPreset;
                if (value === "custom") setPeriodPreset(value);
                else applyPreset(value);
              }} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-[#FBFAF6] px-3 text-sm text-[#0F2A43] outline-none transition focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20">
                <option value="7">{localize("7 ngày gần nhất", "Last 7 days")}</option>
                <option value="30">{localize("30 ngày gần nhất", "Last 30 days")}</option>
                <option value="month">{localize("Tháng này", "This month")}</option>
                <option value="custom">{localize("Tùy chọn ngày", "Custom dates")}</option>
              </select>
            </label>
            {periodPreset === "custom" ? <div className="grid gap-3 sm:grid-cols-[1fr_1fr_auto]">
              <label className="grid gap-1.5 text-xs font-bold text-[#52616D]">{localize("Từ ngày", "From")}<input type="date" value={draftPeriod.from} max={draftPeriod.to} onChange={(event) => setDraftPeriod((current) => ({ ...current, from: event.target.value }))} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-[#FBFAF6] px-3 text-sm text-[#0F2A43] outline-none transition focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20" /></label>
              <label className="grid gap-1.5 text-xs font-bold text-[#52616D]">{localize("Đến ngày", "To")}<input type="date" value={draftPeriod.to} min={draftPeriod.from} onChange={(event) => setDraftPeriod((current) => ({ ...current, to: event.target.value }))} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-[#FBFAF6] px-3 text-sm text-[#0F2A43] outline-none transition focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20" /></label>
              <button type="button" onClick={applyPeriod} className="min-h-11 self-end rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#173B5B] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">{localize("Áp dụng", "Apply")}</button>
            </div> : <p className="self-end pb-2 text-xs font-semibold leading-5 text-[#66727C]">{period.from} → {period.to}<span className="ml-2 rounded-full bg-[#F1F0EA] px-2.5 py-1 text-[10px] font-bold text-[#52616D]">{localize("Tự động gom", "Auto-grouped")} {granularityLabel}</span></p>}
            <button type="button" disabled={isRefreshing} onClick={refresh} className="inline-flex min-h-11 items-center justify-center gap-2 rounded-lg border border-[#0F2A43]/12 px-4 text-xs font-bold transition hover:border-[#B8944F] hover:bg-[#F8F3E8] disabled:opacity-50"><span className={isRefreshing ? "animate-spin" : ""}>↻</span>{localize("Làm mới", "Refresh")}</button>
          </div>
          {notice && <p role="status" className="mt-3 rounded-lg border border-[#B8944F]/30 bg-[#FFF8E7] px-4 py-3 text-xs font-semibold text-[#765A21]">{notice}</p>}
          <details className="group mt-3 border-t border-[#0F2A43]/8 pt-3">
            <summary className="inline-flex min-h-11 cursor-pointer list-none items-center gap-2 rounded-lg px-2 text-xs font-bold text-[#52616D] transition hover:bg-[#F8F3E8] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">
              {localize("Xuất dữ liệu khi cần", "Export data on demand")}
              <span aria-hidden="true" className="transition group-open:rotate-180">⌄</span>
            </summary>
            <div className="mt-2 grid max-w-xl gap-2 rounded-xl bg-[#F8F6F0] p-3 sm:grid-cols-[minmax(0,1fr)_auto]">
              <label className="grid gap-1 text-[10px] font-extrabold uppercase tracking-[0.12em] text-[#66727C]">
                {localize("Loại báo cáo CSV", "CSV report type")}
                <select value={selectedExportReport} onChange={(event) => setSelectedExportReport(event.target.value as ExportReport)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-xs font-bold normal-case tracking-normal text-[#0F2A43] outline-none transition focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20">
                  {(Object.keys(exportLabels) as ExportReport[]).map((report) => <option key={report} value={report}>{exportLabels[report]}</option>)}
                </select>
              </label>
              <button type="button" disabled={Boolean(isExporting)} onClick={() => void downloadReport(selectedExportReport)} className="inline-flex min-h-11 self-end items-center justify-center gap-2 rounded-lg bg-[#0F2A43] px-5 text-xs font-extrabold text-white transition hover:bg-[#173B5B] disabled:cursor-not-allowed disabled:opacity-55 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">
                {Boolean(isExporting) && <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-white/30 border-t-white" />}
                {localize("Tải CSV", "Download CSV")}
              </button>
            </div>
          </details>
        </section>

        {error && <div role="alert" className="rounded-xl border border-rose-200 bg-rose-50 px-5 py-4 text-sm font-semibold text-rose-800">{error}</div>}

        {isLoading ? <><div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">{Array.from({ length: 4 }, (_, index) => <div key={index} className="h-40 animate-pulse rounded-2xl bg-white" />)}</div><LoadingPanel /></> : overview && <>
          <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4" aria-label={localize("Chỉ số chính", "Key metrics")}>
            {kpiCards.map((card) => <MetricCard key={card.label} {...card} localeTag={localeTag} />)}
          </section>

          {(overview.dataQuality.warnings.length > 0 || overview.dataQuality.paymentCompleteness !== "CANONICAL") && (
            <section className="rounded-2xl border border-amber-200 bg-amber-50/80 p-5" aria-labelledby="data-quality-title">
              <div className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
                <div><p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-amber-700">{localize("Chất lượng dữ liệu", "Data quality")}</p><h2 id="data-quality-title" className="mt-1 font-serif text-xl font-bold text-[#0F2A43]">{localize("Báo cáo minh bạch phần dữ liệu ước tính", "Estimated data is explicitly disclosed")}</h2></div>
                <div className="flex flex-wrap gap-2"><span className="rounded-full border border-amber-300 bg-white px-3 py-1 text-[10px] font-bold text-amber-800">Payment: {overview.dataQuality.paymentCompleteness}</span><span className="rounded-full border border-amber-300 bg-white px-3 py-1 text-[10px] font-bold text-amber-800">Occupancy: {overview.dataQuality.occupancyAccuracy}</span></div>
              </div>
              <ul className="mt-4 grid gap-2 text-xs leading-5 text-amber-900 md:grid-cols-2">{overview.dataQuality.warnings.map((warning) => <li key={warning} className="flex gap-2"><span aria-hidden="true">•</span><span>{warning}</span></li>)}</ul>
              {overview.dataQuality.legacyUnreconciledPaymentCount > 0 && <p className="mt-3 text-xs font-bold text-amber-900">{localize("Giao dịch legacy tách riêng", "Legacy transactions kept separate")}: {overview.dataQuality.legacyUnreconciledPaymentCount} · {formatMoney(overview.dataQuality.legacyUnreconciledPaymentAmount)}</p>}
              {overview.dataQuality.unmatchedCashInEventCount > 0 && <p className="mt-2 text-xs font-bold text-amber-900">{localize("Tiền vào chưa ghép payment", "Unmatched incoming cash")}: {overview.dataQuality.unmatchedCashInEventCount} · {formatMoney(overview.dataQuality.unmatchedCashInAmount)}</p>}
              {overview.dataQuality.unclassifiedCashOutEventCount > 0 && <p className="mt-2 text-xs font-bold text-amber-900">{localize("Tiền ra chưa ghép refund", "Unclassified outgoing cash")}: {overview.dataQuality.unclassifiedCashOutEventCount} · {formatMoney(overview.dataQuality.unclassifiedCashOutAmount)}</p>}
            </section>
          )}

          <section className="grid gap-6 xl:grid-cols-[minmax(0,1.65fr)_minmax(310px,0.75fr)]">
            <article ref={cashFlowSectionRef} className={`${sectionClass} p-5 sm:p-6`}>
              <div className="mb-5 flex items-start justify-between gap-4"><div><p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#B8944F]">{localize("Doanh thu", "Revenue")}</p><h2 className="mt-1 font-serif text-2xl font-bold">{localize("Doanh thu theo kỳ", "Revenue by period")}</h2><p className="mt-2 text-xs leading-5 text-[#66727C]">{localize("Danh sách ngắn thay cho biểu đồ để đọc nhanh và đối chiếu chính xác.", "A compact list replaces the chart for faster, exact review.")}</p></div><span className="rounded-full bg-[#F1F0EA] px-3 py-1 text-[10px] font-bold text-[#66727C]">{period.from} → {period.to}</span></div>
              <div className="overflow-hidden rounded-xl border border-[#0F2A43]/8">
                {revenue.length ? revenue.slice(-6).reverse().map((point) => <div key={point.period} className="grid min-h-13 grid-cols-[minmax(0,1fr)_auto] items-center gap-4 border-b border-[#0F2A43]/7 px-4 py-3 last:border-b-0">
                  <div><p className="text-sm font-bold text-[#0F2A43]">{formatPeriod(point.period)}</p><p className="mt-1 text-[10px] text-[#66727C]">{number.format(point.invoiceCount)} {localize("hóa đơn", "invoices")}</p></div>
                  <p className="text-right font-serif text-lg font-bold text-[#0F2A43]">{formatMoney(point.recognizedRevenue)}</p>
                </div>) : <p className="px-5 py-10 text-center text-sm text-[#66727C]">{localize("Chưa có hóa đơn checkout trong kỳ.", "No checkout invoices in this period.")}</p>}
              </div>
              <div className="mt-4 grid grid-cols-2 gap-2 border-t border-[#0F2A43]/8 pt-4 sm:grid-cols-4">
                <p className="rounded-lg bg-[#F8F6F0] px-3 py-2 text-[10px] text-[#66727C]">{localize("Hóa đơn", "Invoices")}<strong className="mt-1 block text-sm text-[#0F2A43]">{number.format(revenueTotals.invoices)}</strong></p>
                <p className="rounded-lg bg-[#F8F6F0] px-3 py-2 text-[10px] text-[#66727C]">{localize("Dịch vụ", "Add-ons")}<strong className="mt-1 block text-sm text-[#0F2A43]">{formatMoney(revenueTotals.addOns)}</strong></p>
                <p className="rounded-lg bg-[#F8F6F0] px-3 py-2 text-[10px] text-[#66727C]">{localize("Phụ phí", "Additional fees")}<strong className="mt-1 block text-sm text-[#0F2A43]">{formatMoney(revenueTotals.additionalFees)}</strong></p>
                <p className="rounded-lg bg-[#F8F6F0] px-3 py-2 text-[10px] text-[#66727C]">{localize("Phí trả muộn", "Late fees")}<strong className="mt-1 block text-sm text-[#0F2A43]">{formatMoney(revenueTotals.lateFees)}</strong></p>
              </div>
            </article>
            <article className={`${sectionClass} p-5 sm:p-6`}>
              <div><p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#B8944F]">{localize("Việc cần chú ý", "Attention needed")}</p><h2 className="mt-1 font-serif text-2xl font-bold">{localize("Ngoại lệ dòng tiền", "Cash-flow exceptions")}</h2><p className="mt-2 text-xs leading-5 text-[#66727C]">{localize("Chỉ hiện khoản chưa ghép hoặc chưa phân loại; giao dịch bình thường không làm nhiễu màn hình.", "Only unmatched or unclassified money appears here; normal transactions stay out of the way.")}</p></div>
              {cashFlowError ? <p role="alert" className="mt-4 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-semibold text-rose-800">{cashFlowError}</p> : isCashLoading ? <div className="mt-4 h-36 animate-pulse rounded-xl bg-[#F1F0EA]" /> : cashFlowTotals.unmatchedIn || cashFlowTotals.unclassifiedOut ? <dl className="mt-5 grid gap-3 text-sm">
                <div className="flex items-center justify-between gap-4 border-b border-[#0F2A43]/8 pb-3"><dt className="text-[#66727C]">{localize("Tiền vào chưa ghép", "Unmatched incoming")}</dt><dd className="text-right font-black text-amber-700">{number.format(cashFlowTotals.unmatchedIn)}<span className="block text-[10px] font-semibold">{formatMoney(overview.dataQuality.unmatchedCashInAmount)}</span></dd></div>
                <div className="flex items-center justify-between gap-4"><dt className="text-[#66727C]">{localize("Tiền ra chưa phân loại", "Unclassified outgoing")}</dt><dd className="text-right font-black text-rose-700">{number.format(cashFlowTotals.unclassifiedOut)}<span className="block text-[10px] font-semibold">{formatMoney(cashFlowTotals.unclassifiedOutAmount)}</span></dd></div>
              </dl> : <div className="mt-5 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-5 text-sm leading-6 text-emerald-900"><strong className="block">{localize("Không có ngoại lệ dòng tiền", "No cash-flow exceptions")}</strong><span className="mt-1 block text-xs">{localize("Tiền vào và tiền ra trong kỳ đã có liên kết nghiệp vụ.", "Incoming and outgoing money in this period has a business link.")}</span></div>}
              <button type="button" onClick={openLedgerDetails} className="mt-5 inline-flex min-h-11 w-full items-center justify-center rounded-lg border border-[#0F2A43]/15 px-4 text-xs font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F8F3E8] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">{localize("Tra cứu sổ giao dịch khi cần", "Open transaction ledger if needed")}</button>
            </article>
          </section>

          <details className={`${sectionClass} group overflow-hidden`}>
            <summary className="flex min-h-16 cursor-pointer list-none items-center justify-between gap-4 px-5 py-4 transition hover:bg-[#F8F6F0] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[#B8944F] sm:px-6">
              <span><span className="block text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#B8944F]">{localize("Chỉ số bổ sung", "Additional metrics")}</span><span className="mt-1 block font-serif text-xl font-bold">{localize("ADR, RevPAR và số đơn", "ADR, RevPAR, and bookings")}</span></span>
              <span className="flex items-center gap-2 text-xs font-bold text-[#66727C]">{localize("Mở khi cần phân tích", "Open for analysis")} <i className="not-italic transition group-open:rotate-180">⌄</i></span>
            </summary>
            <dl className="grid gap-3 border-t border-[#0F2A43]/8 p-4 sm:grid-cols-3 sm:p-5">
              <div className="rounded-xl bg-[#F8F6F0] p-4"><dt className="text-[10px] font-extrabold uppercase tracking-[0.14em] text-[#66727C]">ADR</dt><dd className="mt-2 font-serif text-xl font-bold">{formatMoney(overview.adr.current)}</dd><p className="mt-1 text-[11px] text-[#66727C]">{localize("Doanh thu phòng trên đêm phòng quy đổi", "Room revenue per room-night equivalent")}</p></div>
              <div className="rounded-xl bg-[#F8F6F0] p-4"><dt className="text-[10px] font-extrabold uppercase tracking-[0.14em] text-[#66727C]">RevPAR</dt><dd className="mt-2 font-serif text-xl font-bold">{formatMoney(overview.revPar.current)}</dd><p className="mt-1 text-[11px] text-[#66727C]">{localize("Doanh thu phòng trên công suất sẵn có", "Room revenue per available capacity")}</p></div>
              <div className="rounded-xl bg-[#F8F6F0] p-4"><dt className="text-[10px] font-extrabold uppercase tracking-[0.14em] text-[#66727C]">{localize("Đơn được tạo", "Bookings created")}</dt><dd className="mt-2 font-serif text-xl font-bold">{number.format(overview.bookings.current)}</dd><p className="mt-1 text-[11px] text-[#66727C]">{localize("Theo ngày tạo đơn trong kỳ đã chọn", "By booking creation date in the selected period")}</p></div>
            </dl>
          </details>

          <details ref={reservationSectionRef} onToggle={(event) => { if (event.currentTarget.open) setReservationRevenueEnabled(true); }} className={`${sectionClass} group overflow-hidden`}>
            <summary className="flex min-h-16 cursor-pointer list-none items-center justify-between gap-4 px-5 py-4 transition hover:bg-[#F8F6F0] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[#B8944F] sm:px-6"><span><span className="block text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#B8944F]">{localize("Chi tiết khi cần", "Details on demand")}</span><span className="mt-1 block font-serif text-xl font-bold">{localize("Doanh thu theo từng đơn đặt phòng", "Revenue by reservation")}</span></span><span className="flex items-center gap-2 text-xs font-bold text-[#66727C]">{localize("Mở danh sách", "Open list")} <i className="not-italic transition group-open:rotate-180">⌄</i></span></summary>
          <section className="border-t border-[#0F2A43]/8" aria-labelledby="reservation-revenue-heading">
            <div className="border-b border-[#0F2A43]/8 px-5 py-5 sm:px-6">
              <div className="flex flex-col gap-4 xl:flex-row xl:items-end xl:justify-between">
                <div>
                  <p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#B8944F]">{localize("Doanh thu theo đơn", "Revenue by reservation")}</p>
                  <h2 id="reservation-revenue-heading" className="mt-1 font-serif text-2xl font-bold">{localize("Từ hóa đơn bất biến đến dòng tiền của từng đơn", "From immutable invoice to cash flow for each reservation")}</h2>
                  <p className="mt-2 max-w-3xl text-xs leading-5 text-[#66727C]">{localize("Mỗi đơn được xếp vào kỳ theo thời điểm phát hành hóa đơn tại khách sạn. Tiền thu và hoàn là tổng giao dịch đã hoàn tất gắn với chính đơn đó.", "Each reservation is assigned to a period by hotel-local invoice issue time. Cash received and refunds are completed transactions linked to that reservation.")}</p>
                </div>
                <form className="grid gap-2 sm:grid-cols-[minmax(220px,1fr)_180px_auto_auto]" onSubmit={(event) => { event.preventDefault(); applyReservationFilters(); }}>
                  <label className="grid gap-1 text-[10px] font-bold uppercase tracking-[0.1em] text-[#66727C]">
                    {localize("Mã đơn / hóa đơn", "Reservation / invoice")}
                    <input value={reservationDraftFilters.q} maxLength={100} onChange={(event) => setReservationDraftFilters((current) => ({ ...current, q: event.target.value }))} placeholder="RES-… / INV-…" className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-xs font-semibold normal-case tracking-normal text-[#0F2A43] outline-none transition focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20" />
                  </label>
                  <label className="grid gap-1 text-[10px] font-bold uppercase tracking-[0.1em] text-[#66727C]">
                    {localize("Trạng thái đơn", "Reservation status")}
                    <select value={reservationDraftFilters.status} onChange={(event) => setReservationDraftFilters((current) => ({ ...current, status: event.target.value }))} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-xs font-semibold normal-case tracking-normal text-[#0F2A43] outline-none transition focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20">
                      <option value="">{localize("Tất cả", "All")}</option>
                      <option value="CHECKED_OUT">{localize("Đã trả phòng", "Checked out")}</option>
                      <option value="CHECKED_IN">{localize("Đang lưu trú", "Checked in")}</option>
                      <option value="CANCELLED">{localize("Đã hủy", "Cancelled")}</option>
                      <option value="NO_SHOW">{localize("Không đến", "No show")}</option>
                    </select>
                  </label>
                  <button type="submit" className="min-h-11 self-end rounded-lg bg-[#0F2A43] px-4 text-xs font-bold text-white transition hover:bg-[#173B5B] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">{localize("Lọc", "Filter")}</button>
                  <button type="button" onClick={resetReservationFilters} className="min-h-11 self-end rounded-lg border border-[#0F2A43]/15 px-4 text-xs font-bold transition hover:border-[#B8944F] hover:bg-[#FBF8F1] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">{localize("Xóa lọc", "Clear")}</button>
                </form>
              </div>
            </div>
            {reservationError && <p role="alert" className="m-5 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-semibold text-rose-800">{reservationError}</p>}
            <div className="lux-scrollbar hidden overflow-x-auto lg:block">
              <table className="w-full min-w-[1380px] text-left text-sm">
                <thead className="bg-[#F8F6F0] text-[10px] uppercase tracking-[0.12em] text-[#66727C]"><tr><th className="px-6 py-4">{localize("Kỳ / hóa đơn", "Period / invoice")}</th><th className="px-4 py-4">{localize("Đơn / lưu trú", "Reservation / stay")}</th><th className="px-4 py-4">{localize("Trạng thái", "Status")}</th><th className="px-4 py-4">{localize("Cấu phần doanh thu", "Revenue breakdown")}</th><th className="px-4 py-4 text-right">{localize("Doanh thu", "Revenue")}</th><th className="px-4 py-4 text-right">{localize("Tiền đã thu", "Cash received")}</th><th className="px-4 py-4 text-right">{localize("Tiền hoàn", "Refunds")}</th><th className="px-6 py-4 text-right">{localize("Dòng tiền ròng", "Net cash")}</th></tr></thead>
                <tbody className="divide-y divide-[#0F2A43]/7">
                  {isReservationLoading ? <tr><td colSpan={8} className="px-6 py-12 text-center"><span className="inline-flex items-center gap-2 text-sm font-semibold text-[#66727C]"><i className="h-4 w-4 animate-spin rounded-full border-2 border-[#0F2A43]/20 border-t-[#0F2A43]" />{localize("Đang tải doanh thu theo đơn...", "Loading reservation revenue...")}</span></td></tr> : reservationRevenue.content.length ? reservationRevenue.content.map((entry) => {
                    const issued = formatHotelDateTime(entry.issuedAtUtc);
                    const arrival = formatHotelLocalDateTime(entry.actualCheckIn || entry.plannedCheckIn);
                    const departure = formatHotelLocalDateTime(entry.actualCheckOut || entry.plannedCheckOut);
                    const serviceAndFees = Number(entry.addOnServiceAmount) + Number(entry.additionalFee) + Number(entry.lateCheckoutFee) + Number(entry.otherRevenue);
                    return <tr key={entry.reservationId} className="align-top transition hover:bg-[#FBF8F1]">
                      <td className="whitespace-nowrap px-6 py-4"><p className="font-bold">{formatReservationPeriod(entry.period, entry.periodEndExclusive)}</p><p className="mt-1 text-xs text-[#66727C]">{issued.date} · {issued.time}</p><p className="mt-2 text-[10px] font-bold text-[#80632F]">{entry.invoiceNumber}</p></td>
                      <td className="px-4 py-4"><Link href={`/dashboard/reservations?reservationId=${entry.reservationId}`} className="font-extrabold text-[#0F2A43] underline decoration-[#B8944F]/50 underline-offset-4 transition hover:text-[#80632F]">{entry.reservationCode}</Link><p className="mt-2 whitespace-nowrap text-xs text-[#66727C]">{arrival.date} {arrival.time} →</p><p className="whitespace-nowrap text-xs text-[#66727C]">{departure.date} {departure.time}</p></td>
                      <td className="px-4 py-4"><span className="rounded-full bg-[#EAF2EE] px-2.5 py-1 text-[10px] font-bold text-emerald-800">{entry.reservationStatus}</span><p className="mt-2 text-[10px] font-bold text-[#66727C]">{entry.settlementStatus || "—"}</p><p className="mt-1 text-[10px] text-[#66727C]">{entry.pricingVersion || "—"}</p>{entry.dataQuality !== "CANONICAL" && <span className="mt-2 inline-flex rounded bg-amber-100 px-2 py-1 text-[10px] font-bold text-amber-800">{entry.dataQuality}</span>}</td>
                      <td className="px-4 py-4 text-xs"><p>{localize("Phòng", "Room")}: <strong>{formatMoney(Number(entry.roomCharge) + Number(entry.extraGuestCharge))}</strong></p><p className="mt-1 text-[#66727C]">{localize("Dịch vụ & phí", "Services & fees")}: {formatMoney(serviceAndFees)}</p><p className="mt-1 text-[#66727C]">{localize("Giảm / thuế", "Discount / tax")}: −{formatMoney(entry.discountAmount)} / +{formatMoney(entry.taxAmount)}</p></td>
                      <td className="whitespace-nowrap px-4 py-4 text-right font-black">{formatMoney(entry.recognizedRevenue)}</td>
                      <td className="whitespace-nowrap px-4 py-4 text-right"><p className="font-black text-emerald-700">{formatMoney(entry.grossCashInflow)}</p><p className="mt-1 text-[10px] text-[#66727C]">{localize("Chấp nhận", "Accepted")}: {formatMoney(entry.acceptedCashInflow)}</p></td>
                      <td className="whitespace-nowrap px-4 py-4 text-right font-black text-rose-700">{formatMoney(entry.refundOutflow)}</td>
                      <td className={`whitespace-nowrap px-6 py-4 text-right font-black ${entry.netCashFlow < 0 ? "text-rose-700" : "text-[#0F2A43]"}`}>{formatMoney(entry.netCashFlow)}</td>
                    </tr>;
                  }) : <tr><td colSpan={8} className="px-6 py-12 text-center text-sm text-[#66727C]">{localize("Không có hóa đơn phù hợp kỳ và bộ lọc.", "No invoices match the selected period and filters.")}</td></tr>}
                </tbody>
              </table>
            </div>
            <div className="grid gap-3 p-4 lg:hidden">
              {isReservationLoading ? <LoadingPanel /> : reservationRevenue.content.length ? reservationRevenue.content.map((entry) => {
                const issued = formatHotelDateTime(entry.issuedAtUtc);
                return <article key={entry.reservationId} className="rounded-xl border border-[#0F2A43]/10 bg-[#FBFAF6] p-4">
                  <div className="flex items-start justify-between gap-3"><div><Link href={`/dashboard/reservations?reservationId=${entry.reservationId}`} className="font-extrabold underline decoration-[#B8944F]/50 underline-offset-4">{entry.reservationCode}</Link><p className="mt-1 text-[10px] font-semibold text-[#80632F]">{formatReservationPeriod(entry.period, entry.periodEndExclusive)}</p><p className="mt-1 text-[10px] text-[#66727C]">{entry.invoiceNumber} · {issued.date}</p></div><span className="rounded-full bg-[#EAF2EE] px-2 py-1 text-[9px] font-bold text-emerald-800">{entry.reservationStatus}</span></div>
                  <dl className="mt-4 grid grid-cols-2 gap-3 text-xs"><div><dt className="text-[#66727C]">{localize("Doanh thu", "Revenue")}</dt><dd className="mt-1 font-black">{formatMoney(entry.recognizedRevenue)}</dd></div><div><dt className="text-[#66727C]">{localize("Tiền đã thu", "Cash received")}</dt><dd className="mt-1 font-black text-emerald-700">{formatMoney(entry.grossCashInflow)}</dd></div><div><dt className="text-[#66727C]">{localize("Tiền hoàn", "Refunds")}</dt><dd className="mt-1 font-black text-rose-700">{formatMoney(entry.refundOutflow)}</dd></div><div><dt className="text-[#66727C]">{localize("Dòng tiền ròng", "Net cash")}</dt><dd className="mt-1 font-black">{formatMoney(entry.netCashFlow)}</dd></div></dl>
                </article>;
              }) : <p className="py-8 text-center text-sm text-[#66727C]">{localize("Không có hóa đơn phù hợp kỳ và bộ lọc.", "No invoices match the selected period and filters.")}</p>}
            </div>
            <div className="flex flex-col gap-3 border-t border-[#0F2A43]/8 px-5 py-4 sm:flex-row sm:items-center sm:justify-between"><p className="text-xs text-[#66727C]">{localize("Tổng", "Total")}: <strong className="text-[#0F2A43]">{reservationRevenue.totalElements.toLocaleString(localeTag)}</strong> · {localize("Trang", "Page")} {reservationRevenue.totalPages ? reservationRevenue.page + 1 : 0}/{reservationRevenue.totalPages}</p><div className="flex gap-2"><button type="button" disabled={reservationRevenue.page <= 0 || isReservationLoading} onClick={() => setReservationPage((current) => Math.max(0, current - 1))} className="min-h-11 rounded-lg border border-[#0F2A43]/15 px-4 text-xs font-bold transition hover:border-[#B8944F] disabled:cursor-not-allowed disabled:opacity-40">← {localize("Trước", "Previous")}</button><button type="button" disabled={reservationRevenue.page + 1 >= reservationRevenue.totalPages || isReservationLoading} onClick={() => setReservationPage((current) => current + 1)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 px-4 text-xs font-bold transition hover:border-[#B8944F] disabled:cursor-not-allowed disabled:opacity-40">{localize("Sau", "Next")} →</button></div></div>
          </section>
          </details>

          <details className={`${sectionClass} group overflow-hidden`}>
            <summary className="flex min-h-16 cursor-pointer list-none items-center justify-between gap-4 px-5 py-4 transition hover:bg-[#F8F6F0] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[#B8944F] sm:px-6"><span><span className="block text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#B8944F]">{localize("Chi tiết mở rộng", "Expanded details")}</span><span className="mt-1 block font-serif text-xl font-bold">{localize("Hiệu quả theo hạng phòng", "Room-type performance")}</span></span><span className="flex items-center gap-2 text-xs font-bold text-[#66727C]">{localize("Xem bảng", "View table")} <i className="not-italic transition group-open:rotate-180">⌄</i></span></summary>
            <div className="lux-scrollbar overflow-x-auto">
              <table className="min-w-[980px] w-full text-left text-sm">
                <thead className="bg-[#F8F6F0] text-[10px] uppercase tracking-[0.12em] text-[#66727C]"><tr><th className="px-6 py-4">{localize("Loại phòng", "Room type")}</th><th className="px-4 py-4">{localize("Đơn", "Bookings")}</th><th className="px-4 py-4">{localize("Lượt phòng", "Rooms reserved")}</th><th className="px-4 py-4">{localize("Giờ phòng bán", "Sold room-hours")}</th><th className="px-4 py-4">{localize("Công suất", "Occupancy")}</th><th className="px-4 py-4">{localize("Doanh thu phòng", "Room revenue")}</th><th className="px-4 py-4">ADR</th><th className="px-4 py-4">RevPAR</th></tr></thead>
                <tbody className="divide-y divide-[#0F2A43]/7">{roomTypes.length ? roomTypes.map((roomType) => <tr key={roomType.roomTypeId} className="transition hover:bg-[#FBF8F1]"><td className="px-6 py-4"><p className="font-bold">{roomType.roomTypeName}</p><p className="mt-1 text-[10px] font-bold tracking-[0.1em] text-[#66727C]">{roomType.roomTypeCode}</p></td><td className="px-4 py-4 font-semibold">{roomType.bookingCount}</td><td className="px-4 py-4 font-semibold">{roomType.reservedRoomQuantity}</td><td className="px-4 py-4">{number.format(roomType.soldRoomHours)}</td><td className="px-4 py-4"><div className="flex items-center gap-3"><span className="h-2 w-24 overflow-hidden rounded-full bg-[#E8E6DE]"><i className="block h-full rounded-full bg-[#315B78]" style={{ width: `${Math.min(100, roomType.occupancyRate)}%` }} /></span><strong>{formatPercent(roomType.occupancyRate)}</strong></div></td><td className="px-4 py-4 font-bold">{formatMoney(roomType.recognizedRoomRevenue + roomType.extraGuestRevenue)}</td><td className="px-4 py-4 font-bold">{formatMoney(roomType.adr)}</td><td className="px-4 py-4 font-bold">{formatMoney(roomType.revPar)}</td></tr>) : <tr><td colSpan={8} className="px-6 py-12 text-center text-sm text-[#66727C]">{localize("Chưa có dữ liệu loại phòng trong kỳ.", "No room-type data in this period.")}</td></tr>}</tbody>
              </table>
            </div>
          </details>

          <details className={`${sectionClass} group overflow-hidden`}>
            <summary className="flex min-h-16 cursor-pointer list-none items-center justify-between gap-4 px-5 py-4 transition hover:bg-[#F8F6F0] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[#B8944F] sm:px-6"><span><span className="block text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#B8944F]">{localize("Kế toán mở rộng", "Extended accounting")}</span><span className="mt-1 block font-serif text-xl font-bold">{localize("Tiền cọc và nghĩa vụ hoàn", "Deposits and refund obligations")}</span></span><i className="not-italic text-[#66727C] transition group-open:rotate-180">⌄</i></summary>
          <section className="grid gap-4 border-t border-[#0F2A43]/8 p-4 md:grid-cols-3 sm:p-5">
            {[[localize("Tiền khách trả trước", "Customer deposits"), overview.customerDeposits, localize("Tiền đã thu trên các đơn chưa đóng", "Cash held on open reservations")], [localize("Nghĩa vụ hoàn tiền", "Refund payable"), overview.refundPayable, localize("Yêu cầu đang mở và phần nghĩa vụ chưa được refund hợp lệ bao phủ", "Open requests plus refund obligations not covered by a valid refund")], [localize("Tiền được chấp nhận", "Accepted cash"), overview.acceptedCashInflow, localize("Phần tiền khớp nghĩa vụ, không gồm phần dư", "Amount matched to obligations, excluding excess")]].map(([label, value, detail]) => <article key={String(label)} className={`${sectionClass} p-5`}><p className="text-[10px] font-extrabold uppercase tracking-[0.16em] text-[#66727C]">{label}</p><p className="mt-3 text-2xl font-black">{formatMoney(Number(value))}</p><p className="mt-2 text-xs leading-5 text-[#66727C]">{detail}</p></article>)}
          </section>
          </details>
        </>}

        <details ref={ledgerSectionRef} onToggle={(event) => { if (event.currentTarget.open) setLedgerEnabled(true); }} className={`${sectionClass} group overflow-hidden`} aria-labelledby="ledger-heading">
          <summary className="flex min-h-16 cursor-pointer list-none items-center justify-between gap-4 px-5 py-4 transition hover:bg-[#F8F6F0] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[#B8944F] sm:px-6"><span><span className="block text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#B8944F]">{localize("Tra cứu nâng cao", "Advanced lookup")}</span><span id="ledger-heading" className="mt-1 block font-serif text-xl font-bold">{localize("Sổ giao dịch chỉ đọc", "Read-only transaction ledger")}</span></span><span className="flex items-center gap-2 text-xs font-bold text-[#66727C]">{localize("Mở khi cần đối soát", "Open for reconciliation")} <i className="not-italic transition group-open:rotate-180">⌄</i></span></summary>
          <div className="border-b border-[#0F2A43]/8 px-5 py-5 sm:px-6">
            <div className="flex flex-col gap-4 xl:flex-row xl:items-end xl:justify-between">
              <p className="max-w-xl text-xs leading-5 text-[#66727C]">{localize("Dùng khi cần truy vết một mã đơn, giao dịch hoặc khoản tiền chưa ghép. Đây không phải màn hình làm việc hằng ngày.", "Use this to trace a reservation, transaction, or unmatched amount. It is not a daily workspace.")}</p>
              <form className="grid gap-2 sm:grid-cols-2 xl:grid-cols-[220px_180px_160px_180px_auto]" onSubmit={(event) => { event.preventDefault(); applyLedgerQuery(); }}>
                <label className="grid gap-1 text-[10px] font-bold uppercase tracking-[0.1em] text-[#66727C]">{localize("Mã đơn / tham chiếu", "Reservation / reference")}<input value={ledgerQueryDraft} maxLength={100} onChange={(event) => setLedgerQueryDraft(event.target.value)} placeholder="RES-… / TXN-…" className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-xs font-semibold normal-case tracking-normal text-[#0F2A43] outline-none transition focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20" /></label>
                <label className="grid gap-1 text-[10px] font-bold uppercase tracking-[0.1em] text-[#66727C]">{localize("Loại bút toán", "Entry type")}<select value={ledgerFilters.eventType} onChange={(event) => updateLedgerFilter("eventType", event.target.value)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-xs font-semibold text-[#0F2A43]"><option value="">{localize("Tất cả", "All")}</option><option value="CASH_IN">{localize("Tiền vào đã ghép", "Matched cash in")}</option><option value="UNMATCHED_CASH_IN">{localize("Tiền vào cần đối soát", "Unmatched cash in")}</option><option value="REFUND_OUT">{localize("Tiền hoàn đã ghép", "Matched refund out")}</option><option value="UNCLASSIFIED_CASH_OUT">{localize("Tiền ra cần đối soát", "Unclassified cash out")}</option><option value="REVENUE_RECOGNIZED">{localize("Doanh thu ghi nhận", "Revenue recognized")}</option></select></label>
                <label className="grid gap-1 text-[10px] font-bold uppercase tracking-[0.1em] text-[#66727C]">Provider<select value={ledgerFilters.provider} onChange={(event) => updateLedgerFilter("provider", event.target.value)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-xs font-semibold text-[#0F2A43]"><option value="">{localize("Tất cả", "All")}</option><option value="SEPAY">SePay</option><option value="CASH">Cash</option></select></label>
                <label className="grid gap-1 text-[10px] font-bold uppercase tracking-[0.1em] text-[#66727C]">Status<select value={ledgerFilters.status} onChange={(event) => updateLedgerFilter("status", event.target.value)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-xs font-semibold text-[#0F2A43]"><option value="">{localize("Tất cả", "All")}</option><option value="SUCCESS">SUCCESS</option><option value="REFUND_PENDING">REFUND_PENDING</option><option value="REFUNDED">REFUNDED</option><option value="SUCCEEDED">SUCCEEDED</option><option value="PAID">PAID</option><option value="BALANCE_DUE">BALANCE_DUE</option><option value="OVERPAID">OVERPAID</option><option value="REVIEW_REQUIRED">REVIEW_REQUIRED</option><option value="PROCESSED">PROCESSED</option><option value="IGNORED">IGNORED</option></select></label>
                <button type="submit" className="min-h-11 self-end rounded-lg bg-[#0F2A43] px-4 text-xs font-bold text-white transition hover:bg-[#173B5B] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">{localize("Tìm", "Search")}</button>
              </form>
            </div>
          </div>
          {ledgerError && <p role="alert" className="m-5 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-semibold text-rose-800">{ledgerError}</p>}
          <div className="lux-scrollbar overflow-x-auto">
            <table className="min-w-[1050px] w-full text-left text-sm">
              <thead className="bg-[#F8F6F0] text-[10px] uppercase tracking-[0.12em] text-[#66727C]"><tr><th className="px-6 py-4">{localize("Thời gian", "Time")}</th><th className="px-4 py-4">{localize("Loại", "Type")}</th><th className="px-4 py-4">{localize("Đơn", "Reservation")}</th><th className="px-4 py-4">{localize("Tham chiếu", "Reference")}</th><th className="px-4 py-4">Provider</th><th className="px-4 py-4">Status</th><th className="px-6 py-4 text-right">{localize("Số tiền", "Amount")}</th></tr></thead>
              <tbody className="divide-y divide-[#0F2A43]/7">{isLedgerLoading ? <tr><td colSpan={7} className="px-6 py-12 text-center"><span className="inline-flex items-center gap-2 text-sm font-semibold text-[#66727C]"><i className="h-4 w-4 animate-spin rounded-full border-2 border-[#0F2A43]/20 border-t-[#0F2A43]" />{localize("Đang tải sổ giao dịch...", "Loading ledger...")}</span></td></tr> : ledger.content.length ? ledger.content.map((entry) => { const hotelTime = formatHotelDateTime(entry.occurredAtUtc); return <tr key={entry.entryKey} className="transition hover:bg-[#FBF8F1]"><td className="whitespace-nowrap px-6 py-4"><p className="font-semibold">{hotelTime.date}</p><p className="mt-1 text-xs text-[#66727C]">{hotelTime.time}</p></td><td className="px-4 py-4"><span className={`rounded-full px-2.5 py-1 text-[10px] font-bold ${entry.eventType === "CASH_IN" ? "bg-emerald-50 text-emerald-700" : entry.eventType === "REFUND_OUT" ? "bg-rose-50 text-rose-700" : entry.eventType === "UNMATCHED_CASH_IN" || entry.eventType === "UNCLASSIFIED_CASH_OUT" ? "bg-amber-100 text-amber-800" : "bg-blue-50 text-blue-700"}`}>{entry.eventType}</span><p className="mt-2 text-xs text-[#66727C]">{entry.description}</p></td><td className="px-4 py-4 font-bold">{entry.reservationCode || "—"}</td><td className="max-w-52 break-all px-4 py-4 text-xs">{entry.reference || "—"}</td><td className="px-4 py-4">{entry.provider || "—"}</td><td className="px-4 py-4"><span className="rounded-md bg-[#F1F0EA] px-2 py-1 text-[10px] font-bold">{entry.status || "—"}</span>{entry.dataQuality !== "CANONICAL" && <p className="mt-2 text-[10px] font-bold text-amber-700">{entry.dataQuality}</p>}</td><td className={`whitespace-nowrap px-6 py-4 text-right font-black ${entry.direction === "OUT" ? "text-rose-700" : entry.direction === "IN" ? "text-emerald-700" : "text-[#0F2A43]"}`}>{entry.direction === "OUT" ? "−" : entry.direction === "IN" ? "+" : ""}{formatMoney(entry.amount)}</td></tr>; }) : <tr><td colSpan={7} className="px-6 py-12 text-center text-sm text-[#66727C]">{localize("Không có bút toán phù hợp bộ lọc.", "No ledger entries match the filters.")}</td></tr>}</tbody>
            </table>
          </div>
          <div className="flex flex-col gap-3 border-t border-[#0F2A43]/8 px-5 py-4 sm:flex-row sm:items-center sm:justify-between"><p className="text-xs text-[#66727C]">{localize("Tổng", "Total")}: <strong className="text-[#0F2A43]">{ledger.totalElements.toLocaleString(localeTag)}</strong> · {localize("Trang", "Page")} {ledger.totalPages ? ledger.page + 1 : 0}/{ledger.totalPages}</p><div className="flex gap-2"><button type="button" disabled={ledger.page <= 0 || isLedgerLoading} onClick={() => setLedgerPage((current) => Math.max(0, current - 1))} className="min-h-11 rounded-lg border border-[#0F2A43]/15 px-4 text-xs font-bold transition hover:border-[#B8944F] disabled:cursor-not-allowed disabled:opacity-40">← {localize("Trước", "Previous")}</button><button type="button" disabled={ledger.page + 1 >= ledger.totalPages || isLedgerLoading} onClick={() => setLedgerPage((current) => current + 1)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 px-4 text-xs font-bold transition hover:border-[#B8944F] disabled:cursor-not-allowed disabled:opacity-40">{localize("Sau", "Next")} →</button></div></div>
        </details>
        </>}

        {activeView === "cashier" && <section className={`${sectionClass} p-4 sm:p-6`}>
          <CashierShiftPanel embedded adminView />
        </section>}

        {activeView === "close" && <section className={`${sectionClass} p-4 sm:p-6`}>
          <BusinessDayPanel embedded />
        </section>}

        <footer className="pb-4 text-center text-[11px] leading-5 text-[#66727C]">{localize("Báo cáo nghiệp vụ là dữ liệu chỉ đọc. Mọi chỉnh sửa tiền vẫn phải đi qua Payment, Refund, Invoice và workflow đối soát hiện hành.", "Business reporting is read-only. Financial mutations still go through the existing Payment, Refund, Invoice and reconciliation workflows.")}</footer>
      </div>
    </main>
  );
}
