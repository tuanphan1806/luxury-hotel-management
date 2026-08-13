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
import DashboardTimeGroupingControl from "@/components/dashboard/DashboardTimeGroupingControl";
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from "@/lib/idempotency";
import {
  DashboardTimeGrouping,
  DashboardTimeScope,
  formatDashboardTimeGroupLabel,
  groupByCalendarTime,
  matchesIntervalTimeScope,
} from "@/lib/dashboard-time";

interface ReservationGuestRow {
  id: number | string;
  reservationId: number;
  reservationRoomId?: number;
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
  checkedOutAt?: string;
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
  actualTotalAmount?: number;
  extraGuestCharge?: number;
  roomTypes?: Array<{
    id: number;
    roomTypeId: number;
    roomTypeName?: string;
    roomTypeNameEn?: string;
    quantity?: number;
    includedGuestsPerRoom?: number;
    maxGuestsPerRoom?: number;
    extraGuestPricePerCycle?: number;
  }>;
  rooms?: Array<{
    id: number;
    reservationRoomTypeId: number;
    roomId?: number;
    roomName?: string;
    status?: string;
  }>;
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
  checkedOutAt?: string;
}

interface ReservationGuestGroup {
  reservationId: number;
  reservationCode: string;
  status: string;
  checkIn: string;
  checkOut: string;
  rows: ReservationGuestRow[];
  stayRooms: StayRoomOption[];
}

interface StayRoomOption {
  reservationRoomId: number;
  reservationRoomTypeId: number;
  roomName: string;
  roomTypeName: string;
  actualGuests: number;
  lineActualGuests: number;
  includedGuestsPerRoom: number;
  includedGuestCapacity: number;
  maxGuestsPerRoom: number;
  extraGuestPricePerCycle: number;
}

interface AddGuestContext {
  reservationId: number;
  reservationCode: string;
  rooms: StayRoomOption[];
}

interface MoveGuestContext {
  guest: ReservationGuestRow;
  rooms: StayRoomOption[];
}

const emptyAddGuestForm = {
  reservationRoomId: "",
  fullName: "",
  phone: "",
  email: "",
  idCardType: "CCCD",
  idCardNumber: "",
  nationality: "Vietnam",
  dateOfBirth: "",
};

const emptyMoveGuestForm = {
  targetReservationRoomId: "",
  reason: "",
};

const getApiErrorMessage = (error: unknown, fallback: string) => {
  if (typeof error !== "object" || error === null || !("response" in error)) return fallback;
  const response = (error as { response?: { data?: { message?: unknown } } }).response;
  return typeof response?.data?.message === "string" ? response.data.message : fallback;
};

const createOperationId = () => typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
  ? crypto.randomUUID()
  : `${Date.now()}-${Math.random().toString(16).slice(2)}`;

const toLocalDateInputValue = (date: Date) => {
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 10);
};

const todayLocalDate = () => toLocalDateInputValue(new Date());

const latestBirthDate = () => {
  const yesterday = new Date();
  yesterday.setDate(yesterday.getDate() - 1);
  return toLocalDateInputValue(yesterday);
};

const validateGuestForm = (form: typeof emptyAddGuestForm) => {
  const fullName = form.fullName.trim();
  const phone = form.phone.trim();
  const email = form.email.trim();
  const documentNumber = form.idCardNumber.trim();
  if (fullName.length < 2) return "Họ tên khách phải có ít nhất 2 ký tự.";
  if (fullName.length > 100) return "Họ tên khách không được quá 100 ký tự.";
  if (phone.length > 20) return "Số điện thoại không được quá 20 ký tự.";
  if (phone && !/^(?:0|\+84)[0-9]{9,10}$/.test(phone.replace(/[\s().-]/g, ""))) {
    return "Số điện thoại khách không hợp lệ.";
  }
  if (email.length > 254) return "Email không được quá 254 ký tự.";
  if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return "Email khách không đúng định dạng.";
  if (documentNumber && documentNumber.length < 4) return "Số giấy tờ phải có ít nhất 4 ký tự.";
  if (documentNumber.length > 50) return "Số giấy tờ không được quá 50 ký tự.";
  if (form.nationality.trim().length > 100) return "Quốc tịch không được quá 100 ký tự.";
  if (form.dateOfBirth && form.dateOfBirth >= todayLocalDate()) return "Ngày sinh phải nằm trong quá khứ.";
  return "";
};

