"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import ViewportModal from "@/components/UI/ViewportModal";
import Toast from "@/components/UI/Toast";
import { apiClient, cachedGet, getApiErrorMessage } from "@/lib/api";
import {
  clearIdempotencyKey,
  getOrCreateIdempotencyKey,
} from "@/lib/idempotency";
import {
  calendarMonthLeadingDays,
  compactWorkShiftLabel,
  formatShiftTime,
  shiftCalendarMonth,
  staffCalendarSlotLabel,
  unwrapWorkScheduleApiData,
  workShiftPeriod,
  workShiftStaffingSegments,
  type WorkScheduleEmployee,
  type WorkShiftCalendarDay,
  type WorkShiftCalendarSlot,
  type WorkShiftMonthCalendar,
  type WorkShiftPeriod,
  type WorkShiftRegistration,
} from "@/lib/work-schedules";

type ToastState = { message: string; type: "success" | "error" | "info" };

interface WorkforceMonthCalendarProps {
  isAdmin: boolean;
  isStaff: boolean;
  employees: WorkScheduleEmployee[];
  refreshSignal?: number;
  onScheduleChanged?: () => Promise<void> | void;
}

type SlotKey = { date: string; shiftTemplateId: number };
type ShiftFilter = "ALL" | WorkShiftPeriod;

const HOTEL_TIME_ZONE = "Asia/Ho_Chi_Minh";
const weekdays = ["T2", "T3", "T4", "T5", "T6", "T7", "CN"];
const inputClass = "ops-control min-h-11 w-full rounded-lg border px-3 py-2.5 text-sm font-semibold text-[#0F2A43] outline-none transition hover:border-[#0F2A43]/30 focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20 disabled:cursor-not-allowed disabled:opacity-60";
const labelClass = "mb-2 block text-[11px] font-bold uppercase tracking-[0.08em] text-[#66727C]";
const shiftFilterOptions: Array<{ value: ShiftFilter; label: string }> = [
  { value: "ALL", label: "Tất cả ca" },
  { value: "MORNING", label: "Ca sáng" },
  { value: "AFTERNOON", label: "Ca chiều" },
  { value: "NIGHT", label: "Ca tối" },
];

const shiftVisuals: Record<WorkShiftPeriod, {
  label: string;
  compact: string;
  row: string;
  marker: string;
  icon: string;
}> = {
  MORNING: {
    label: "Ca sáng",
    compact: "bg-amber-100 text-amber-800",
    row: "border-amber-200 bg-amber-50/85 text-amber-950",
    marker: "bg-amber-500",
    icon: "☀",
  },
  AFTERNOON: {
    label: "Ca chiều",
    compact: "bg-teal-100 text-teal-800",
    row: "border-teal-200 bg-teal-50/85 text-teal-950",
    marker: "bg-teal-500",
    icon: "◐",
  },
  NIGHT: {
    label: "Ca tối",
    compact: "bg-indigo-100 text-indigo-800",
    row: "border-indigo-200 bg-indigo-50/85 text-indigo-950",
    marker: "bg-indigo-500",
    icon: "☾",
  },
};

const currentMonthKey = () => new Intl.DateTimeFormat("en-CA", {
  timeZone: HOTEL_TIME_ZONE,
  year: "numeric",
  month: "2-digit",
}).format(new Date());

const formatMonth = (month: string) => {
  const [year, value] = month.split("-").map(Number);
  return `Tháng ${value}/${year}`;
};

const formatDay = (date: string, withWeekday = true) => new Intl.DateTimeFormat("vi-VN", {
  ...(withWeekday ? { weekday: "long" as const } : {}),
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  timeZone: HOTEL_TIME_ZONE,
}).format(new Date(`${date}T12:00:00+07:00`));

const registrationStatusLabel: Record<WorkShiftRegistration["status"], string> = {
  PENDING: "Chờ ADMIN duyệt",
  APPROVED: "Đã được duyệt",
  REJECTED: "Đã từ chối",
  CANCELLED: "Đã hủy",
};

const requestTone: Record<WorkShiftRegistration["status"], string> = {
  PENDING: "border-amber-200 bg-amber-50 text-amber-900",
  APPROVED: "border-emerald-200 bg-emerald-50 text-emerald-800",
  REJECTED: "border-rose-200 bg-rose-50 text-rose-800",
  CANCELLED: "border-slate-200 bg-slate-50 text-slate-600",
};

