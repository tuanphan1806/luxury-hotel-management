"use client";

import Link from "next/link";
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { useDashboardRole } from "@/hooks/use-dashboard-role";
import { apiClient, cachedGet, getApiErrorMessage, getApiErrorStatus } from "@/lib/api";
import {
  apiData,
  type CashFlowPoint,
  chartPoints,
  type BookingPoint,
  type LedgerPage,
  monthToDatePreset,
  type OccupancyPoint,
  type ReservationRevenuePage,
  type RevenuePoint,
  type RoomTypePerformance,
  type StatisticsGranularity,
  type StatisticsKpi,
  type StatisticsOverview,
  statisticsPreset,
} from "@/lib/business-statistics";

type PeriodFilter = { from: string; to: string };
type LedgerFilters = { eventType: string; provider: string; status: string; q: string };
type ReservationFilters = { q: string; status: string };
type ExportReport = "revenue" | "cash-flow" | "bookings" | "occupancy" | "room-types" | "reservations" | "ledger";
type LineMetricPoint = {
  period: string;
  recognizedRevenue?: number;
  netCashFlow?: number;
};

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
  localeTag,
}: {
  label: string;
  value: string;
  detail: string;
  kpi: StatisticsKpi;
  localeTag: string;
}) {
  return (
    <article className={`${sectionClass} min-w-0 p-5 transition duration-200 hover:-translate-y-0.5 hover:shadow-[0_16px_40px_rgba(15,42,67,0.11)]`}>
      <div className="flex items-start justify-between gap-3">
        <p className="text-[11px] font-extrabold uppercase tracking-[0.16em] text-[#66727C]">{label}</p>
        <ChangeBadge value={kpi.changePercent} localeTag={localeTag} />
      </div>
      <p className="mt-4 break-words font-serif text-2xl font-bold text-[#0F2A43] sm:text-3xl">{value}</p>
      <p className="mt-2 text-xs leading-5 text-[#66727C]">{detail}</p>
    </article>
  );
}

