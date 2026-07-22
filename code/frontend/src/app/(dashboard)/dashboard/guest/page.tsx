"use client";

import React, { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { apiClient, cachedGet } from "@/lib/api";
import Toast from "@/components/UI/Toast";
import ViewportModal from "@/components/UI/ViewportModal";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import {
  DashboardFilterPanel,
  DashboardSearchField,
  DashboardSelectField,
  FilterQuickButton,
} from "@/components/dashboard/DashboardFilterPanel";

interface ReservationGuestRow {
  id: number | string;
  reservationId: number;
  reservationCode: string;
  guestName: string;
  email: string;
  phone: string;
  status: string;
  guestCount: number;
  checkIn: string;
  checkOut: string;
  roomSummary: string;
  roomName?: string;
  isPrimary: boolean;
  idCard: string;
  source: "STAY_GUEST" | "BOOKING_CONTACT";
  idCardType?: string;
  idCardNumber?: string;
  nationality?: string;
  dateOfBirth?: string;
}

interface ReservationApiItem {
  id: number;
  reservationCode?: string;
  customerName?: string;
  customerEmail?: string;
  customerPhone?: string;
  status: string;
  guestCount?: number;
  checkIn: string;
  checkOut: string;
}

interface StayGuestApiItem {
  id: number;
  reservationId?: number;
  reservationRoomId?: number;
  roomName?: string;
  fullName?: string;
  email?: string;
  phone?: string;
  isPrimary?: boolean;
  idCardType?: string;
  idCardNumber?: string;
  nationality?: string;
  dateOfBirth?: string;
}

interface ReservationGuestGroup {
  reservationId: number;
  reservationCode: string;
  status: string;
  checkIn: string;
  checkOut: string;
  rows: ReservationGuestRow[];
}

const getApiErrorMessage = (error: unknown, fallback: string) => {
  if (typeof error !== "object" || error === null || !("response" in error)) return fallback;
  const response = (error as { response?: { data?: { message?: unknown } } }).response;
  return typeof response?.data?.message === "string" ? response.data.message : fallback;
};

const formatDate = (value?: string, localeTag = "vi-VN") => {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString(localeTag, { day: "2-digit", month: "2-digit", year: "numeric" });
};

export default function DashboardGuestPage() {
  const { locale, localeTag, localize } = useLanguage();
  const [rows, setRows] = useState<ReservationGuestRow[]>([]);
  const [searchQuery, setSearchQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("All");
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [toast, setToast] = useState<{ message: string; type: "success" | "error" | "info" } | null>(null);
  const [editingGuest, setEditingGuest] = useState<ReservationGuestRow | null>(null);
  const [expandedReservationId, setExpandedReservationId] = useState<number | null>(null);
  const [editForm, setEditForm] = useState({ fullName: "", phone: "", email: "", idCardType: "CCCD", idCardNumber: "", nationality: "Vietnam", dateOfBirth: "" });
  const [isSaving, setIsSaving] = useState(false);

  const fetchGuests = useCallback(async (showLoading = false) => {
    if (showLoading) setIsLoading(true);
    setLoadError("");
    try {
      const [res, guestsRes] = await Promise.all([
        cachedGet("/api/reservations"),
        cachedGet("/api/guests"),
      ]);
      const reservations = (Array.isArray(res.data?.data) ? res.data.data as ReservationApiItem[] : [])
        .filter((reservation) => reservation.status !== "CANCELLED");
      const allGuests = Array.isArray(guestsRes.data?.data) ? guestsRes.data.data as StayGuestApiItem[] : [];
      const guestsByReservation = allGuests.reduce<Record<number, StayGuestApiItem[]>>((groups, guest) => {
        if (guest.reservationId) (groups[guest.reservationId] ||= []).push(guest);
        return groups;
      }, {});
      const guestRows = reservations.flatMap<ReservationGuestRow>((reservation) => {
        const guests = guestsByReservation[reservation.id] || [];
        if (!guests.length) {
          return [{
            id: `reservation-${reservation.id}`,
            reservationId: reservation.id,
            reservationCode: reservation.reservationCode || "",
            guestName: reservation.customerName || "Khách đại diện",
            email: reservation.customerEmail || "-",
            phone: reservation.customerPhone || "-",
            status: reservation.status || "-",
            guestCount: Number(reservation.guestCount || 0),
            checkIn: reservation.checkIn,
            checkOut: reservation.checkOut,
            roomSummary: ["CONFIRMED", "DRAFT", "CANCELLATION_PENDING"].includes(reservation.status) ? "Chưa check-in / chưa gán phòng" : "Không có danh sách khách lưu trú",
            roomName: undefined,
            isPrimary: true,
            idCard: "-",
            source: "BOOKING_CONTACT" as const,
            idCardType: "",
            idCardNumber: "",
            nationality: "",
            dateOfBirth: "",
          }];
        }
        return guests.map((guest) => ({
          id: guest.id,
          reservationId: reservation.id,
          reservationCode: reservation.reservationCode || "",
          guestName: guest.fullName || "Guest",
          email: guest.email || "-",
          phone: guest.phone || "-",
          status: reservation.status || "-",
          guestCount: 1,
          checkIn: reservation.checkIn,
          checkOut: reservation.checkOut,
          roomSummary: guest.roomName ? `Phòng #${guest.roomName}` : guest.reservationRoomId ? `Chưa gán phòng vật lý (#${guest.reservationRoomId})` : "Đã checkout",
          roomName: guest.roomName,
          isPrimary: Boolean(guest.isPrimary),
          idCard: [guest.idCardType, guest.idCardNumber].filter(Boolean).join(" · ") || "-",
          source: "STAY_GUEST" as const,
          idCardType: guest.idCardType || "CCCD",
          idCardNumber: guest.idCardNumber || "",
          nationality: guest.nationality || "Vietnam",
          dateOfBirth: guest.dateOfBirth || "",
        }));
      });
      setRows(guestRows);
    } catch {
      setRows([]);
      setLoadError(localize("Không thể tải danh sách khách từ dữ liệu đặt phòng.", "Could not load guests from reservation data."));
    } finally {
      setIsLoading(false);
    }
  }, [localize]);

  useEffect(() => {
    void fetchGuests(true);
    const refreshInBackground = () => void fetchGuests(false);
    window.addEventListener("focus", refreshInBackground);
    return () => window.removeEventListener("focus", refreshInBackground);
  }, [fetchGuests]);

  const statuses = useMemo(() => {
    const unique = Array.from(new Set(rows.map((row) => row.status).filter(Boolean)));
    return ["All", ...unique];
  }, [rows]);

  const filteredRows = useMemo(() => {
    const keyword = searchQuery.trim().toLowerCase();
    const priority: Record<string, number> = { CHECKED_IN: 0, CANCELLATION_PENDING: 1, CONFIRMED: 2, DRAFT: 3, CHECKED_OUT: 4, NO_SHOW: 5 };
    return rows
      .filter((row) => {
        const matchesStatus = statusFilter === "All" || row.status === statusFilter;
        const matchesSearch =
          !keyword ||
          row.guestName.toLowerCase().includes(keyword) ||
          String(row.id).toLowerCase().includes(keyword) ||
          row.reservationCode.toLowerCase().includes(keyword) ||
          row.email.toLowerCase().includes(keyword) ||
          row.phone.toLowerCase().includes(keyword) ||
          row.roomSummary.toLowerCase().includes(keyword);
        return matchesStatus && matchesSearch;
      })
      .sort((left, right) => (priority[left.status] ?? 99) - (priority[right.status] ?? 99)
        || Number(right.isPrimary) - Number(left.isPrimary)
        || new Date(right.checkIn).getTime() - new Date(left.checkIn).getTime());
  }, [rows, searchQuery, statusFilter]);

  const filteredReservationGroups = useMemo(() => {
    const matchedReservationIds = new Set(filteredRows.map((row) => row.reservationId));
    const groups = new Map<number, ReservationGuestGroup>();
    rows.forEach((row) => {
      if (!matchedReservationIds.has(row.reservationId)) return;
      const current = groups.get(row.reservationId);
      if (current) {
        current.rows.push(row);
        return;
      }
      groups.set(row.reservationId, {
        reservationId: row.reservationId,
        reservationCode: row.reservationCode,
        status: row.status,
        checkIn: row.checkIn,
        checkOut: row.checkOut,
        rows: [row],
      });
    });

    const priority: Record<string, number> = { CHECKED_IN: 0, CANCELLATION_PENDING: 1, CONFIRMED: 2, DRAFT: 3, CHECKED_OUT: 4, NO_SHOW: 5 };
    return Array.from(groups.values())
      .map((group) => ({
        ...group,
        rows: [...group.rows].sort((left, right) => Number(right.isPrimary) - Number(left.isPrimary)
          || left.guestName.localeCompare(right.guestName, localeTag)),
      }))
      .sort((left, right) => (priority[left.status] ?? 99) - (priority[right.status] ?? 99)
        || new Date(right.checkIn).getTime() - new Date(left.checkIn).getTime());
  }, [filteredRows, localeTag, rows]);

  const openEditGuest = (row: ReservationGuestRow) => {
    setEditingGuest(row);
    setEditForm({ fullName: row.guestName, phone: row.phone === "-" ? "" : row.phone, email: row.email === "-" ? "" : row.email, idCardType: row.idCardType || "CCCD", idCardNumber: row.idCardNumber || "", nationality: row.nationality || "Vietnam", dateOfBirth: row.dateOfBirth || "" });
  };

  const saveGuest = async () => {
    if (!editingGuest || !editForm.fullName.trim()) return;
    setIsSaving(true);
    try {
      await apiClient.patch(`/api/guests/${editingGuest.id}`, { ...editForm, fullName: editForm.fullName.trim(), phone: editForm.phone || undefined, email: editForm.email || undefined, idCardNumber: editForm.idCardNumber || undefined, dateOfBirth: editForm.dateOfBirth || undefined, isPrimary: editingGuest.isPrimary });
      setEditingGuest(null);
      setToast({ message: "Đã cập nhật thông tin khách lưu trú", type: "success" });
      await fetchGuests();
    } catch (error: unknown) {
      setToast({ message: getApiErrorMessage(error, "Không thể cập nhật thông tin khách"), type: "error" });
    } finally {
      setIsSaving(false);
    }
  };

  const reservationIdsForStatuses = (statusesToCount: string[]) => new Set(
    rows.filter((row) => statusesToCount.includes(row.status)).map((row) => row.reservationId),
  ).size;
  const stayingCount = reservationIdsForStatuses(["CHECKED_IN"]);
  const upcomingCount = reservationIdsForStatuses(["DRAFT", "CONFIRMED", "CANCELLATION_PENDING"]);
  const checkedOutCount = reservationIdsForStatuses(["CHECKED_OUT"]);
  const reservationCount = new Set(rows.map((row) => row.reservationId)).size;
  const statusLabel = (status: string) => (locale === "vi"
    ? { DRAFT: "Chờ xác nhận", CONFIRMED: "Đã xác nhận", CANCELLATION_PENDING: "Chờ duyệt hủy", CANCELLED: "Đã hủy", CHECKED_IN: "Đang lưu trú", CHECKED_OUT: "Đã trả phòng", NO_SHOW: "Không đến" }
    : { DRAFT: "Awaiting confirmation", CONFIRMED: "Confirmed", CANCELLATION_PENDING: "Cancellation pending", CANCELLED: "Cancelled", CHECKED_IN: "Checked in", CHECKED_OUT: "Checked out", NO_SHOW: "No-show" })[status] || status;
  const statusClass = (status: string) => status === "CHECKED_IN" ? "border-emerald-200 bg-emerald-50 text-emerald-700" : status === "CHECKED_OUT" ? "border-slate-200 bg-slate-50 text-slate-700" : status === "CONFIRMED" ? "border-blue-200 bg-blue-50 text-blue-700" : status === "CANCELLED" || status === "NO_SHOW" ? "border-rose-200 bg-rose-50 text-rose-700" : status === "CANCELLATION_PENDING" ? "border-violet-200 bg-violet-50 text-violet-700" : "border-[#D8C398] bg-[#EAE2D2] text-[#66727C]";

  const renderGuestActions = (row: ReservationGuestRow, includeReservationLink = true) => (
    <div className="flex flex-wrap justify-end gap-2">
      {row.source === "STAY_GUEST" && <button type="button" onClick={() => openEditGuest(row)} className="min-h-10 rounded-lg bg-[#0F2A43] px-3 text-xs font-bold text-white hover:bg-[#091E30]">{localize("Sửa thông tin", "Edit guest")}</button>}
      {includeReservationLink && <Link href={`/dashboard/reservations?reservationId=${row.reservationId}`} className="inline-flex min-h-10 items-center rounded-lg border border-[#0F2A43]/20 bg-white px-3 text-xs font-bold text-[#0F2A43] hover:bg-[#E5E9ED]">
        {localize("Xem đơn", "View reservation")}
      </Link>}
    </div>
  );

  return (
    <div className="ops-page mx-auto w-full max-w-[1600px] space-y-6 p-4 sm:p-6 lg:p-8">
      <div className="flex flex-col gap-4 border-b border-[#0F2A43]/5 pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-xs font-bold uppercase tracking-[0.18em] text-[#80632F]">{localize("Khách & lưu trú", "Guests & stays")}</p>
          <h1 className="mt-2 text-3xl font-bold leading-tight tracking-tight text-[#0F2A43]">{localize("Danh sách khách", "Guest directory")}</h1>
          <p className="mt-1.5 text-sm text-[#66727C]">{localize("Bao gồm khách đại diện của đơn sắp đến, khách đang lưu trú và lịch sử đã trả phòng.", "Includes booking contacts for upcoming stays, current guests and checked-out history.")}</p>
        </div>
        <button
          type="button"
          onClick={() => void fetchGuests(false)}
          className="ops-panel-strong self-start rounded-xl border px-5 py-2.5 text-sm font-bold text-[#0F2A43] transition hover:bg-[var(--ops-surface-muted)]"
        >
          {localize("Làm mới dữ liệu", "Refresh data")}
        </button>
      </div>

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        {(locale === "vi" ? [['Đơn có khách', reservationCount], ['Sắp đến / chờ xử lý', upcomingCount], ['Đang lưu trú', stayingCount], ['Đã trả phòng', checkedOutCount]] : [['Reservations with guests', reservationCount], ['Upcoming / pending', upcomingCount], ['Currently staying', stayingCount], ['Checked out', checkedOutCount]]).map(([label, value]) => <div key={String(label)} className="flex min-h-28 flex-col justify-between rounded-xl border border-[#0F2A43]/10 bg-white p-5"><span className="text-xs font-semibold leading-5 text-[#66727C]">{label}</span><p className="mt-2 text-3xl font-bold tabular-nums text-[#0F2A43]">{value}</p></div>)}
      </div>

      <DashboardFilterPanel
        title={localize("Bộ lọc hồ sơ khách", "Guest filters")}
        description={localize("Tra cứu khách theo thông tin liên hệ, mã đơn và trạng thái lưu trú", "Find guests by contact details, reservation code and stay status")}
        resultCount={filteredReservationGroups.length}
        resultLabel={localize("đơn phù hợp", "matching reservations")}
        resultNote={localize(`${filteredRows.length} hồ sơ khách · đơn đang lưu trú được ưu tiên`, `${filteredRows.length} guest records · current stays appear first`)}
        hasActiveFilters={Boolean(searchQuery || statusFilter !== "All")}
        activeFilterCount={Number(Boolean(searchQuery)) + Number(statusFilter !== "All")}
        activeFilterLabel={localize("bộ lọc đang dùng", "active filters")}
        onReset={() => {
          setSearchQuery("");
          setStatusFilter("All");
        }}
        resetLabel={localize("Xóa toàn bộ bộ lọc", "Clear all filters")}
        actions={(
          <>
            <FilterQuickButton active={statusFilter === "CHECKED_IN"} onClick={() => setStatusFilter((current) => current === "CHECKED_IN" ? "All" : "CHECKED_IN")}>
              {localize("Đang lưu trú", "Currently staying")}
            </FilterQuickButton>
            <FilterQuickButton active={statusFilter === "CHECKED_OUT"} onClick={() => setStatusFilter((current) => current === "CHECKED_OUT" ? "All" : "CHECKED_OUT")}>
              {localize("Đã trả phòng", "Checked out")}
            </FilterQuickButton>
          </>
        )}
      >
        <div className="grid gap-4 md:grid-cols-[minmax(0,2fr)_minmax(14rem,1fr)]">
          <DashboardSearchField
            id="guest-search"
            label={localize("Tìm kiếm", "Search")}
            value={searchQuery}
            onChange={setSearchQuery}
            placeholder={localize("Tên khách, điện thoại, email hoặc mã đơn...", "Guest name, phone, email or reservation code...")}
            clearLabel={localize("Xóa từ khóa", "Clear search")}
          />
          <DashboardSelectField
            id="guest-status"
            label={localize("Trạng thái lưu trú", "Stay status")}
            value={statusFilter}
            onChange={(event) => setStatusFilter(event.target.value)}
          >
            {statuses.map((status) => (
              <option key={status} value={status}>{status === "All" ? localize("Tất cả", "All") : statusLabel(status)}</option>
            ))}
          </DashboardSelectField>
        </div>
      </DashboardFilterPanel>

      <section className="overflow-hidden rounded-xl border border-[#0F2A43]/10 bg-white" aria-labelledby="guest-list-title">

      <div className="flex flex-col gap-2 border-b border-[#0F2A43]/10 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
        <div><h2 id="guest-list-title" className="font-bold text-[#0F2A43]">{localize("Khách được nhóm theo đơn đặt phòng", "Guests grouped by reservation")}</h2><p className="mt-0.5 text-xs text-[#66727C]">{filteredReservationGroups.length} {localize(`đơn · ${filteredRows.length} hồ sơ khách`, `reservations · ${filteredRows.length} guest records`)}</p></div>
        <span className="text-xs font-semibold text-[#66727C] sm:text-right">{localize("Mỗi khối là một đơn; phòng và khách nằm cùng một nhóm", "Each block is one reservation with its rooms and guests")}</span>
      </div>

      {loadError && <div className="m-4 rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm font-medium text-rose-700" role="alert">{loadError}</div>}
        {isLoading ? (
          <div className="space-y-3 p-4" role="status" aria-label={localize("Đang tải dữ liệu khách", "Loading guest data")}>{[1, 2, 3].map((item) => <div key={item} className="h-20 animate-pulse rounded-lg bg-[#F1F0EA]" />)}</div>
        ) : filteredReservationGroups.length === 0 ? (
          <div className="px-6 py-14 text-center"><p className="font-bold text-[#0F2A43]">{localize("Không có khách phù hợp", "No matching guests")}</p><p className="mt-2 text-sm text-[#66727C]">{localize("Thử thay đổi từ khóa hoặc bộ lọc trạng thái.", "Try a different keyword or status filter.")}</p></div>
        ) : (
          <div className="space-y-2 bg-[#F7F4EC]/55 p-3">
            {filteredReservationGroups.map((group) => {
              const roomNames = Array.from(new Set(
                group.rows.map((row) => row.roomName).filter((roomName): roomName is string => Boolean(roomName)),
              )).sort((left, right) => left.localeCompare(right, undefined, { numeric: true }));
              const primaryGuest = group.rows.find((row) => row.isPrimary) || group.rows[0];
              const isExpanded = expandedReservationId === group.reservationId;
              const titleId = `guest-reservation-${group.reservationId}`;
              const detailsId = `${titleId}-details`;
              return (
                <article key={group.reservationId} className="overflow-hidden rounded-lg border border-[#0F2A43]/12 bg-white shadow-[0_4px_14px_rgba(15,42,67,0.04)]" aria-labelledby={titleId}>
                  <header className={`flex items-stretch ${group.status === "CHECKED_IN" ? "bg-emerald-50/65" : "bg-white"}`}>
                    <button
                      type="button"
                      aria-expanded={isExpanded}
                      aria-controls={detailsId}
                      onClick={() => setExpandedReservationId((current) => current === group.reservationId ? null : group.reservationId)}
                      className="group grid min-h-[72px] min-w-0 flex-1 cursor-pointer grid-cols-[minmax(0,1fr)_auto] gap-3 px-4 py-3 text-left transition hover:bg-[#F1F0EA]/65 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[#B8944F] xl:grid-cols-[minmax(11rem,1fr)_minmax(10rem,1.05fr)_minmax(10rem,0.95fr)_minmax(7rem,0.65fr)_auto] xl:items-center"
                    >
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <h3 id={titleId} className="truncate font-mono text-sm font-black text-[#0F2A43]">{group.reservationCode || `#${group.reservationId}`}</h3>
                          <span className={`inline-flex rounded-md border px-2 py-0.5 text-[10px] font-bold ${statusClass(group.status)}`}>{statusLabel(group.status)}</span>
                        </div>
                        <p className="mt-1 truncate text-[11px] font-semibold text-[#80632F]">
                          {group.rows.length} {localize("hồ sơ khách", "guest records")}
                          <span className="xl:hidden"> · {formatDate(group.checkIn, localeTag)} → {formatDate(group.checkOut, localeTag)}</span>
                        </p>
                      </div>

                      <div className="min-w-0 text-xs">
                        <p className="text-[10px] font-semibold uppercase tracking-[0.08em] text-[#66727C]">{localize("Khách chính", "Primary guest")}</p>
                        <p className="mt-1 truncate font-bold text-[#0F2A43]">{primaryGuest?.guestName || "-"}</p>
                        <p className="truncate text-[11px] text-[#66727C]">{primaryGuest?.phone || "-"}</p>
                      </div>

                      <div className="hidden text-xs xl:block">
                        <p className="text-[10px] font-semibold uppercase tracking-[0.08em] text-[#66727C]">{localize("Kỳ lưu trú", "Stay period")}</p>
                        <p className="mt-1 whitespace-nowrap font-bold text-[#0F2A43]">{formatDate(group.checkIn, localeTag)} → {formatDate(group.checkOut, localeTag)}</p>
                      </div>

                      <div className="text-xs">
                        <p className="text-[10px] font-semibold uppercase tracking-[0.08em] text-[#66727C]">{localize("Phòng", "Rooms")}</p>
                        <div className="mt-1 flex flex-wrap gap-1">{roomNames.length > 0 ? roomNames.map((roomName) => <span key={roomName} className="rounded border border-emerald-200 bg-white px-1.5 py-0.5 font-mono font-bold text-emerald-800">#{roomName}</span>) : <span className="font-semibold text-[#66727C]">{localize("Chưa gán", "Unassigned")}</span>}</div>
                      </div>

                      <span className={`col-start-2 row-start-1 flex h-9 w-9 items-center justify-center justify-self-end rounded-full border border-[#0F2A43]/15 bg-white text-[#0F2A43] transition group-hover:border-[#B8944F] group-hover:bg-[#FBFAF6] xl:col-auto xl:row-auto ${isExpanded ? "rotate-180" : ""}`} aria-hidden="true">
                        <svg viewBox="0 0 24 24" className="h-4 w-4 fill-none stroke-current" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6" /></svg>
                      </span>
                    </button>

                    <div className="flex shrink-0 items-center border-l border-[#0F2A43]/10 px-3">
                      <Link href={`/dashboard/reservations?reservationId=${group.reservationId}`} className="inline-flex min-h-10 items-center rounded-lg border border-[#0F2A43]/20 bg-white px-3 text-xs font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#FBFAF6] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#B8944F]">
                        {localize("Xem đơn", "View")}
                      </Link>
                    </div>
                  </header>

                  {isExpanded && <div id={detailsId} className="divide-y divide-[#0F2A43]/8 border-t border-[#0F2A43]/10 bg-[#FBFAF6]">
                    {group.rows.map((row) => (
                      <section key={row.id} className="grid gap-2 px-4 py-3 transition hover:bg-white sm:grid-cols-[minmax(12rem,1.25fr)_minmax(10rem,0.9fr)_minmax(9rem,0.75fr)_auto] sm:items-center">
                        <div>
                          <div className="flex flex-wrap items-center gap-2">
                            <p className="font-bold text-[#0F2A43]">{row.guestName}</p>
                            <span className={`rounded-lg border px-2 py-1 text-[10px] font-bold ${row.source === "BOOKING_CONTACT" ? "border-amber-200 bg-amber-50 text-amber-800" : "border-[#0F2A43]/15 bg-[#E5E9ED] text-[#0F2A43]"}`}>{row.source === "BOOKING_CONTACT" ? localize("Người đặt phòng", "Booking contact") : row.isPrimary ? localize("Khách chính", "Primary guest") : localize("Khách cùng phòng", "Additional guest")}</span>
                          </div>
                          <p className="mt-1 text-xs text-[#66727C]">{row.phone} · {row.email}</p>
                        </div>
                        <div className="text-xs"><p className="font-semibold text-[#66727C]">{localize("Phòng", "Room")}</p><p className="mt-1 font-bold text-[#0F2A43]">{row.roomSummary}</p></div>
                        <div className="text-xs"><p className="font-semibold text-[#66727C]">{localize("Giấy tờ", "Identity document")}</p><p className="mt-1 font-bold text-[#0F2A43]">{row.source === "STAY_GUEST" ? row.idCard : localize(`${row.guestCount} khách khai báo`, `${row.guestCount} declared guests`)}</p></div>
                        {row.source === "STAY_GUEST" && <div className="md:justify-self-end">{renderGuestActions(row, false)}</div>}
                      </section>
                    ))}
                  </div>}
                </article>
              );
            })}
          </div>
        )}
      </section>

      {editingGuest && (
        <ViewportModal
          open
          onClose={() => setEditingGuest(null)}
          labelledBy="edit-guest-title"
          busy={isSaving}
          panelClassName="max-w-2xl"
        >
            <header className="border-b border-[#0F2A43]/10 px-6 py-5"><p className="text-xs font-bold uppercase tracking-[0.14em] text-[#80632F]">Hồ sơ khách lưu trú</p><h2 id="edit-guest-title" className="mt-1 text-xl font-bold text-[#0F2A43]">Chỉnh sửa thông tin khách</h2><p className="mt-1 text-sm text-[#66727C]">Reservation {editingGuest.reservationCode} · {editingGuest.roomSummary}</p></header>
            <div className="grid min-h-0 flex-1 gap-4 overflow-y-auto px-6 py-5 sm:grid-cols-2">
              <label className="text-sm font-semibold text-[#0F2A43] sm:col-span-2">Họ và tên *<input data-modal-autofocus value={editForm.fullName} onChange={(e) => setEditForm({ ...editForm, fullName: e.target.value })} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal outline-none focus:border-[#B8944F]" /></label>
              <label className="text-sm font-semibold text-[#0F2A43]">Số điện thoại<input value={editForm.phone} onChange={(e) => setEditForm({ ...editForm, phone: e.target.value })} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal outline-none focus:border-[#B8944F]" /></label>
              <label className="text-sm font-semibold text-[#0F2A43]">Email<input type="email" value={editForm.email} onChange={(e) => setEditForm({ ...editForm, email: e.target.value })} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal outline-none focus:border-[#B8944F]" /></label>
              <label className="text-sm font-semibold text-[#0F2A43]">Loại giấy tờ<select value={editForm.idCardType} onChange={(e) => setEditForm({ ...editForm, idCardType: e.target.value })} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal"><option value="CCCD">CCCD</option><option value="CMND">CMND</option><option value="PASSPORT">Hộ chiếu</option></select></label>
              <label className="text-sm font-semibold text-[#0F2A43]">Số giấy tờ<input value={editForm.idCardNumber} onChange={(e) => setEditForm({ ...editForm, idCardNumber: e.target.value })} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal outline-none focus:border-[#B8944F]" /></label>
              <label className="text-sm font-semibold text-[#0F2A43]">Ngày sinh<input type="date" value={editForm.dateOfBirth} onChange={(e) => setEditForm({ ...editForm, dateOfBirth: e.target.value })} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal" /></label>
              <label className="text-sm font-semibold text-[#0F2A43]">Quốc tịch<input value={editForm.nationality} onChange={(e) => setEditForm({ ...editForm, nationality: e.target.value })} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal outline-none focus:border-[#B8944F]" /></label>
            </div>
            <footer className="flex justify-end gap-3 border-t border-[#0F2A43]/10 px-6 py-4"><button disabled={isSaving} onClick={() => setEditingGuest(null)} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43]">Hủy</button><button disabled={isSaving || !editForm.fullName.trim()} onClick={saveGuest} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white disabled:opacity-50">{isSaving ? "Đang lưu..." : "Lưu thay đổi"}</button></footer>
        </ViewportModal>
      )}

      {toast && <Toast message={toast.message} type={toast.type} onClose={() => setToast(null)} />}
    </div>
  );
}