function Chevron({ direction }: { direction: "left" | "right" }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className="h-4 w-4 fill-none stroke-current stroke-2">
      <path d={direction === "left" ? "m15 18-6-6 6-6" : "m9 18 6-6-6-6"} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function compactSlotStatus(
  slot: WorkShiftCalendarSlot,
  isAdmin: boolean,
  past: boolean,
) {
  if (!isAdmin) return staffCalendarSlotLabel(slot, past);
  if (slot.pendingRequestCount > 0) return `${slot.pendingRequestCount} chờ`;
  if (past || slot.registrationOpen === false) return "Đã qua";
  if (slot.availableSlots > 0) return `${slot.availableSlots} trống`;
  return "Đủ";
}

function isWeekend(date: string) {
  const day = new Date(`${date}T12:00:00+07:00`).getUTCDay();
  return day === 0 || day === 6;
}

function drawerActionLabel(slot: WorkShiftCalendarSlot, isAdmin: boolean) {
  if (isAdmin) return "Quản lý ca";
  if (slot.currentUserAssignment) return "Xem ca của tôi";
  if (slot.currentUserRequest?.status === "PENDING") return "Xem yêu cầu";
  if (slot.availableSlots > 0 && slot.registrationOpen !== false) return "Đăng ký ca";
  return "Xem chi tiết";
}

export default function WorkforceMonthCalendar({
  isAdmin,
  isStaff,
  employees,
  refreshSignal = 0,
  onScheduleChanged,
}: WorkforceMonthCalendarProps) {
  const [month, setMonth] = useState(currentMonthKey);
  const [calendar, setCalendar] = useState<WorkShiftMonthCalendar | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [selectedDate, setSelectedDate] = useState<string | null>(null);
  const [selectedKey, setSelectedKey] = useState<SlotKey | null>(null);
  const [shiftFilter, setShiftFilter] = useState<ShiftFilter>("ALL");
  const [staffNote, setStaffNote] = useState("");
  const [reviewReasons, setReviewReasons] = useState<Record<number, string>>({});
  const [directEmployeeId, setDirectEmployeeId] = useState(0);
  const [directNote, setDirectNote] = useState("");
  const [requiredStaff, setRequiredStaff] = useState(1);
  const [requirementNote, setRequirementNote] = useState("");
  const [toast, setToast] = useState<ToastState | null>(null);

  const loadCalendar = useCallback(async (showLoading = false) => {
    if (showLoading) {
      setLoading(true);
      setCalendar(null);
      setLoadError(null);
    }
    try {
      const response = await cachedGet(
        `/api/work-schedules/calendar?month=${month}`,
        { ttlMs: 3_000, force: true },
      );
      setCalendar(unwrapWorkScheduleApiData<WorkShiftMonthCalendar>(response));
    } catch (error) {
      const message = getApiErrorMessage(error, "Không thể tải lịch tháng");
      if (showLoading) {
        setCalendar(null);
        setLoadError(message);
      }
      setToast({
        type: "error",
        message,
      });
    } finally {
      setLoading(false);
    }
  }, [month]);

  useEffect(() => {
    void loadCalendar(true);
  }, [loadCalendar, refreshSignal]);

  useEffect(() => {
    setSelectedDate(null);
    setSelectedKey(null);
  }, [month]);

  const selectedDay = useMemo(() => {
    if (!selectedDate || !calendar) return null;
    return calendar.days.find((item) => item.date === selectedDate) || null;
  }, [calendar, selectedDate]);

  const selected = useMemo(() => {
    if (!selectedKey || !calendar) return null;
    const day = calendar.days.find((item) => item.date === selectedKey.date);
    const slot = day?.slots.find(
      (item) => item.shiftTemplateId === selectedKey.shiftTemplateId,
    );
    return day && slot ? { day, slot } : null;
  }, [calendar, selectedKey]);

  const openSlot = (day: WorkShiftCalendarDay, slot: WorkShiftCalendarSlot) => {
    setSelectedDate(day.date);
    setSelectedKey({ date: day.date, shiftTemplateId: slot.shiftTemplateId });
    setStaffNote(slot.currentUserRequest?.staffNote || "");
    setReviewReasons({});
    setDirectEmployeeId(0);
    setDirectNote("");
    setRequiredStaff(slot.requiredStaff);
    setRequirementNote(slot.requirementNote || "");
  };

  const completeMutation = async (message: string) => {
    await loadCalendar();
    await onScheduleChanged?.();
    setToast({ type: "success", message });
  };

  const createRegistration = async () => {
    if (!selected) return;
    const scope = `work-shift-request:create:${selected.day.date}:${selected.slot.shiftTemplateId}:${staffNote.trim()}`;
    setSubmitting(true);
    try {
      await apiClient.post(
        "/api/work-schedules/registration-requests",
        {
          shiftTemplateId: selected.slot.shiftTemplateId,
          workDate: selected.day.date,
          note: staffNote.trim() || null,
        },
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } },
      );
      clearIdempotencyKey(scope);
      await completeMutation("Đã gửi yêu cầu đăng ký ca cho ADMIN");
    } catch (error) {
      setToast({
        type: "error",
        message: getApiErrorMessage(error, "Không thể đăng ký ca"),
      });
    } finally {
      setSubmitting(false);
    }
  };

  const cancelRegistration = async () => {
    const request = selected?.slot.currentUserRequest;
    if (!request) return;
    const scope = `work-shift-request:cancel:${request.id}`;
    setSubmitting(true);
    try {
      await apiClient.post(
        `/api/work-schedules/registration-requests/${request.id}/cancel`,
        {},
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } },
      );
      clearIdempotencyKey(scope);
      await completeMutation("Đã hủy yêu cầu đăng ký ca");
    } catch (error) {
      setToast({
        type: "error",
        message: getApiErrorMessage(error, "Không thể hủy yêu cầu"),
      });
    } finally {
      setSubmitting(false);
    }
  };

  const reviewRegistration = async (
    request: WorkShiftRegistration,
    decision: "approve" | "reject",
  ) => {
    const reviewReason = reviewReasons[request.id]?.trim() || "";
    if (decision === "reject" && !reviewReason) {
      setToast({ type: "error", message: "Vui lòng nhập lý do từ chối." });
      return;
    }
    const scope = `work-shift-request:${decision}:${request.id}:${reviewReason}`;
    setSubmitting(true);
    try {
      await apiClient.post(
        `/api/work-schedules/registration-requests/${request.id}/${decision}`,
        { reason: reviewReason || null },
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } },
      );
      clearIdempotencyKey(scope);
      setReviewReasons((current) => {
        const next = { ...current };
        delete next[request.id];
        return next;
      });
      await completeMutation(
        decision === "approve"
          ? `Đã duyệt ca cho ${request.employeeName}`
          : `Đã từ chối yêu cầu của ${request.employeeName}`,
      );
    } catch (error) {
      setToast({
        type: "error",
        message: getApiErrorMessage(error, "Không thể xử lý yêu cầu"),
      });
    } finally {
      setSubmitting(false);
    }
  };

  const saveRequirement = async () => {
    if (!selected) return;
    const scope = `work-shift-requirement:${selected.day.date}:${selected.slot.shiftTemplateId}:${requiredStaff}:${requirementNote.trim()}`;
    setSubmitting(true);
    try {
      await apiClient.put(
        `/api/work-schedules/requirements/${selected.day.date}/${selected.slot.shiftTemplateId}`,
        {
          requiredStaff,
          note: requirementNote.trim() || null,
        },
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } },
      );
      clearIdempotencyKey(scope);
      await completeMutation("Đã cập nhật số nhân sự cần cho ca");
    } catch (error) {
      setToast({
        type: "error",
        message: getApiErrorMessage(error, "Không thể cập nhật nhu cầu nhân sự"),
      });
    } finally {
      setSubmitting(false);
    }
  };

  const directAssign = async () => {
    if (!selected || !directEmployeeId) {
      setToast({ type: "error", message: "Vui lòng chọn nhân viên." });
      return;
    }
    const payload = {
      employeeId: directEmployeeId,
      shiftTemplateId: selected.slot.shiftTemplateId,
      workDate: selected.day.date,
      note: directNote.trim() || null,
    };
    const scope = `work-schedule:calendar-assign:${JSON.stringify(payload)}`;
    setSubmitting(true);
    try {
      await apiClient.post(
        "/api/work-schedules/assignments",
        payload,
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } },
      );
      clearIdempotencyKey(scope);
      setDirectNote("");
      await completeMutation("Đã phân ca trực tiếp cho nhân viên");
    } catch (error) {
      setToast({
        type: "error",
        message: getApiErrorMessage(error, "Không thể phân ca"),
      });
    } finally {
      setSubmitting(false);
    }
  };

  const leadingDays = calendarMonthLeadingDays(month);
  const trailingDays = calendar
    ? (7 - ((leadingDays + calendar.days.length) % 7)) % 7
    : 0;
  const calendarSummary = useMemo(() => {
    if (!calendar) {
      return {
        required: 0,
        assigned: 0,
        available: 0,
        pending: 0,
        myAssignments: 0,
        myPending: 0,
      };
    }

    return calendar.days.reduce((summary, day) => {
      day.slots.forEach((slot) => {
        summary.required += slot.requiredStaff;
        summary.assigned += slot.assignedCount;
        summary.available += Math.max(slot.availableSlots, 0);
        summary.pending += slot.pendingRequestCount;
        if (slot.currentUserAssignment) summary.myAssignments += 1;
        if (slot.currentUserRequest?.status === "PENDING") summary.myPending += 1;
      });
      return summary;
    }, {
      required: 0,
      assigned: 0,
      available: 0,
      pending: 0,
      myAssignments: 0,
      myPending: 0,
    });
  }, [calendar]);
  const pendingRequests = selected?.slot.requests.filter(
    (request) => request.status === "PENDING",
  ) || [];
  const reviewedRequests = selected?.slot.requests.filter(
    (request) => request.status !== "PENDING",
  ) || [];

  return (
    <>
      <section className="ops-panel overflow-hidden rounded-2xl border shadow-sm" aria-labelledby="month-calendar-title">
        <header className="bg-[#0F2A43] px-5 py-5 text-white md:px-6">
          <div className="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between">
            <div>
              <p className="text-[10px] font-bold uppercase tracking-[0.2em] text-[#D8C398]">
                {isAdmin ? "Kế hoạch nhân sự" : "Lịch làm việc của tôi"}
              </p>
              <h2 id="month-calendar-title" className="mt-1 font-serif text-2xl font-bold">
                {formatMonth(month)}
              </h2>
              <p className="mt-2 max-w-3xl text-xs leading-5 text-white/70">
                {isAdmin
                  ? "Chọn một ca để xem nhân viên, duyệt đăng ký và điều chỉnh nhu cầu nhân sự."
                  : "Ca của bạn, yêu cầu chờ duyệt và số chỗ còn trống được phân biệt bằng màu."}
              </p>
            </div>
            <div className="flex flex-wrap items-end gap-2">
              <label className="min-w-36 text-[9px] font-bold uppercase tracking-[0.13em] text-white/60">
                Hiển thị
                <select
                  aria-label="Lọc ca trên lịch tháng"
                  value={shiftFilter}
                  onChange={(event) => setShiftFilter(event.target.value as ShiftFilter)}
                  className="mt-1 block min-h-11 w-full cursor-pointer rounded-xl border border-white/20 bg-[#173D5F] px-3 text-xs font-bold normal-case tracking-normal text-white outline-none transition hover:border-white/35 focus-visible:border-[#D8C398] focus-visible:ring-2 focus-visible:ring-[#D8C398]/25"
                >
                  {shiftFilterOptions.map((option) => (
                    <option key={option.value} value={option.value}>{option.label}</option>
                  ))}
                </select>
              </label>
              <div className="flex items-center gap-2" aria-label="Điều hướng tháng">
                <button
                  type="button"
                  aria-label="Tháng trước"
                  onClick={() => setMonth((value) => shiftCalendarMonth(value, -1))}
                  className="flex min-h-11 min-w-11 cursor-pointer items-center justify-center rounded-xl border border-white/20 bg-white/10 text-white transition duration-200 hover:bg-white/20 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#D8C398]"
                >
                  <Chevron direction="left" />
                </button>
                <button
                  type="button"
                  onClick={() => setMonth(currentMonthKey())}
                  className="min-h-11 cursor-pointer rounded-xl border border-white/20 bg-white/10 px-4 text-xs font-bold text-white transition duration-200 hover:bg-white/20 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#D8C398]"
                >
                  Hôm nay
                </button>
                <button
                  type="button"
                  aria-label="Tháng sau"
                  onClick={() => setMonth((value) => shiftCalendarMonth(value, 1))}
                  className="flex min-h-11 min-w-11 cursor-pointer items-center justify-center rounded-xl border border-white/20 bg-white/10 text-white transition duration-200 hover:bg-white/20 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#D8C398]"
                >
                  <Chevron direction="right" />
                </button>
              </div>
            </div>
          </div>
        </header>

        {!loading && !loadError && calendar && (
          <div className="border-b border-[#0F2A43]/10 bg-[#F8F5EE] px-4 py-4 md:px-5">
            <div className={`grid gap-2 ${isAdmin ? "grid-cols-2 xl:grid-cols-4" : "grid-cols-3"}`}>
              {(isAdmin
                ? [
                    ["Nhân sự cần", calendarSummary.required],
                    ["Đã phân", calendarSummary.assigned],
                    ["Còn thiếu", calendarSummary.available],
                    ["Chờ duyệt", calendarSummary.pending],
                  ]
                : [
                    ["Ca của bạn", calendarSummary.myAssignments],
                    ["Chờ duyệt", calendarSummary.myPending],
                    ["Chỗ còn trống", calendarSummary.available],
                  ]
              ).map(([label, value]) => (
                <div key={String(label)} className="rounded-xl border border-[#0F2A43]/10 bg-white px-3 py-3">
                  <p className="text-[9px] font-bold uppercase tracking-[0.12em] text-[#66727C]">{label}</p>
                  <p className="mt-1 text-xl font-bold tabular-nums text-[#0F2A43]">{value}</p>
                </div>
              ))}
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-2 text-[10px] font-semibold text-[#66727C]" aria-label="Chú thích lịch làm việc">
              {[
                ["bg-amber-500", "S · Sáng"],
                ["bg-teal-500", "C · Chiều"],
                ["bg-indigo-500", "T · Tối"],
                ["bg-emerald-500", isAdmin ? "Đủ nhân sự" : "Ca của bạn"],
                ["bg-rose-500", isAdmin ? "Có yêu cầu chờ duyệt" : "Yêu cầu chờ duyệt"],
              ].map(([tone, label]) => (
                <span key={label} className="inline-flex items-center gap-2">
                  <i className={`h-2.5 w-2.5 rounded-full ${tone}`} aria-hidden="true" />
                  {label}
                </span>
              ))}
            </div>
          </div>
        )}

        {loading ? (
          <div className="grid gap-3 p-5 sm:grid-cols-2 lg:grid-cols-4" aria-label="Đang tải lịch tháng">
            {Array.from({ length: 8 }, (_, index) => (
              <div key={index} className="h-40 animate-pulse rounded-xl bg-[#0F2A43]/6" />
            ))}
          </div>
        ) : loadError ? (
          <div className="flex min-h-64 flex-col items-center justify-center px-5 py-10 text-center" role="alert">
            <div className="flex h-11 w-11 items-center justify-center rounded-full bg-rose-50 text-lg font-bold text-rose-700" aria-hidden="true">!</div>
            <h3 className="mt-3 text-base font-bold text-[#0F2A43]">Chưa tải được lịch tháng</h3>
            <p className="mt-1 max-w-lg text-sm leading-6 text-[#66727C]">{loadError}</p>
            <button
              type="button"
              onClick={() => void loadCalendar(true)}
              className="mt-4 min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#173D5F] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#B8944F]"
            >
              Thử tải lại
            </button>
          </div>
        ) : calendar ? (
          <div className="p-3 sm:p-4 md:p-5">
            <div className="grid grid-cols-7 rounded-t-2xl border border-b-0 border-[#0F2A43]/10 bg-[#F3F5F3] px-px pt-px">
              {weekdays.map((day, index) => (
                <div
                  key={day}
                  className={`py-2 text-center text-[9px] font-black uppercase tracking-[0.12em] sm:text-[10px] ${index >= 5 ? "text-[#80632F]" : "text-[#526372]"}`}
                >
                  {day}
                </div>
              ))}
            </div>

            <div className="grid grid-cols-7 gap-px overflow-hidden rounded-b-2xl border border-[#0F2A43]/10 bg-[#DDE3E5] p-px">
              {Array.from({ length: leadingDays }, (_, index) => (
                <div key={`empty-${index}`} aria-hidden="true" className="min-h-[104px] bg-[#F3F5F3]/75 sm:min-h-[116px]" />
              ))}

              {calendar.days.map((day) => {
                const visibleSlots = day.slots.filter((slot) => (
                  shiftFilter === "ALL" || workShiftPeriod(slot) === shiftFilter
                ));
                const selectedDateActive = selectedDate === day.date;
                const pendingCount = day.slots.reduce((total, slot) => total + slot.pendingRequestCount, 0);

                return (
                  <button
                    key={day.date}
                    type="button"
                    onClick={() => setSelectedDate(day.date)}
                    aria-label={`Xem lịch ${formatDay(day.date)}. ${day.slots.map((slot) => `${slot.shiftName}: ${compactSlotStatus(slot, isAdmin, day.past)}`).join(". ")}`}
                    className={`group relative min-h-[104px] cursor-pointer p-1.5 text-left outline-none transition duration-200 sm:min-h-[116px] sm:p-2 ${
                      isWeekend(day.date) ? "bg-[#F7F8F6]" : "bg-white"
                    } ${day.past ? "opacity-[0.72]" : "hover:bg-[#FCFBF7]"} ${
                      selectedDateActive
                        ? "z-[1] ring-2 ring-inset ring-[#0F2A43]"
                        : "focus-visible:z-[1] focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[#B8944F]"
                    }`}
                  >
                    <span className="mb-1.5 flex min-h-7 items-center justify-between gap-1">
                      <span className={`flex h-7 min-w-7 items-center justify-center rounded-full px-1 text-[11px] font-black tabular-nums sm:text-xs ${
                        day.today
                          ? "bg-[#0F2A43] text-white shadow-[0_4px_12px_rgba(15,42,67,0.22)]"
                          : "text-[#0F2A43]"
                      }`}>
                        {Number(day.date.slice(-2))}
                      </span>
                      <span className="flex items-center gap-1">
                        {pendingCount > 0 ? (
                          <span className="h-2 w-2 rounded-full bg-rose-500" title={`${pendingCount} yêu cầu chờ duyệt`} aria-label={`${pendingCount} yêu cầu chờ duyệt`} />
                        ) : null}
                        {day.today ? (
                          <span className="hidden rounded-full bg-[#B8944F]/15 px-1.5 py-0.5 text-[7px] font-black uppercase tracking-wide text-[#80632F] xl:inline">Hôm nay</span>
                        ) : null}
                      </span>
                    </span>

                    <span className="block space-y-1">
                      {visibleSlots.length > 0 ? visibleSlots.map((slot) => {
                        const period = workShiftPeriod(slot);
                        const visual = shiftVisuals[period];
                        const status = compactSlotStatus(slot, isAdmin, day.past);
                        const segments = workShiftStaffingSegments(slot.assignedCount, slot.requiredStaff);
                        const complete = slot.requiredStaff > 0 && slot.assignedCount >= slot.requiredStaff;

                        return (
                          <span
                            key={slot.shiftTemplateId}
                            title={`${slot.shiftName} ${formatShiftTime(slot.startTime)}–${formatShiftTime(slot.endTime)} · ${slot.assignedCount}/${slot.requiredStaff} đã phân · ${status}`}
                            className={`grid min-h-5 grid-cols-[16px_1fr] items-center gap-1 rounded-md border px-1 py-0.5 sm:grid-cols-[18px_24px_1fr] ${visual.row} ${complete ? "opacity-70" : ""}`}
                          >
                            <span className={`flex h-4 w-4 items-center justify-center rounded text-[8px] font-black sm:h-[18px] sm:w-[18px] sm:text-[9px] ${visual.compact}`}>
                              {compactWorkShiftLabel(period)}
                            </span>
                            <span className="hidden grid-cols-3 gap-0.5 sm:grid" aria-label={`${slot.assignedCount}/${slot.requiredStaff} vị trí đã phân`}>
                              {segments.map((filled, segmentIndex) => (
                                <i key={segmentIndex} className={`h-1.5 rounded-full ${filled ? visual.marker : "bg-white/80 ring-1 ring-inset ring-current/15"}`} aria-hidden="true" />
                              ))}
                            </span>
                            <span className="truncate text-[8px] font-bold leading-none sm:text-[9px] lg:text-[10px]">
                              <span className="sm:hidden">{complete ? "✓" : Math.max(slot.availableSlots, 0)}</span>
                              <span className="hidden sm:inline">{status}</span>
                            </span>
                          </span>
                        );
                      }) : (
                        <span className="flex min-h-[70px] items-center justify-center text-xs font-bold text-[#98A1A8]" aria-hidden="true">—</span>
                      )}
                    </span>
                  </button>
                );
              })}

              {Array.from({ length: trailingDays }, (_, index) => (
                <div key={`trailing-${index}`} aria-hidden="true" className="min-h-[104px] bg-[#F3F5F3]/75 sm:min-h-[116px]" />
              ))}
            </div>

            <p className="mt-3 text-center text-[10px] font-semibold text-[#66727C]">
              Chọn một ngày để xem giờ ca, nhân viên và thao tác chi tiết.
            </p>
          </div>
        ) : null}
      </section>

      <ViewportModal
        open={Boolean(selectedDay)}
        onClose={() => setSelectedDate(null)}
        labelledBy="workforce-day-title"
        variant="drawer"
        panelClassName="max-w-[420px] bg-[#FBFAF6]"
        backdropClassName="bg-[#091E30]/52 backdrop-blur-[2px]"
        zIndexClassName="z-[85]"
      >
        {selectedDay && (
          <div className="flex min-h-0 flex-1 flex-col">
            <header className="border-b border-[#0F2A43]/10 bg-[#0F2A43] px-5 py-5 text-white">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <p className="text-[9px] font-black uppercase tracking-[0.18em] text-[#D8C398]">Lịch trong ngày</p>
                  <h2 id="workforce-day-title" className="mt-1 font-serif text-2xl font-bold capitalize">
                    {formatDay(selectedDay.date)}
                  </h2>
                  <p className="mt-2 text-xs leading-5 text-white/65">
                    {isAdmin
                      ? "Xem nhu cầu nhân sự, người đã nhận ca và yêu cầu chờ duyệt."
                      : "Xem ca của bạn hoặc đăng ký ca còn vị trí trống."}
                  </p>
                </div>
                <button
                  type="button"
                  aria-label="Đóng lịch trong ngày"
                  onClick={() => setSelectedDate(null)}
                  className="flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center rounded-full border border-white/20 bg-white/10 text-lg font-bold transition hover:bg-white/20 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#D8C398]"
                >
                  ×
                </button>
              </div>
            </header>

            <div className="lux-scrollbar min-h-0 flex-1 space-y-4 overflow-y-auto p-4 sm:p-5">
              <div className="grid grid-cols-3 gap-2" aria-label="Tổng hợp nhân sự trong ngày">
                {[
                  ["Nhân sự cần", selectedDay.slots.reduce((total, slot) => total + slot.requiredStaff, 0)],
                  ["Đã phân", selectedDay.slots.reduce((total, slot) => total + slot.assignedCount, 0)],
                  ["Còn thiếu", selectedDay.slots.reduce((total, slot) => total + Math.max(slot.availableSlots, 0), 0)],
                ].map(([label, value]) => (
                  <div key={String(label)} className="rounded-xl border border-[#0F2A43]/10 bg-white p-3 text-center">
                    <p className="text-[8px] font-black uppercase tracking-[0.1em] text-[#66727C]">{label}</p>
                    <p className="mt-1 text-xl font-black tabular-nums text-[#0F2A43]">{value}</p>
                  </div>
                ))}
              </div>

              {selectedDay.slots.map((slot) => {
                const period = workShiftPeriod(slot);
                const visual = shiftVisuals[period];
                const status = compactSlotStatus(slot, isAdmin, selectedDay.past);
                const complete = slot.requiredStaff > 0 && slot.assignedCount >= slot.requiredStaff;

                return (
                  <article key={slot.shiftTemplateId} className={`overflow-hidden rounded-2xl border bg-white shadow-sm ${visual.row}`}>
                    <div className="bg-white/85 p-4">
                      <div className="flex items-start justify-between gap-3">
                        <div className="flex min-w-0 gap-3">
                          <span className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-xl text-xl ${visual.compact}`} aria-hidden="true">
                            {visual.icon}
                          </span>
                          <div className="min-w-0">
                            <h3 className="truncate text-base font-black text-[#0F2A43]">{slot.shiftName}</h3>
                            <p className="mt-1 text-xs font-bold tabular-nums text-[#526372]">
                              {formatShiftTime(slot.startTime)}–{formatShiftTime(slot.endTime)}
                              {slot.crossesMidnight ? " · hôm sau" : ""}
                            </p>
                          </div>
                        </div>
                        <span className={`shrink-0 rounded-full px-2.5 py-1 text-[9px] font-black ${
                          complete ? "bg-emerald-100 text-emerald-800" : slot.pendingRequestCount > 0 ? "bg-rose-100 text-rose-800" : visual.compact
                        }`}>
                          {status}
                        </span>
                      </div>

                      <div className="mt-4 grid grid-cols-3 gap-2 rounded-xl bg-[#F7F5EF] p-3 text-center">
                        <div>
                          <p className="text-[8px] font-black uppercase tracking-wide text-[#7A858D]">Cần</p>
                          <p className="mt-1 text-sm font-black text-[#0F2A43]">{slot.requiredStaff}</p>
                        </div>
                        <div>
                          <p className="text-[8px] font-black uppercase tracking-wide text-[#7A858D]">Đã phân</p>
                          <p className="mt-1 text-sm font-black text-[#0F2A43]">{slot.assignedCount}</p>
                        </div>
                        <div>
                          <p className="text-[8px] font-black uppercase tracking-wide text-[#7A858D]">Chờ duyệt</p>
                          <p className="mt-1 text-sm font-black text-[#0F2A43]">{slot.pendingRequestCount}</p>
                        </div>
                      </div>

                      {isAdmin ? (
                        <div className="mt-3">
                          <p className="text-[9px] font-black uppercase tracking-[0.1em] text-[#66727C]">Nhân viên đã nhận ca</p>
                          <div className="mt-2 flex flex-wrap gap-1.5">
                            {slot.assignments.length > 0 ? slot.assignments.map((assignment) => (
                              <span key={assignment.id} className="rounded-full border border-[#0F2A43]/10 bg-white px-2.5 py-1 text-[10px] font-bold text-[#27445F]">
                                {assignment.employeeName}
                              </span>
                            )) : (
                              <span className="text-xs font-semibold text-[#89939A]">Chưa phân nhân viên</span>
                            )}
                          </div>
                        </div>
                      ) : slot.currentUserAssignment ? (
                        <p className="mt-3 rounded-lg bg-emerald-50 px-3 py-2 text-xs font-bold text-emerald-800">Bạn đã được phân ca này.</p>
                      ) : slot.currentUserRequest?.status === "PENDING" ? (
                        <p className="mt-3 rounded-lg bg-amber-50 px-3 py-2 text-xs font-bold text-amber-800">Yêu cầu của bạn đang chờ duyệt.</p>
                      ) : null}

                      <button
                        type="button"
                        onClick={() => openSlot(selectedDay, slot)}
                        className="mt-4 min-h-11 w-full cursor-pointer rounded-xl bg-[#0F2A43] px-4 text-xs font-black text-white transition duration-200 hover:bg-[#173D5F] active:scale-[0.99] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#B8944F]"
                      >
                        {drawerActionLabel(slot, isAdmin)}
                      </button>
                    </div>
                  </article>
                );
              })}
            </div>
          </div>
        )}
      </ViewportModal>

      <ViewportModal
        open={Boolean(selected)}
        onClose={() => setSelectedKey(null)}
        labelledBy="workforce-slot-title"
        busy={submitting}
        panelClassName="max-w-4xl"
        zIndexClassName="z-[95]"
      >
        {selected && (
          <div className="flex min-h-0 flex-1 flex-col">
            <header className="border-b px-5 py-4">
              <p className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#80632F]">
                {isAdmin ? "Chi tiết ca làm việc" : "Đăng ký ca làm việc"}
              </p>
              <h2 id="workforce-slot-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">
                {selected.slot.shiftName} · {formatDay(selected.day.date)}
              </h2>
              <p className="mt-1 text-xs text-[#66727C]">
                {formatShiftTime(selected.slot.startTime)}–{formatShiftTime(selected.slot.endTime)}
                {selected.slot.crossesMidnight ? " · kết thúc ngày hôm sau" : ""}
              </p>
            </header>

            <div className="lux-scrollbar min-h-0 flex-1 overflow-y-auto p-5">
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                {[
                  ["Cần", selected.slot.requiredStaff],
                  ["Đã phân", selected.slot.assignedCount],
                  ["Còn trống", selected.slot.availableSlots],
                  [isAdmin ? "Chờ duyệt" : "Yêu cầu của tôi", isAdmin ? selected.slot.pendingRequestCount : selected.slot.currentUserRequest ? 1 : 0],
                ].map(([label, value]) => (
                  <div key={String(label)} className="rounded-xl border border-[#0F2A43]/10 bg-[#FBFAF6] p-3">
                    <p className="text-[9px] font-bold uppercase tracking-[0.12em] text-[#66727C]">{label}</p>
                    <p className="mt-1 text-xl font-bold tabular-nums text-[#0F2A43]">{value}</p>
                  </div>
                ))}
              </div>

              {isAdmin && (
                <div className="mt-5 grid gap-5 lg:grid-cols-2">
                  <section className="rounded-xl border border-[#0F2A43]/10 p-4">
                    <h3 className="text-sm font-bold text-[#0F2A43]">Nhân viên đã nhận ca</h3>
                    <p className="mt-1 text-xs text-[#66727C]">Chỉ ADMIN được xem danh sách này.</p>
                    <div className="mt-3 space-y-2">
                      {selected.slot.assignments.length ? selected.slot.assignments.map((assignment) => (
                        <div key={assignment.id} className="flex items-center justify-between gap-3 rounded-lg bg-[#F4EFE5] px-3 py-2.5">
                          <div>
                            <p className="text-sm font-bold text-[#0F2A43]">{assignment.employeeName}</p>
                            <p className="mt-0.5 text-[10px] text-[#66727C]">Lịch #{assignment.id}</p>
                          </div>
                          <span className="rounded-full bg-white px-2 py-1 text-[9px] font-bold text-[#27445F]">{assignment.status}</span>
                        </div>
                      )) : (
                        <p className="rounded-lg border border-dashed p-4 text-center text-xs text-[#66727C]">Chưa phân nhân viên.</p>
                      )}
                    </div>
                  </section>

                  <section className="rounded-xl border border-[#0F2A43]/10 p-4">
                    <h3 className="text-sm font-bold text-[#0F2A43]">Nhu cầu nhân sự</h3>
                    <p className="mt-1 text-xs text-[#66727C]">Không thể đặt thấp hơn số nhân viên đã phân.</p>
                    <div className="mt-3 grid gap-3 sm:grid-cols-[120px_1fr]">
                      <label>
                        <span className={labelClass}>Số người cần</span>
                        <input
                          type="number"
                          min={selected.slot.assignedCount}
                          max={100}
                          value={requiredStaff}
                          disabled={selected.day.past || selected.slot.registrationOpen === false}
                          onChange={(event) => setRequiredStaff(Number(event.target.value))}
                          className={inputClass}
                        />
                      </label>
                      <label>
                        <span className={labelClass}>Ghi chú</span>
                        <input
                          value={requirementNote}
                          maxLength={500}
                          disabled={selected.day.past || selected.slot.registrationOpen === false}
                          onChange={(event) => setRequirementNote(event.target.value)}
                          placeholder="Ví dụ: cuối tuần cần tăng cường"
                          className={inputClass}
                        />
                      </label>
                    </div>
                    <button
                      type="button"
                      disabled={submitting || selected.day.past || selected.slot.registrationOpen === false}
                      onClick={() => void saveRequirement()}
                      className="mt-3 min-h-11 w-full rounded-lg border border-[#B8944F] bg-[#FFF9EA] px-4 text-xs font-bold text-[#0F2A43] transition hover:bg-[#F4E7C6] disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {submitting ? "Đang lưu..." : "Lưu nhu cầu nhân sự"}
                    </button>
                  </section>
                </div>
              )}

              {isAdmin && (
                <section className="mt-5 rounded-xl border border-[#0F2A43]/10 p-4">
                  <h3 className="text-sm font-bold text-[#0F2A43]">Yêu cầu đang chờ duyệt</h3>
                  <div className="mt-3 space-y-3">
                    {pendingRequests.length ? pendingRequests.map((request) => (
                      <article key={request.id} className="rounded-xl border border-amber-200 bg-amber-50/70 p-3">
                        <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                          <div>
                            <p className="text-sm font-bold text-[#0F2A43]">{request.employeeName}</p>
                            <p className="mt-1 text-xs leading-5 text-[#66727C]">{request.staffNote || "Không có ghi chú."}</p>
                          </div>
                          <span className="whitespace-nowrap rounded-full bg-white px-2.5 py-1 text-[9px] font-bold uppercase tracking-wide text-amber-800">Chờ duyệt</span>
                        </div>
                        <div className="mt-3 grid gap-2 sm:grid-cols-[1fr_auto_auto]">
                          <input
                            value={reviewReasons[request.id] || ""}
                            maxLength={500}
                            onChange={(event) => setReviewReasons((current) => ({
                              ...current,
                              [request.id]: event.target.value,
                            }))}
                            aria-label={`Ghi chú xử lý yêu cầu của ${request.employeeName}`}
                            placeholder="Ghi chú duyệt hoặc lý do từ chối"
                            className={inputClass}
                          />
                          <button
                            type="button"
                            disabled={submitting || selected.day.past || selected.slot.registrationOpen === false || selected.slot.availableSlots <= 0}
                            onClick={() => void reviewRegistration(request, "approve")}
                            className="min-h-11 rounded-lg bg-emerald-700 px-4 text-xs font-bold text-white transition hover:bg-emerald-800 disabled:opacity-45"
                          >
                            Duyệt
                          </button>
                          <button
                            type="button"
                            disabled={submitting}
                            onClick={() => void reviewRegistration(request, "reject")}
                            className="min-h-11 rounded-lg border border-rose-300 px-4 text-xs font-bold text-rose-700 transition hover:bg-rose-50 disabled:opacity-45"
                          >
                            Từ chối
                          </button>
                        </div>
                      </article>
                    )) : (
                      <p className="rounded-lg border border-dashed p-4 text-center text-xs text-[#66727C]">Không có yêu cầu chờ duyệt.</p>
                    )}
                  </div>
                </section>
              )}

              {isAdmin && !selected.day.past && selected.slot.registrationOpen !== false && (
                <section className="mt-5 rounded-xl border border-[#0F2A43]/10 bg-[#FBFAF6] p-4">
                  <h3 className="text-sm font-bold text-[#0F2A43]">Phân ca trực tiếp</h3>
                  <div className="mt-3 grid gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
                    <label>
                      <span className={labelClass}>Nhân viên</span>
                      <select value={directEmployeeId} onChange={(event) => setDirectEmployeeId(Number(event.target.value))} className={inputClass}>
                        <option value={0}>Chọn nhân viên</option>
                        {employees.map((employee) => (
                          <option key={employee.id} value={employee.id}>{employee.fullName} · {employee.username}</option>
                        ))}
                      </select>
                    </label>
                    <label>
                      <span className={labelClass}>Ghi chú</span>
                      <input value={directNote} maxLength={1000} onChange={(event) => setDirectNote(event.target.value)} className={inputClass} />
                    </label>
                    <button
                      type="button"
                      disabled={submitting || !directEmployeeId}
                      onClick={() => void directAssign()}
                      className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-xs font-bold text-white transition hover:bg-[#173D5F] disabled:opacity-45"
                    >
                      Phân ca
                    </button>
                  </div>
                </section>
              )}

              {isAdmin && reviewedRequests.length > 0 && (
                <details className="mt-5 rounded-xl border border-[#0F2A43]/10 p-4">
                  <summary className="cursor-pointer text-sm font-bold text-[#0F2A43]">Lịch sử yêu cầu ({reviewedRequests.length})</summary>
                  <div className="mt-3 space-y-2">
                    {reviewedRequests.map((request) => (
                      <div key={request.id} className={`rounded-lg border px-3 py-2 text-xs ${requestTone[request.status]}`}>
                        <strong>{request.employeeName}</strong> · {registrationStatusLabel[request.status]}
                        {request.adminReason ? <span className="mt-1 block opacity-80">{request.adminReason}</span> : null}
                      </div>
                    ))}
                  </div>
                </details>
              )}

              {isStaff && (
                <section className="mt-5 rounded-xl border border-[#0F2A43]/10 p-4">
                  {selected.slot.currentUserAssignment ? (
                    <div className="rounded-xl border border-emerald-200 bg-emerald-50 p-4">
                      <p className="text-[10px] font-bold uppercase tracking-[0.12em] text-emerald-700">Lịch của bạn</p>
                      <h3 className="mt-1 text-lg font-bold text-emerald-900">Bạn đã được phân ca này</h3>
                      <p className="mt-1 text-xs text-emerald-800">Trạng thái: {selected.slot.currentUserAssignment.status}</p>
                    </div>
                  ) : selected.slot.currentUserRequest?.status === "PENDING" ? (
                    <div className="rounded-xl border border-amber-200 bg-amber-50 p-4">
                      <p className="text-[10px] font-bold uppercase tracking-[0.12em] text-amber-700">Yêu cầu của bạn</p>
                      <h3 className="mt-1 text-lg font-bold text-amber-900">Đang chờ ADMIN duyệt</h3>
                      {selected.slot.currentUserRequest.staffNote && <p className="mt-2 text-xs leading-5 text-amber-800">{selected.slot.currentUserRequest.staffNote}</p>}
                      <button
                        type="button"
                        disabled={submitting}
                        onClick={() => void cancelRegistration()}
                        className="mt-3 min-h-11 rounded-lg border border-amber-400 bg-white px-4 text-xs font-bold text-amber-900 transition hover:bg-amber-100 disabled:opacity-45"
                      >
                        Hủy yêu cầu
                      </button>
                    </div>
                  ) : (
                    <>
                      {selected.slot.currentUserRequest && (
                        <div className={`mb-4 rounded-lg border p-3 text-xs ${requestTone[selected.slot.currentUserRequest.status]}`}>
                          <strong>{registrationStatusLabel[selected.slot.currentUserRequest.status]}</strong>
                          {selected.slot.currentUserRequest.adminReason && <span className="mt-1 block">{selected.slot.currentUserRequest.adminReason}</span>}
                        </div>
                      )}
                      <label>
                        <span className={labelClass}>Ghi chú cho ADMIN</span>
                        <textarea
                          data-modal-autofocus
                          value={staffNote}
                          maxLength={500}
                          rows={3}
                          disabled={selected.day.past || selected.slot.registrationOpen === false || selected.slot.availableSlots <= 0}
                          onChange={(event) => setStaffNote(event.target.value)}
                          placeholder="Ví dụ: Tôi có thể nhận ca này"
                          className={`${inputClass} resize-y`}
                        />
                      </label>
                      <button
                        type="button"
                        disabled={submitting || selected.day.past || selected.slot.registrationOpen === false || selected.slot.availableSlots <= 0}
                        onClick={() => void createRegistration()}
                        className="mt-3 min-h-11 w-full rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#173D5F] disabled:cursor-not-allowed disabled:opacity-45"
                      >
                        {submitting
                          ? "Đang gửi..."
                          : selected.day.past || selected.slot.registrationOpen === false
                            ? "Ca đã qua"
                            : selected.slot.availableSlots <= 0
                              ? "Ca đã đủ nhân sự"
                              : selected.slot.currentUserRequest
                                ? "Đăng ký lại"
                                : "Đăng ký ca này"}
                      </button>
                    </>
                  )}
                </section>
              )}
            </div>

            <footer className="flex justify-end border-t px-5 py-4">
              <button
                type="button"
                onClick={() => setSelectedKey(null)}
                className="min-h-11 rounded-lg border px-5 text-sm font-bold text-[#0F2A43] transition hover:bg-[#F4EFE5]"
              >
                Đóng
              </button>
            </footer>
          </div>
        )}
      </ViewportModal>

      {toast && <Toast {...toast} onClose={() => setToast(null)} />}
    </>
  );
}