const formatDate = (value?: string, localeTag = "vi-VN") => {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString(localeTag, { day: "2-digit", month: "2-digit", year: "numeric" });
};

const reservationDashboardHref = (reservationCode: string, reservationId: number) => {
  const lookup = reservationCode
    ? `reservationCode=${encodeURIComponent(reservationCode)}`
    : `reservationId=${reservationId}`;
  return `/dashboard/reservations?${lookup}#reservation-list-title`;
};

export default function DashboardGuestPage() {
  const { locale, localeTag, localize } = useLanguage();
  const [rows, setRows] = useState<ReservationGuestRow[]>([]);
  const [searchQuery, setSearchQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("All");
  const [timeScope, setTimeScope] = useState<DashboardTimeScope>("ALL");
  const [timeGrouping, setTimeGrouping] = useState<DashboardTimeGrouping>("DAY");
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [toast, setToast] = useState<{ message: string; type: "success" | "error" | "info" } | null>(null);
  const [editingGuest, setEditingGuest] = useState<ReservationGuestRow | null>(null);
  const [addingGuest, setAddingGuest] = useState<AddGuestContext | null>(null);
  const [movingGuest, setMovingGuest] = useState<MoveGuestContext | null>(null);
  const [addGuestForm, setAddGuestForm] = useState(emptyAddGuestForm);
  const [addGuestError, setAddGuestError] = useState("");
  const [moveGuestForm, setMoveGuestForm] = useState(emptyMoveGuestForm);
  const [moveGuestError, setMoveGuestError] = useState("");
  const [expandedReservationId, setExpandedReservationId] = useState<number | null>(null);
  const [editForm, setEditForm] = useState({ fullName: "", phone: "", email: "", idCardType: "CCCD", idCardNumber: "", nationality: "Vietnam", dateOfBirth: "" });
  const [isSaving, setIsSaving] = useState(false);
  const [isAddingGuest, setIsAddingGuest] = useState(false);
  const [isMovingGuest, setIsMovingGuest] = useState(false);
  const [stayRoomsByReservation, setStayRoomsByReservation] = useState<Record<number, StayRoomOption[]>>({});
  const [addGuestOperationId, setAddGuestOperationId] = useState("");
  const [moveGuestOperationId, setMoveGuestOperationId] = useState("");

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
      const guestCountByReservationRoom = allGuests.reduce<Record<number, number>>((counts, guest) => {
        if (guest.reservationRoomId && !guest.checkedOutAt) counts[guest.reservationRoomId] = (counts[guest.reservationRoomId] || 0) + 1;
        return counts;
      }, {});
      const reservationRoomIndex = reservations.reduce<Record<number, { reservationId: number; reservationRoomTypeId: number }>>((index, reservation) => {
        for (const room of reservation.rooms || []) {
          index[room.id] = {
            reservationId: reservation.id,
            reservationRoomTypeId: room.reservationRoomTypeId,
          };
        }
        return index;
      }, {});
      const guestCountByReservationLine = allGuests.reduce<Record<number, number>>((counts, guest) => {
        if (!guest.reservationRoomId || guest.checkedOutAt) return counts;
        const assignedRoom = reservationRoomIndex[guest.reservationRoomId];
        if (!assignedRoom || guest.reservationId !== assignedRoom.reservationId) return counts;
        counts[assignedRoom.reservationRoomTypeId] = (counts[assignedRoom.reservationRoomTypeId] || 0) + 1;
        return counts;
      }, {});
      const nextStayRooms = reservations.reduce<Record<number, StayRoomOption[]>>((result, reservation) => {
        const roomTypeLines = new Map((reservation.roomTypes || []).map((line) => [line.id, line]));
        result[reservation.id] = (reservation.rooms || [])
          .filter((room) => room.id && room.status === "CHECKED_IN")
          .map((room) => {
            const line = roomTypeLines.get(room.reservationRoomTypeId);
            const includedGuestsPerRoom = Math.max(1, Number(line?.includedGuestsPerRoom || 1));
            const quantity = Math.max(1, Number(line?.quantity || 1));
            return {
              reservationRoomId: room.id,
              reservationRoomTypeId: room.reservationRoomTypeId,
              roomName: room.roomName || `#${room.roomId || room.id}`,
              roomTypeName: locale === "en" ? line?.roomTypeNameEn || line?.roomTypeName || "Room" : line?.roomTypeName || "Hạng phòng",
              actualGuests: guestCountByReservationRoom[room.id] || 0,
              lineActualGuests: guestCountByReservationLine[room.reservationRoomTypeId] || 0,
              includedGuestsPerRoom,
              includedGuestCapacity: includedGuestsPerRoom * quantity,
              maxGuestsPerRoom: Math.max(includedGuestsPerRoom, Number(line?.maxGuestsPerRoom || includedGuestsPerRoom)),
              extraGuestPricePerCycle: Math.max(0, Number(line?.extraGuestPricePerCycle || 0)),
            };
          });
        return result;
      }, {});
      setStayRoomsByReservation(nextStayRooms);
      const guestRows = reservations.flatMap<ReservationGuestRow>((reservation) => {
        const guests = guestsByReservation[reservation.id] || [];
        if (!guests.length) {
          return [{
            id: `reservation-${reservation.id}`,
            reservationId: reservation.id,
            reservationRoomId: undefined,
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
          reservationRoomId: guest.reservationRoomId,
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
          checkedOutAt: guest.checkedOutAt,
        }));
      });
      setRows(guestRows);
    } catch {
      setRows([]);
      setStayRoomsByReservation({});
      setLoadError(localize("Không thể tải danh sách khách từ dữ liệu đặt phòng.", "Could not load guests from reservation data."));
    } finally {
      setIsLoading(false);
    }
  }, [locale, localize]);

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
        const matchesTime = matchesIntervalTimeScope(row.checkIn, row.checkOut, timeScope);
        return matchesStatus && matchesSearch && matchesTime;
      })
      .sort((left, right) => (priority[left.status] ?? 99) - (priority[right.status] ?? 99)
        || Number(right.isPrimary) - Number(left.isPrimary)
        || new Date(right.checkIn).getTime() - new Date(left.checkIn).getTime());
  }, [rows, searchQuery, statusFilter, timeScope]);

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
        stayRooms: stayRoomsByReservation[row.reservationId] || [],
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
  }, [filteredRows, localeTag, rows, stayRoomsByReservation]);

  const timeGroupedReservationGroups = useMemo(
    () => groupByCalendarTime(filteredReservationGroups, (group) => group.checkIn, timeGrouping),
    [filteredReservationGroups, timeGrouping],
  );

  const timeGroupLabel = (group: (typeof timeGroupedReservationGroups)[number]) => formatDashboardTimeGroupLabel(
    group,
    timeGrouping,
    localeTag,
    localize("Tuần", "Week"),
    localize("Chưa xác định ngày nhận phòng", "Unknown arrival date"),
  );

  const openEditGuest = (row: ReservationGuestRow) => {
    setEditingGuest(row);
    setEditForm({ fullName: row.guestName, phone: row.phone === "-" ? "" : row.phone, email: row.email === "-" ? "" : row.email, idCardType: row.idCardType || "CCCD", idCardNumber: row.idCardNumber || "", nationality: row.nationality || "Vietnam", dateOfBirth: row.dateOfBirth || "" });
  };

  const saveGuest = async () => {
    if (!editingGuest) return;
    const validationError = validateGuestForm({ ...emptyAddGuestForm, ...editForm });
    if (validationError) {
      setToast({ message: validationError, type: "error" });
      return;
    }
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

  const openAddGuest = (group: ReservationGuestGroup) => {
    const roomsWithCapacity = group.stayRooms.filter((room) => room.actualGuests < room.maxGuestsPerRoom);
    if (roomsWithCapacity.length === 0) {
      setToast({ message: "Tất cả phòng trong đơn đã đủ sức chứa", type: "error" });
      return;
    }
    setAddingGuest({ reservationId: group.reservationId, reservationCode: group.reservationCode, rooms: group.stayRooms });
    setAddGuestForm({ ...emptyAddGuestForm, reservationRoomId: String(roomsWithCapacity[0].reservationRoomId) });
    setAddGuestOperationId(createOperationId());
    setAddGuestError("");
  };

  const selectedStayRoom = addingGuest?.rooms.find((room) => String(room.reservationRoomId) === addGuestForm.reservationRoomId);

  const openMoveGuest = (row: ReservationGuestRow) => {
    const rooms = (stayRoomsByReservation[row.reservationId] || [])
      .filter((room) => room.reservationRoomId !== row.reservationRoomId && room.actualGuests < room.maxGuestsPerRoom);
    if (rooms.length === 0) {
      setToast({ message: "Đơn không còn phòng khác có sức chứa để chuyển khách", type: "error" });
      return;
    }
    setMovingGuest({ guest: row, rooms });
    setMoveGuestForm({ targetReservationRoomId: String(rooms[0].reservationRoomId), reason: "" });
    setMoveGuestOperationId(createOperationId());
    setMoveGuestError("");
  };

  const submitMoveGuest = async () => {
    if (!movingGuest) return;
    const reason = moveGuestForm.reason.trim();
    if (!moveGuestForm.targetReservationRoomId) {
      setMoveGuestError("Vui lòng chọn phòng đích.");
      return;
    }
    if (reason.length < 5) {
      setMoveGuestError("Vui lòng nhập lý do chuyển phòng từ 5 ký tự.");
      return;
    }
    setIsMovingGuest(true);
    setMoveGuestError("");
    const scope = `stay-guest-move:${movingGuest.guest.reservationId}:${movingGuest.guest.id}:${moveGuestOperationId}`;
    try {
      await apiClient.patch(
        `/api/reservations/${movingGuest.guest.reservationId}/stay-guests/${movingGuest.guest.id}/room`,
        { targetReservationRoomId: Number(moveGuestForm.targetReservationRoomId), reason },
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } },
      );
      clearIdempotencyKey(scope);
      setMovingGuest(null);
      setToast({ message: "Đã chuyển khách và cập nhật lại chi phí theo phòng", type: "success" });
      await fetchGuests(false);
    } catch (error: unknown) {
      setMoveGuestError(getApiErrorMessage(error, "Không thể chuyển khách sang phòng đã chọn"));
    } finally {
      setIsMovingGuest(false);
    }
  };

  const submitAddGuest = async () => {
    if (!addingGuest) return;
    if (!addGuestForm.reservationRoomId) {
      setAddGuestError("Vui lòng chọn phòng cho khách.");
      return;
    }
    const validationError = validateGuestForm(addGuestForm);
    if (validationError) {
      setAddGuestError(validationError);
      return;
    }
    if (selectedStayRoom && selectedStayRoom.actualGuests >= selectedStayRoom.maxGuestsPerRoom) {
      setAddGuestError("Phòng đã đủ sức chứa; hãy chọn phòng khác.");
      return;
    }
    setIsAddingGuest(true);
    setAddGuestError("");
    const operationScope = `stay-guest-add:${addingGuest.reservationId}:${addGuestOperationId}`;
    try {
      const response = await apiClient.post(`/api/reservations/${addingGuest.reservationId}/stay-guests`, {
        reservationRoomId: Number(addGuestForm.reservationRoomId),
        guest: {
          fullName: addGuestForm.fullName.trim(),
          phone: addGuestForm.phone.trim() || undefined,
          email: addGuestForm.email.trim() || undefined,
          idCardType: addGuestForm.idCardType,
          idCardNumber: addGuestForm.idCardNumber.trim() || undefined,
          nationality: addGuestForm.nationality.trim() || undefined,
          dateOfBirth: addGuestForm.dateOfBirth || undefined,
          isPrimary: false,
        },
      }, { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(operationScope) } });
      clearIdempotencyKey(operationScope);
      const extraGuestCharge = Number(response.data?.data?.extraGuestCharge || 0);
      setAddingGuest(null);
      setToast({
        message: extraGuestCharge > 0
          ? `Đã thêm khách; phụ thu khách thêm hiện tại ${new Intl.NumberFormat("vi-VN").format(extraGuestCharge)} đ`
          : "Đã thêm khách; số khách vẫn nằm trong suất đã gồm giá",
        type: "success",
      });
      await fetchGuests(false);
    } catch (error: unknown) {
      setAddGuestError(getApiErrorMessage(error, "Không thể thêm khách vào phòng"));
    } finally {
      setIsAddingGuest(false);
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
      {row.source === "STAY_GUEST" && row.status === "CHECKED_IN" && !row.isPrimary && !row.checkedOutAt && <button type="button" onClick={() => openMoveGuest(row)} className="min-h-10 rounded-lg border border-[#B8944F]/50 bg-[#F7F4EC] px-3 text-xs font-bold text-[#80632F] transition-colors hover:bg-[#EAE2D2]">{localize("Chuyển phòng", "Move room")}</button>}
      {includeReservationLink && <Link href={reservationDashboardHref(row.reservationCode, row.reservationId)} className="inline-flex min-h-10 items-center rounded-lg border border-[#0F2A43]/20 bg-white px-3 text-xs font-bold text-[#0F2A43] hover:bg-[#E5E9ED]">
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
        hasActiveFilters={Boolean(searchQuery || statusFilter !== "All" || timeScope !== "ALL")}
        activeFilterCount={Number(Boolean(searchQuery)) + Number(statusFilter !== "All") + Number(timeScope !== "ALL")}
        activeFilterLabel={localize("bộ lọc đang dùng", "active filters")}
        onReset={() => {
          setSearchQuery("");
          setStatusFilter("All");
          setTimeScope("ALL");
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
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-[minmax(0,2fr)_minmax(13rem,1fr)_minmax(13rem,1fr)]">
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
          <DashboardSelectField
            id="guest-time-scope"
            label={localize("Thời gian lưu trú", "Stay period")}
            value={timeScope}
            onChange={(event) => setTimeScope(event.target.value as DashboardTimeScope)}
          >
            <option value="ALL">{localize("Tất cả thời gian", "All time")}</option>
            <option value="TODAY">{localize("Hôm nay", "Today")}</option>
            <option value="WEEK">{localize("Tuần này", "This week")}</option>
            <option value="MONTH">{localize("Tháng này", "This month")}</option>
          </DashboardSelectField>
        </div>
      </DashboardFilterPanel>

      <section className="overflow-hidden rounded-xl border border-[#0F2A43]/10 bg-white" aria-labelledby="guest-list-title">

      <div className="flex flex-col gap-2 border-b border-[#0F2A43]/10 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
        <div><h2 id="guest-list-title" className="font-bold text-[#0F2A43]">{localize("Khách được nhóm theo đơn đặt phòng", "Guests grouped by reservation")}</h2><p className="mt-0.5 text-xs text-[#66727C]">{filteredReservationGroups.length} {localize(`đơn · ${filteredRows.length} hồ sơ khách`, `reservations · ${filteredRows.length} guest records`)}</p></div>
        <DashboardTimeGroupingControl
          value={timeGrouping}
          onChange={setTimeGrouping}
          title={localize("Nhóm theo ngày nhận phòng", "Group by arrival")}
          ariaLabel={localize("Nhóm đơn và khách lưu trú theo ngày nhận phòng", "Group reservations and guests by arrival date")}
          labels={{ day: localize("Ngày", "Day"), week: localize("Tuần", "Week"), month: localize("Tháng", "Month") }}
        />
      </div>

      {loadError && <div className="m-4 rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm font-medium text-rose-700" role="alert">{loadError}</div>}
        {isLoading ? (
          <div className="space-y-3 p-4" role="status" aria-label={localize("Đang tải dữ liệu khách", "Loading guest data")}>{[1, 2, 3].map((item) => <div key={item} className="h-20 animate-pulse rounded-lg bg-[#F1F0EA]" />)}</div>
        ) : filteredReservationGroups.length === 0 ? (
          <div className="px-6 py-14 text-center"><p className="font-bold text-[#0F2A43]">{localize("Không có khách phù hợp", "No matching guests")}</p><p className="mt-2 text-sm text-[#66727C]">{localize("Thử thay đổi từ khóa hoặc bộ lọc trạng thái.", "Try a different keyword or status filter.")}</p></div>
        ) : (
          <div className="space-y-4 bg-[#F7F4EC]/55 p-3">
            {timeGroupedReservationGroups.map((timeGroup) => (
              <section key={timeGroup.key} aria-labelledby={`guest-time-group-${timeGroup.key}`}>
                <div className="mb-2 flex min-h-8 items-center justify-between rounded-md border border-[#0F2A43]/8 bg-[#EAE2D2]/70 px-3 py-1.5">
                  <h3 id={`guest-time-group-${timeGroup.key}`} className="text-[11px] font-black uppercase tracking-[0.12em] text-[#80632F]">{timeGroupLabel(timeGroup)}</h3>
                  <span className="text-[11px] font-semibold text-[#66727C]">{timeGroup.items.length} {localize("đơn", "reservations")}</span>
                </div>
                <div className="space-y-2">
            {timeGroup.items.map((group) => {
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

                    <div className="flex shrink-0 flex-col items-stretch justify-center gap-2 border-l border-[#0F2A43]/10 px-3 sm:flex-row sm:items-center">
                      {group.status === "CHECKED_IN" && (
                        <button type="button" onClick={() => openAddGuest(group)} className="inline-flex min-h-10 items-center justify-center rounded-lg bg-[#0F2A43] px-3 text-xs font-bold text-white transition hover:bg-[#091E30] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#B8944F]">
                          {localize("Thêm khách", "Add guest")}
                        </button>
                      )}
                      <Link href={reservationDashboardHref(group.reservationCode, group.reservationId)} className="inline-flex min-h-10 items-center rounded-lg border border-[#0F2A43]/20 bg-white px-3 text-xs font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#FBFAF6] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#B8944F]">
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
              </section>
            ))}
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
              <label className="text-sm font-semibold text-[#0F2A43] sm:col-span-2">Họ và tên *<input data-modal-autofocus maxLength={100} value={editForm.fullName} onChange={(e) => setEditForm({ ...editForm, fullName: e.target.value })} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal outline-none focus:border-[#B8944F]" /></label>
              <label className="text-sm font-semibold text-[#0F2A43]">Số điện thoại<input type="tel" inputMode="tel" maxLength={20} value={editForm.phone} onChange={(e) => setEditForm({ ...editForm, phone: e.target.value })} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal outline-none focus:border-[#B8944F]" /></label>
              <label className="text-sm font-semibold text-[#0F2A43]">Email<input type="email" maxLength={254} value={editForm.email} onChange={(e) => setEditForm({ ...editForm, email: e.target.value })} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal outline-none focus:border-[#B8944F]" /></label>
              <label className="text-sm font-semibold text-[#0F2A43]">Loại giấy tờ<select value={editForm.idCardType} onChange={(e) => setEditForm({ ...editForm, idCardType: e.target.value })} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal"><option value="CCCD">CCCD</option><option value="CMND">CMND</option><option value="PASSPORT">Hộ chiếu</option></select></label>
              <label className="text-sm font-semibold text-[#0F2A43]">Số giấy tờ<input maxLength={50} value={editForm.idCardNumber} onChange={(e) => setEditForm({ ...editForm, idCardNumber: e.target.value })} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal outline-none focus:border-[#B8944F]" /></label>
              <label className="text-sm font-semibold text-[#0F2A43]">Ngày sinh<input type="date" max={latestBirthDate()} value={editForm.dateOfBirth} onChange={(e) => setEditForm({ ...editForm, dateOfBirth: e.target.value })} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal" /></label>
              <label className="text-sm font-semibold text-[#0F2A43]">Quốc tịch<input maxLength={100} value={editForm.nationality} onChange={(e) => setEditForm({ ...editForm, nationality: e.target.value })} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal outline-none focus:border-[#B8944F]" /></label>
            </div>
            <footer className="flex justify-end gap-3 border-t border-[#0F2A43]/10 px-6 py-4"><button disabled={isSaving} onClick={() => setEditingGuest(null)} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43]">Hủy</button><button disabled={isSaving || !editForm.fullName.trim()} onClick={saveGuest} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white disabled:opacity-50">{isSaving ? "Đang lưu..." : "Lưu thay đổi"}</button></footer>
        </ViewportModal>
      )}

      {addingGuest && (
        <ViewportModal
          open
          onClose={() => setAddingGuest(null)}
          labelledBy="add-stay-guest-title"
          busy={isAddingGuest}
          panelClassName="max-w-2xl"
        >
          <header className="border-b border-[#0F2A43]/10 px-6 py-5">
            <p className="text-xs font-bold uppercase tracking-[0.14em] text-[#80632F]">Khách phát sinh trong kỳ lưu trú</p>
            <h2 id="add-stay-guest-title" className="mt-1 text-xl font-bold text-[#0F2A43]">Thêm khách vào phòng</h2>
            <p className="mt-1 text-sm text-[#66727C]">Đơn {addingGuest.reservationCode} · hệ thống tự kiểm tra sức chứa và tính phụ thu theo hạng phòng.</p>
          </header>
          <div className="grid min-h-0 flex-1 gap-4 overflow-y-auto px-6 py-5 sm:grid-cols-2">
            <label className="text-sm font-semibold text-[#0F2A43] sm:col-span-2">Phòng lưu trú *
              <select data-modal-autofocus value={addGuestForm.reservationRoomId} onChange={(event) => { setAddGuestForm({ ...addGuestForm, reservationRoomId: event.target.value }); setAddGuestError(""); }} className="mt-2 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-4 font-normal outline-none focus:border-[#B8944F]">
                {addingGuest.rooms.map((room) => (
                  <option key={room.reservationRoomId} value={room.reservationRoomId} disabled={room.actualGuests >= room.maxGuestsPerRoom}>
                    Phòng #{room.roomName} · {room.roomTypeName} · {room.actualGuests}/{room.maxGuestsPerRoom} khách{room.actualGuests >= room.maxGuestsPerRoom ? " · Đã đủ" : ""}
                  </option>
                ))}
              </select>
            </label>
            {selectedStayRoom && (
              <div className="rounded-xl border border-[#B8944F]/25 bg-[#F7F4EC] p-4 text-sm sm:col-span-2">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <p className="font-bold text-[#0F2A43]">{selectedStayRoom.roomTypeName} · phòng #{selectedStayRoom.roomName}</p>
                  <span className="rounded-full bg-white px-3 py-1 text-xs font-bold text-[#0F2A43]">{selectedStayRoom.actualGuests}/{selectedStayRoom.maxGuestsPerRoom} khách trong phòng</span>
                </div>
                <p className="mt-2 leading-6 text-[#66727C]">Hạng phòng đang có {selectedStayRoom.lineActualGuests}/{selectedStayRoom.includedGuestCapacity} suất khách đã gồm giá. Từ suất tiếp theo, hệ thống tự tính {new Intl.NumberFormat("vi-VN").format(selectedStayRoom.extraGuestPricePerCycle)} đ/khách cho mỗi chu kỳ lưu trú.</p>
              </div>
            )}
            <label className="text-sm font-semibold text-[#0F2A43] sm:col-span-2">Họ và tên *<input maxLength={100} value={addGuestForm.fullName} onChange={(event) => { setAddGuestForm({ ...addGuestForm, fullName: event.target.value }); setAddGuestError(""); }} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal outline-none focus:border-[#B8944F]" /></label>
            <label className="text-sm font-semibold text-[#0F2A43]">Số điện thoại<input type="tel" inputMode="tel" maxLength={20} value={addGuestForm.phone} onChange={(event) => setAddGuestForm({ ...addGuestForm, phone: event.target.value })} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal outline-none focus:border-[#B8944F]" /></label>
            <label className="text-sm font-semibold text-[#0F2A43]">Email<input type="email" maxLength={254} value={addGuestForm.email} onChange={(event) => setAddGuestForm({ ...addGuestForm, email: event.target.value })} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal outline-none focus:border-[#B8944F]" /></label>
            <label className="text-sm font-semibold text-[#0F2A43]">Loại giấy tờ<select value={addGuestForm.idCardType} onChange={(event) => setAddGuestForm({ ...addGuestForm, idCardType: event.target.value })} className="mt-2 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-4 font-normal"><option value="CCCD">CCCD</option><option value="CMND">CMND</option><option value="PASSPORT">Hộ chiếu</option></select></label>
            <label className="text-sm font-semibold text-[#0F2A43]">Số giấy tờ<input maxLength={50} value={addGuestForm.idCardNumber} onChange={(event) => setAddGuestForm({ ...addGuestForm, idCardNumber: event.target.value })} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal outline-none focus:border-[#B8944F]" /></label>
            <label className="text-sm font-semibold text-[#0F2A43]">Ngày sinh<input type="date" max={latestBirthDate()} value={addGuestForm.dateOfBirth} onChange={(event) => setAddGuestForm({ ...addGuestForm, dateOfBirth: event.target.value })} className="mt-2 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-4 font-normal" /></label>
            <label className="text-sm font-semibold text-[#0F2A43]">Quốc tịch<input maxLength={100} value={addGuestForm.nationality} onChange={(event) => setAddGuestForm({ ...addGuestForm, nationality: event.target.value })} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-2.5 font-normal outline-none focus:border-[#B8944F]" /></label>
            {addGuestError && <p className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-semibold text-rose-700 sm:col-span-2" role="alert">{addGuestError}</p>}
          </div>
          <footer className="flex justify-end gap-3 border-t border-[#0F2A43]/10 px-6 py-4">
            <button type="button" disabled={isAddingGuest} onClick={() => setAddingGuest(null)} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43] disabled:opacity-50">Hủy</button>
            <button type="button" disabled={isAddingGuest || !addGuestForm.fullName.trim() || !selectedStayRoom || selectedStayRoom.actualGuests >= selectedStayRoom.maxGuestsPerRoom} onClick={() => void submitAddGuest()} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white disabled:cursor-not-allowed disabled:opacity-50">{isAddingGuest ? "Đang thêm..." : "Thêm khách và tính lại"}</button>
          </footer>
        </ViewportModal>
      )}

      {movingGuest && (
        <ViewportModal
          open
          onClose={() => setMovingGuest(null)}
          labelledBy="move-stay-guest-title"
          busy={isMovingGuest}
          panelClassName="max-w-xl"
        >
          <header className="border-b border-[#0F2A43]/10 px-6 py-5">
            <p className="text-xs font-bold uppercase tracking-[0.14em] text-[#80632F]">Điều chỉnh phân phòng</p>
            <h2 id="move-stay-guest-title" className="mt-1 text-xl font-bold text-[#0F2A43]">Chuyển khách sang phòng khác</h2>
            <p className="mt-1 text-sm text-[#66727C]">{movingGuest.guest.guestName} · {movingGuest.guest.roomSummary}. Hệ thống sẽ kiểm tra sức chứa và tính lại phụ thu của cả hai hạng phòng.</p>
          </header>
          <div className="grid min-h-0 flex-1 gap-4 overflow-y-auto px-6 py-5">
            <label className="text-sm font-semibold text-[#0F2A43]">Phòng đích *
              <select data-modal-autofocus value={moveGuestForm.targetReservationRoomId} onChange={(event) => { setMoveGuestForm({ ...moveGuestForm, targetReservationRoomId: event.target.value }); setMoveGuestError(""); }} className="mt-2 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-4 font-normal outline-none focus:border-[#B8944F]">
                {movingGuest.rooms.map((room) => <option key={room.reservationRoomId} value={room.reservationRoomId}>Phòng #{room.roomName} · {room.roomTypeName} · {room.actualGuests}/{room.maxGuestsPerRoom} khách</option>)}
              </select>
            </label>
            <label className="text-sm font-semibold text-[#0F2A43]">Lý do chuyển phòng *
              <textarea maxLength={500} rows={3} value={moveGuestForm.reason} onChange={(event) => { setMoveGuestForm({ ...moveGuestForm, reason: event.target.value }); setMoveGuestError(""); }} placeholder="Ví dụ: Nhân viên phân nhầm phòng khi thêm khách" className="mt-2 w-full resize-none rounded-lg border border-[#0F2A43]/15 px-4 py-3 font-normal outline-none focus:border-[#B8944F]" />
              <span className="mt-1 block text-xs font-normal text-[#66727C]">{moveGuestForm.reason.length}/500 ký tự</span>
            </label>
            {moveGuestError && <p className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-semibold text-rose-700" role="alert">{moveGuestError}</p>}
          </div>
          <footer className="flex justify-end gap-3 border-t border-[#0F2A43]/10 px-6 py-4">
            <button type="button" disabled={isMovingGuest} onClick={() => setMovingGuest(null)} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43] disabled:opacity-50">Hủy</button>
            <button type="button" disabled={isMovingGuest || moveGuestForm.reason.trim().length < 5 || !moveGuestForm.targetReservationRoomId} onClick={() => void submitMoveGuest()} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white disabled:cursor-not-allowed disabled:opacity-50">{isMovingGuest ? "Đang chuyển..." : "Xác nhận chuyển phòng"}</button>
          </footer>
        </ViewportModal>
      )}

      {toast && <Toast message={toast.message} type={toast.type} onClose={() => setToast(null)} />}
    </div>
  );
}