function LineChart({
  points,
  valueKey,
  formatValue,
  formatPeriod,
  emptyLabel,
}: {
  points: LineMetricPoint[];
  valueKey: "recognizedRevenue" | "netCashFlow";
  formatValue: (value: number) => string;
  formatPeriod: (value: string) => string;
  emptyLabel: string;
}) {
  const width = 760;
  const height = 230;
  const values = points.map((point) => Number(point[valueKey] || 0));
  const coordinates = chartPoints(values, width, height, 24);
  const path = coordinates.map((point, index) => `${index ? "L" : "M"} ${point.x} ${point.y}`).join(" ");
  const hasData = values.some((value) => value !== 0);

  if (!hasData) {
    return <div className="flex h-56 items-center justify-center rounded-xl border border-dashed border-[#0F2A43]/15 bg-[#F8F6F0] px-6 text-center text-sm text-[#66727C]">{emptyLabel}</div>;
  }

  return (
    <div className="overflow-x-auto pb-2">
      <svg viewBox={`0 0 ${width} ${height}`} className="h-60 min-w-[680px] w-full" role="img" aria-label={emptyLabel}>
        <defs>
          <linearGradient id={`statistics-line-${valueKey}`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#B8944F" stopOpacity="0.26" />
            <stop offset="100%" stopColor="#B8944F" stopOpacity="0" />
          </linearGradient>
        </defs>
        {[0, 1, 2, 3].map((index) => (
          <line key={index} x1="24" x2={width - 24} y1={24 + index * 58} y2={24 + index * 58} stroke="#0F2A43" strokeOpacity="0.08" />
        ))}
        {coordinates.length > 1 && (
          <path d={`${path} L ${coordinates.at(-1)?.x} ${height - 24} L ${coordinates[0].x} ${height - 24} Z`} fill={`url(#statistics-line-${valueKey})`} />
        )}
        <path d={path} fill="none" stroke="#0F2A43" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" />
        {coordinates.map((coordinate, index) => (
          <g key={`${points[index].period}-${valueKey}`} tabIndex={0} role="img" aria-label={`${formatPeriod(points[index].period)}: ${formatValue(coordinate.value)}`}>
            <circle cx={coordinate.x} cy={coordinate.y} r="8" fill="white" stroke="#B8944F" strokeWidth="4" className="cursor-pointer transition hover:r-[10px]" />
            <title>{formatPeriod(points[index].period)} · {formatValue(coordinate.value)}</title>
          </g>
        ))}
      </svg>
      <div className="flex min-w-[680px] justify-between px-6 text-[11px] font-semibold text-[#66727C]">
        <span>{points[0] ? formatPeriod(points[0].period) : ""}</span>
        <span>{points.at(-1) ? formatPeriod(points.at(-1)!.period) : ""}</span>
      </div>
    </div>
  );
}

const bookingSegments: Array<{ key: keyof BookingPoint; label: string; color: string }> = [
  { key: "paymentPending", label: "Chờ thanh toán", color: "#D8C398" },
  { key: "draft", label: "Chờ xác nhận", color: "#B8944F" },
  { key: "confirmed", label: "Đã xác nhận", color: "#4D7895" },
  { key: "checkedIn", label: "Đang lưu trú", color: "#2D7A65" },
  { key: "checkedOut", label: "Đã trả phòng", color: "#0F2A43" },
  { key: "cancellationPending", label: "Chờ hủy", color: "#D08C60" },
  { key: "cancelled", label: "Đã hủy", color: "#A85555" },
  { key: "noShow", label: "Không đến", color: "#6E5B73" },
];

function BookingChart({ points, formatPeriod, emptyLabel }: { points: BookingPoint[]; formatPeriod: (value: string) => string; emptyLabel: string }) {
  const maximum = Math.max(1, ...points.map((point) => point.total));
  if (!points.some((point) => point.total > 0)) {
    return <div className="flex h-56 items-center justify-center rounded-xl border border-dashed border-[#0F2A43]/15 bg-[#F8F6F0] px-6 text-center text-sm text-[#66727C]">{emptyLabel}</div>;
  }
  return (
    <div>
      <div className="lux-scrollbar flex min-h-56 items-end gap-3 overflow-x-auto px-2 pb-3 pt-6">
        {points.map((point) => (
          <div key={point.period} className="group flex min-w-12 flex-1 flex-col items-center gap-2" title={`${formatPeriod(point.period)} · ${point.total} đơn`}>
            <span className="text-[11px] font-bold text-[#0F2A43] opacity-0 transition group-hover:opacity-100 group-focus-within:opacity-100">{point.total}</span>
            <div className="flex h-40 w-full max-w-12 flex-col-reverse overflow-hidden rounded-t-md bg-[#F1F0EA]" tabIndex={0} aria-label={`${formatPeriod(point.period)}: ${point.total} đơn`}>
              {bookingSegments.map((segment) => {
                const value = Number(point[segment.key] || 0);
                return value > 0 ? <span key={segment.key} style={{ height: `${(value / maximum) * 100}%`, backgroundColor: segment.color }} title={`${segment.label}: ${value}`} /> : null;
              })}
            </div>
            <span className="max-w-16 truncate text-[10px] font-semibold text-[#66727C]">{formatPeriod(point.period)}</span>
          </div>
        ))}
      </div>
      <div className="mt-3 flex flex-wrap gap-x-4 gap-y-2 border-t border-[#0F2A43]/8 pt-3">
        {bookingSegments.map((segment) => <span key={segment.key} className="inline-flex items-center gap-1.5 text-[10px] font-semibold text-[#66727C]"><i className="h-2.5 w-2.5 rounded-sm" style={{ backgroundColor: segment.color }} />{segment.label}</span>)}
      </div>
    </div>
  );
}

function OccupancyChart({ points, formatPeriod, formatPercent, emptyLabel }: { points: OccupancyPoint[]; formatPeriod: (value: string) => string; formatPercent: (value: number) => string; emptyLabel: string }) {
  if (!points.some((point) => point.availableRoomHours > 0)) {
    return <div className="flex h-56 items-center justify-center rounded-xl border border-dashed border-[#0F2A43]/15 bg-[#F8F6F0] px-6 text-center text-sm text-[#66727C]">{emptyLabel}</div>;
  }
  return (
    <div className="lux-scrollbar flex min-h-60 items-end gap-3 overflow-x-auto px-2 pb-3 pt-8">
      {points.map((point) => (
        <div key={point.period} className="group flex min-w-12 flex-1 flex-col items-center gap-2" title={`${formatPeriod(point.period)} · ${formatPercent(point.occupancyRate)}`}>
          <span className="text-[10px] font-bold text-[#0F2A43] opacity-0 transition group-hover:opacity-100 group-focus-within:opacity-100">{formatPercent(point.occupancyRate)}</span>
          <div className="relative h-40 w-full max-w-12 overflow-hidden rounded-t-lg bg-[#E8E6DE]" tabIndex={0} aria-label={`${formatPeriod(point.period)}: ${formatPercent(point.occupancyRate)}`}>
            <span className="absolute inset-x-0 bottom-0 rounded-t-lg bg-gradient-to-t from-[#0F2A43] to-[#315B78] transition-all duration-500" style={{ height: `${Math.min(100, Math.max(0, point.occupancyRate))}%` }} />
          </div>
          <span className="max-w-16 truncate text-[10px] font-semibold text-[#66727C]">{formatPeriod(point.period)}</span>
        </div>
      ))}
    </div>
  );
}

function LoadingPanel() {
  return <div className="h-72 animate-pulse rounded-2xl border border-[#0F2A43]/8 bg-gradient-to-r from-white via-[#F1F0EA] to-white bg-[length:200%_100%]" />;
}

export default function BusinessStatisticsPage() {
  const { localeTag, localize } = useLanguage();
  const { role, isAdmin } = useDashboardRole();
  const [draftPeriod, setDraftPeriod] = useState<PeriodFilter>(initialPeriod);
  const [period, setPeriod] = useState<PeriodFilter>(initialPeriod);
  const [granularity, setGranularity] = useState<StatisticsGranularity>("day");
  const [overview, setOverview] = useState<StatisticsOverview | null>(null);
  const [revenue, setRevenue] = useState<RevenuePoint[]>([]);
  const [cashFlow, setCashFlow] = useState<CashFlowPoint[]>([]);
  const [cashProvider, setCashProvider] = useState("");
  const [bookings, setBookings] = useState<BookingPoint[]>([]);
  const [occupancy, setOccupancy] = useState<OccupancyPoint[]>([]);
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
  const reservationSectionRef = useRef<HTMLElement | null>(null);
  const ledgerSectionRef = useRef<HTMLElement | null>(null);
  const summaryRequestRef = useRef(0);
  const cashFlowRequestRef = useRef(0);
  const reservationRequestRef = useRef(0);
  const ledgerRequestRef = useRef(0);

  const money = useMemo(() => new Intl.NumberFormat(localeTag, { style: "currency", currency: "VND", maximumFractionDigits: 0 }), [localeTag]);
  const number = useMemo(() => new Intl.NumberFormat(localeTag, { maximumFractionDigits: 1 }), [localeTag]);
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
    payments: totals.payments + Number(point.paymentCount || 0),
    refunds: totals.refunds + Number(point.refundCount || 0),
    unaccepted: totals.unaccepted + Number(point.unacceptedReceivedAmount || 0),
    unmatchedIn: totals.unmatchedIn + Number(point.unmatchedCashInEventCount || 0),
    unclassifiedOut: totals.unclassifiedOut + Number(point.unclassifiedCashOutEventCount || 0),
    unclassifiedOutAmount: totals.unclassifiedOutAmount + Number(point.unclassifiedCashOutflow || 0),
  }), { payments: 0, refunds: 0, unaccepted: 0, unmatchedIn: 0, unclassifiedOut: 0, unclassifiedOutAmount: 0 }), [cashFlow]);

  const loadSummary = useCallback(async (force = false) => {
    const requestId = ++summaryRequestRef.current;
    setIsLoading(true);
    setError("");
    const params = { ...period, granularity };
    try {
      const [overviewResponse, revenueResponse, bookingResponse, occupancyResponse, roomTypeResponse] = await Promise.all([
        cachedGet<{ data: StatisticsOverview }>("/api/admin/statistics/overview", { ttlMs: 30_000, force, config: { params: period } }),
        cachedGet<{ data: RevenuePoint[] }>("/api/admin/statistics/revenue", { ttlMs: 30_000, force, config: { params } }),
        cachedGet<{ data: BookingPoint[] }>("/api/admin/statistics/bookings", { ttlMs: 30_000, force, config: { params } }),
        cachedGet<{ data: OccupancyPoint[] }>("/api/admin/statistics/occupancy", { ttlMs: 30_000, force, config: { params } }),
        cachedGet<{ data: RoomTypePerformance[] }>("/api/admin/statistics/room-types", { ttlMs: 30_000, force, config: { params: period } }),
      ]);
      if (requestId !== summaryRequestRef.current) return;
      setOverview(apiData(overviewResponse));
      setRevenue(apiData(revenueResponse));
      setBookings(apiData(bookingResponse));
      setOccupancy(apiData(occupancyResponse));
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
    if (isAdmin) void loadSummary();
  }, [isAdmin, loadSummary]);

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
    if (!isAdmin || !overview) return;
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
  }, [isAdmin, overview]);

  const applyPeriod = () => {
    if (!draftPeriod.from || !draftPeriod.to || draftPeriod.from > draftPeriod.to) {
      setNotice(localize("Khoảng ngày không hợp lệ: ngày bắt đầu phải trước hoặc bằng ngày kết thúc.", "Invalid date range: the start date must be on or before the end date."));
      return;
    }
    setNotice("");
    setLedgerPage(0);
    setReservationPage(0);
    setPeriod(draftPeriod);
  };

  const applyPreset = (next: PeriodFilter) => {
    setDraftPeriod(next);
    setPeriod(next);
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
    { label: localize("Đơn đặt phòng", "Bookings"), value: number.format(overview.bookings.current), detail: localize("Đơn được tạo trong kỳ", "Reservations created in the period"), kpi: overview.bookings },
    { label: localize("Công suất", "Occupancy"), value: formatPercent(overview.occupancyRate.current), detail: localize("Theo giờ phòng, quy đổi 24 giờ", "Room-hours, normalized to 24 hours"), kpi: overview.occupancyRate },
    { label: "ADR", value: formatMoney(overview.adr.current), detail: localize("Doanh thu phòng / đêm phòng quy đổi", "Room revenue / room-night equivalent"), kpi: overview.adr },
    { label: "RevPAR", value: formatMoney(overview.revPar.current), detail: localize("Doanh thu phòng / đêm phòng sẵn có", "Room revenue / available room-night"), kpi: overview.revPar },
  ] : [];

  return (
    <main className="min-h-screen bg-[#F1F0EA] px-4 py-6 text-[#0F2A43] sm:px-6 lg:px-8">
      <div className="mx-auto max-w-[1500px] space-y-6">
        <header className="overflow-hidden rounded-3xl bg-[#0F2A43] text-white shadow-[0_18px_48px_rgba(15,42,67,0.2)]">
          <div className="relative grid gap-7 px-6 py-8 lg:grid-cols-[1fr_auto] lg:items-end lg:px-10">
            <div className="pointer-events-none absolute -right-20 -top-28 h-72 w-72 rounded-full border border-[#B8944F]/25" />
            <div className="pointer-events-none absolute -right-8 -top-12 h-44 w-44 rounded-full border border-white/10" />
            <div className="relative">
              <p className="text-[11px] font-extrabold uppercase tracking-[0.24em] text-[#D8C398]">{localize("Báo cáo nghiệp vụ", "Business reporting")}</p>
              <h1 className="mt-3 max-w-3xl font-serif text-3xl font-bold leading-tight sm:text-4xl lg:text-5xl">{localize("Doanh thu, đặt phòng và công suất trong một nguồn dữ liệu.", "Revenue, bookings and occupancy from one source of truth.")}</h1>
              <p className="mt-4 max-w-3xl text-sm leading-6 text-white/72">{localize("Tách rõ doanh thu đã ghi nhận, dòng tiền thực nhận, tiền hoàn và nghĩa vụ còn mở. Mọi kỳ báo cáo dùng múi giờ Asia/Ho_Chi_Minh.", "Recognized revenue, actual cash flow, refunds and open obligations are kept distinct. All reporting periods use Asia/Ho_Chi_Minh.")}</p>
            </div>
            <div className="relative grid min-w-[260px] gap-2 rounded-2xl border border-white/15 bg-white/8 p-3 backdrop-blur-sm">
              <label className="grid gap-1 text-[10px] font-extrabold uppercase tracking-[0.14em] text-[#D8C398]">
                {localize("Xuất báo cáo CSV", "Export CSV report")}
                <select value={selectedExportReport} onChange={(event) => setSelectedExportReport(event.target.value as ExportReport)} className="min-h-11 rounded-lg border border-white/20 bg-[#173B5B] px-3 text-xs font-bold normal-case tracking-normal text-white outline-none transition focus:border-[#D8C398] focus:ring-2 focus:ring-[#D8C398]/30">
                  {(Object.keys(exportLabels) as ExportReport[]).map((report) => <option key={report} value={report}>{exportLabels[report]}</option>)}
                </select>
              </label>
              <button type="button" disabled={Boolean(isExporting)} onClick={() => void downloadReport(selectedExportReport)} className="inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-[#B8944F] px-4 text-xs font-extrabold text-[#0F2A43] transition hover:bg-[#D8C398] disabled:cursor-not-allowed disabled:opacity-55 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white">
                {Boolean(isExporting) && <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-[#0F2A43]/30 border-t-[#0F2A43]" />}
                {localize("Tải CSV", "Download CSV")}
              </button>
            </div>
          </div>
        </header>

        <section className={`${sectionClass} p-4 sm:p-5`} aria-label={localize("Bộ lọc báo cáo", "Report filters")}>
          <div className="flex flex-col gap-4 xl:flex-row xl:items-end xl:justify-between">
            <div className="grid flex-1 gap-3 sm:grid-cols-2 lg:grid-cols-[minmax(160px,1fr)_minmax(160px,1fr)_auto]">
              <label className="grid gap-1.5 text-xs font-bold text-[#52616D]">{localize("Từ ngày", "From")}<input type="date" value={draftPeriod.from} max={draftPeriod.to} onChange={(event) => setDraftPeriod((current) => ({ ...current, from: event.target.value }))} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-[#FBFAF6] px-3 text-sm text-[#0F2A43] outline-none transition focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20" /></label>
              <label className="grid gap-1.5 text-xs font-bold text-[#52616D]">{localize("Đến ngày", "To")}<input type="date" value={draftPeriod.to} min={draftPeriod.from} onChange={(event) => setDraftPeriod((current) => ({ ...current, to: event.target.value }))} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-[#FBFAF6] px-3 text-sm text-[#0F2A43] outline-none transition focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20" /></label>
              <button type="button" onClick={applyPeriod} className="min-h-11 self-end rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#173B5B] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">{localize("Áp dụng", "Apply")}</button>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <button type="button" onClick={() => applyPreset(statisticsPreset(7))} className="min-h-11 rounded-lg border border-[#0F2A43]/12 px-3 text-xs font-bold transition hover:border-[#B8944F] hover:bg-[#F8F3E8]">7 {localize("ngày", "days")}</button>
              <button type="button" onClick={() => applyPreset(statisticsPreset(30))} className="min-h-11 rounded-lg border border-[#0F2A43]/12 px-3 text-xs font-bold transition hover:border-[#B8944F] hover:bg-[#F8F3E8]">30 {localize("ngày", "days")}</button>
              <button type="button" onClick={() => applyPreset(monthToDatePreset())} className="min-h-11 rounded-lg border border-[#0F2A43]/12 px-3 text-xs font-bold transition hover:border-[#B8944F] hover:bg-[#F8F3E8]">{localize("Tháng này", "This month")}</button>
              <div className="flex min-h-11 rounded-lg bg-[#F1F0EA] p-1">
                {(["day", "week", "month"] as StatisticsGranularity[]).map((value) => <button key={value} type="button" onClick={() => setGranularity(value)} className={`rounded-md px-3 text-xs font-bold transition ${granularity === value ? "bg-white text-[#0F2A43] shadow-sm" : "text-[#66727C] hover:text-[#0F2A43]"}`}>{value === "day" ? localize("Ngày", "Day") : value === "week" ? localize("Tuần", "Week") : localize("Tháng", "Month")}</button>)}
              </div>
              <button type="button" disabled={isRefreshing} onClick={refresh} className="inline-flex min-h-11 items-center gap-2 rounded-lg border border-[#0F2A43]/12 px-3 text-xs font-bold transition hover:border-[#B8944F] hover:bg-[#F8F3E8] disabled:opacity-50"><span className={isRefreshing ? "animate-spin" : ""}>↻</span>{localize("Làm mới", "Refresh")}</button>
            </div>
          </div>
          {notice && <p role="status" className="mt-3 rounded-lg border border-[#B8944F]/30 bg-[#FFF8E7] px-4 py-3 text-xs font-semibold text-[#765A21]">{notice}</p>}
        </section>

        {error && <div role="alert" className="rounded-xl border border-rose-200 bg-rose-50 px-5 py-4 text-sm font-semibold text-rose-800">{error}</div>}

        {isLoading ? <><div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">{Array.from({ length: 5 }, (_, index) => <div key={index} className="h-40 animate-pulse rounded-2xl bg-white" />)}</div><div className="grid gap-6 xl:grid-cols-2"><LoadingPanel /><LoadingPanel /></div></> : overview && <>
          <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5" aria-label={localize("Chỉ số chính", "Key metrics")}>
            {kpiCards.map((card) => <MetricCard key={card.label} {...card} localeTag={localeTag} />)}
          </section>

          <section className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
            {[
              [localize("Tiền vào thực nhận", "Gross cash received"), overview.grossCashInflow, localize("Theo receivedAmount của giao dịch thành công", "Successful transaction receivedAmount")],
              [localize("Tiền hoàn đã chi", "Completed refunds"), overview.refundOutflow, localize("Refund SUCCEEDED theo thời điểm hoàn tất", "SUCCEEDED refunds by completion time")],
              [localize("Dòng tiền ròng", "Net cash flow"), overview.netCashFlow, localize("Tiền vào trừ tiền hoàn trong kỳ", "Cash received minus refunds in the period")],
              [localize("Công nợ đang lưu trú", "Active-stay receivables"), overview.outstandingReceivables, localize("Số còn cần thu của đơn đang CHECKED_IN", "Amount still due from CHECKED_IN stays")],
            ].map(([label, value, detail]) => <article key={String(label)} className={`${sectionClass} p-5`}><p className="text-[10px] font-extrabold uppercase tracking-[0.16em] text-[#66727C]">{label}</p><p className={`mt-3 text-2xl font-black ${Number(value) < 0 ? "text-rose-700" : "text-[#0F2A43]"}`}>{formatMoney(Number(value))}</p><p className="mt-2 text-xs leading-5 text-[#66727C]">{detail}</p></article>)}
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

          <section className="grid gap-6 xl:grid-cols-2">
            <article ref={cashFlowSectionRef} className={`${sectionClass} p-5 sm:p-6`}>
              <div className="mb-5 flex items-start justify-between gap-4"><div><p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#B8944F]">{localize("Doanh thu", "Revenue")}</p><h2 className="mt-1 font-serif text-2xl font-bold">{localize("Doanh thu đã ghi nhận", "Recognized revenue trend")}</h2></div><span className="rounded-full bg-[#F1F0EA] px-3 py-1 text-[10px] font-bold text-[#66727C]">{period.from} → {period.to}</span></div>
              <LineChart points={revenue} valueKey="recognizedRevenue" formatValue={formatMoney} formatPeriod={formatPeriod} emptyLabel={localize("Chưa có hóa đơn checkout trong kỳ.", "No checkout invoices in this period.")} />
              <div className="mt-4 grid grid-cols-2 gap-2 border-t border-[#0F2A43]/8 pt-4 sm:grid-cols-4">
                <p className="rounded-lg bg-[#F8F6F0] px-3 py-2 text-[10px] text-[#66727C]">{localize("Hóa đơn", "Invoices")}<strong className="mt-1 block text-sm text-[#0F2A43]">{number.format(revenueTotals.invoices)}</strong></p>
                <p className="rounded-lg bg-[#F8F6F0] px-3 py-2 text-[10px] text-[#66727C]">{localize("Dịch vụ", "Add-ons")}<strong className="mt-1 block text-sm text-[#0F2A43]">{formatMoney(revenueTotals.addOns)}</strong></p>
                <p className="rounded-lg bg-[#F8F6F0] px-3 py-2 text-[10px] text-[#66727C]">{localize("Phụ phí", "Additional fees")}<strong className="mt-1 block text-sm text-[#0F2A43]">{formatMoney(revenueTotals.additionalFees)}</strong></p>
                <p className="rounded-lg bg-[#F8F6F0] px-3 py-2 text-[10px] text-[#66727C]">{localize("Phí trả muộn", "Late fees")}<strong className="mt-1 block text-sm text-[#0F2A43]">{formatMoney(revenueTotals.lateFees)}</strong></p>
              </div>
            </article>
            <article className={`${sectionClass} p-5 sm:p-6`}>
              <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between"><div><p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#B8944F]">{localize("Dòng tiền", "Cash flow")}</p><h2 className="mt-1 font-serif text-2xl font-bold">{localize("Tiền vào trừ tiền hoàn", "Cash received less refunds")}</h2></div><label className="grid gap-1 text-[10px] font-bold uppercase tracking-[0.1em] text-[#66727C]">Provider<select value={cashProvider} onChange={(event) => setCashProvider(event.target.value)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-xs font-semibold text-[#0F2A43]"><option value="">{localize("Tất cả", "All")}</option><option value="SEPAY">SePay</option><option value="CASH">Cash</option></select></label></div>
              {cashFlowError ? <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-semibold text-rose-800">{cashFlowError}</p> : isCashLoading ? <LoadingPanel /> : <LineChart points={cashFlow} valueKey="netCashFlow" formatValue={formatMoney} formatPeriod={formatPeriod} emptyLabel={localize("Chưa có dòng tiền trong kỳ.", "No cash movement in this period.")} />}
              <div className="mt-4 grid grid-cols-2 gap-2 border-t border-[#0F2A43]/8 pt-4 sm:grid-cols-5">
                <p className="rounded-lg bg-[#F8F6F0] px-3 py-2 text-[10px] text-[#66727C]">{localize("Giao dịch thu", "Payments")}<strong className="mt-1 block text-sm text-[#0F2A43]">{number.format(cashFlowTotals.payments)}</strong></p>
                <p className="rounded-lg bg-[#FFF8E7] px-3 py-2 text-[10px] text-[#765A21]">{localize("Tiền vào chưa ghép", "Unmatched in")}<strong className="mt-1 block text-sm">{number.format(cashFlowTotals.unmatchedIn)}</strong></p>
                <p className="rounded-lg bg-[#F8F6F0] px-3 py-2 text-[10px] text-[#66727C]">{localize("Giao dịch hoàn", "Refunds")}<strong className="mt-1 block text-sm text-[#0F2A43]">{number.format(cashFlowTotals.refunds)}</strong></p>
                <p className="rounded-lg bg-[#F8F6F0] px-3 py-2 text-[10px] text-[#66727C]">{localize("Tiền dư/chưa nhận nghĩa vụ", "Unaccepted received")}<strong className="mt-1 block text-sm text-[#0F2A43]">{formatMoney(cashFlowTotals.unaccepted)}</strong></p>
                <p className="rounded-lg bg-rose-50 px-3 py-2 text-[10px] text-rose-700">{localize("Tiền ra chưa phân loại", "Unclassified out")}<strong className="mt-1 block text-sm">{number.format(cashFlowTotals.unclassifiedOut)} · {formatMoney(cashFlowTotals.unclassifiedOutAmount)}</strong></p>
              </div>
            </article>
            <article className={`${sectionClass} p-5 sm:p-6`}>
              <div className="mb-4"><p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#B8944F]">{localize("Đặt phòng", "Bookings")}</p><h2 className="mt-1 font-serif text-2xl font-bold">{localize("Số đơn theo trạng thái hiện tại", "Bookings by current status")}</h2></div>
              <BookingChart points={bookings} formatPeriod={formatPeriod} emptyLabel={localize("Chưa có đơn được tạo trong kỳ.", "No reservations were created in this period.")} />
            </article>
            <article className={`${sectionClass} p-5 sm:p-6`}>
              <div className="mb-4"><p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#B8944F]">{localize("Công suất", "Occupancy")}</p><h2 className="mt-1 font-serif text-2xl font-bold">{localize("Tỷ lệ sử dụng phòng theo giờ", "Hourly room utilization")}</h2></div>
              <OccupancyChart points={occupancy} formatPeriod={formatPeriod} formatPercent={formatPercent} emptyLabel={localize("Chưa có dữ liệu tồn kho phòng trong kỳ.", "No room inventory data is available for this period.")} />
            </article>
          </section>

          <section ref={reservationSectionRef} className={`${sectionClass} overflow-hidden`} aria-labelledby="reservation-revenue-heading">
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

          <section className={`${sectionClass} overflow-hidden`}>
            <div className="border-b border-[#0F2A43]/8 px-5 py-5 sm:px-6"><p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#B8944F]">{localize("Hiệu quả hạng phòng", "Room-type performance")}</p><h2 className="mt-1 font-serif text-2xl font-bold">{localize("Doanh thu và công suất theo loại phòng", "Revenue and occupancy by room type")}</h2></div>
            <div className="lux-scrollbar overflow-x-auto">
              <table className="min-w-[980px] w-full text-left text-sm">
                <thead className="bg-[#F8F6F0] text-[10px] uppercase tracking-[0.12em] text-[#66727C]"><tr><th className="px-6 py-4">{localize("Loại phòng", "Room type")}</th><th className="px-4 py-4">{localize("Đơn", "Bookings")}</th><th className="px-4 py-4">{localize("Lượt phòng", "Rooms reserved")}</th><th className="px-4 py-4">{localize("Giờ phòng bán", "Sold room-hours")}</th><th className="px-4 py-4">{localize("Công suất", "Occupancy")}</th><th className="px-4 py-4">{localize("Doanh thu phòng", "Room revenue")}</th><th className="px-4 py-4">ADR</th><th className="px-4 py-4">RevPAR</th></tr></thead>
                <tbody className="divide-y divide-[#0F2A43]/7">{roomTypes.length ? roomTypes.map((roomType) => <tr key={roomType.roomTypeId} className="transition hover:bg-[#FBF8F1]"><td className="px-6 py-4"><p className="font-bold">{roomType.roomTypeName}</p><p className="mt-1 text-[10px] font-bold tracking-[0.1em] text-[#66727C]">{roomType.roomTypeCode}</p></td><td className="px-4 py-4 font-semibold">{roomType.bookingCount}</td><td className="px-4 py-4 font-semibold">{roomType.reservedRoomQuantity}</td><td className="px-4 py-4">{number.format(roomType.soldRoomHours)}</td><td className="px-4 py-4"><div className="flex items-center gap-3"><span className="h-2 w-24 overflow-hidden rounded-full bg-[#E8E6DE]"><i className="block h-full rounded-full bg-[#315B78]" style={{ width: `${Math.min(100, roomType.occupancyRate)}%` }} /></span><strong>{formatPercent(roomType.occupancyRate)}</strong></div></td><td className="px-4 py-4 font-bold">{formatMoney(roomType.recognizedRoomRevenue + roomType.extraGuestRevenue)}</td><td className="px-4 py-4 font-bold">{formatMoney(roomType.adr)}</td><td className="px-4 py-4 font-bold">{formatMoney(roomType.revPar)}</td></tr>) : <tr><td colSpan={8} className="px-6 py-12 text-center text-sm text-[#66727C]">{localize("Chưa có dữ liệu loại phòng trong kỳ.", "No room-type data in this period.")}</td></tr>}</tbody>
              </table>
            </div>
          </section>

          <section className="grid gap-4 md:grid-cols-3">
            {[[localize("Tiền khách trả trước", "Customer deposits"), overview.customerDeposits, localize("Tiền đã thu trên các đơn chưa đóng", "Cash held on open reservations")], [localize("Nghĩa vụ hoàn tiền", "Refund payable"), overview.refundPayable, localize("Yêu cầu đang mở và phần nghĩa vụ chưa được refund hợp lệ bao phủ", "Open requests plus refund obligations not covered by a valid refund")], [localize("Tiền được chấp nhận", "Accepted cash"), overview.acceptedCashInflow, localize("Phần tiền khớp nghĩa vụ, không gồm phần dư", "Amount matched to obligations, excluding excess")]].map(([label, value, detail]) => <article key={String(label)} className={`${sectionClass} p-5`}><p className="text-[10px] font-extrabold uppercase tracking-[0.16em] text-[#66727C]">{label}</p><p className="mt-3 text-2xl font-black">{formatMoney(Number(value))}</p><p className="mt-2 text-xs leading-5 text-[#66727C]">{detail}</p></article>)}
          </section>
        </>}

        <section ref={ledgerSectionRef} className={`${sectionClass} overflow-hidden`} aria-labelledby="ledger-heading">
          <div className="border-b border-[#0F2A43]/8 px-5 py-5 sm:px-6">
            <div className="flex flex-col gap-4 xl:flex-row xl:items-end xl:justify-between">
              <div><p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#B8944F]">{localize("Sổ giao dịch chỉ đọc", "Read-only transaction ledger")}</p><h2 id="ledger-heading" className="mt-1 font-serif text-2xl font-bold">{localize("Không để tiền vào, tiền hoàn hoặc doanh thu bị khuất", "Keep cash-in, refunds and recognized revenue visible")}</h2></div>
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
        </section>

        <footer className="pb-4 text-center text-[11px] leading-5 text-[#66727C]">{localize("Báo cáo nghiệp vụ là dữ liệu chỉ đọc. Mọi chỉnh sửa tiền vẫn phải đi qua Payment, Refund, Invoice và workflow đối soát hiện hành.", "Business reporting is read-only. Financial mutations still go through the existing Payment, Refund, Invoice and reconciliation workflows.")}</footer>
      </div>
    </main>
  );
}
