"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
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
  shiftWorkDate,
  unwrapWorkScheduleApiData,
  workShiftCalendarStatus,
  workShiftPeriod,
  workShiftStaffingSegments,
  type WorkScheduleEmployee,
  type WorkShiftCalendarAssignment,
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
  editorOverlayOpen?: boolean;
  onScheduleChanged?: () => Promise<void> | void;
  onEditAssignment?: (assignmentId: number) => Promise<void> | void;
  onCancelAssignment?: (assignmentId: number) => Promise<void> | void;
}

type SlotKey = { date: string; shiftTemplateId: number };
type ShiftFilter = "ALL" | WorkShiftPeriod;
type CalendarDisplayMode = "DAY" | "WEEK" | "MONTH";

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
const calendarDisplayOptions: Array<{ value: CalendarDisplayMode; label: string }> = [
  { value: "DAY", label: "Ngày" },
  { value: "WEEK", label: "Tuần" },
  { value: "MONTH", label: "Tháng" },
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

const currentDateKey = () => new Intl.DateTimeFormat("en-CA", {
  timeZone: HOTEL_TIME_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
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

const formatCompactDate = (date: string) => new Intl.DateTimeFormat("vi-VN", {
  day: "2-digit",
  month: "2-digit",
  timeZone: HOTEL_TIME_ZONE,
}).format(new Date(`${date}T12:00:00+07:00`));

const startOfWorkWeek = (date: string) => {
  const sundayFirst = new Date(`${date}T12:00:00+07:00`).getUTCDay();
  return shiftWorkDate(date, -((sundayFirst + 6) % 7));
};

const formatCalendarPeriod = (mode: CalendarDisplayMode, month: string, focusDate: string) => {
  if (mode === "MONTH") return formatMonth(month);
  if (mode === "DAY") return formatDay(focusDate);
  const from = startOfWorkWeek(focusDate);
  return `Tuần ${formatDay(from, false)} – ${formatDay(shiftWorkDate(from, 6), false)}`;
};

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

const calendarStatusTone = {
  active: "bg-sky-100 text-sky-800",
  available: "bg-white/80 text-[#526372]",
  danger: "bg-rose-100 text-rose-800",
  muted: "bg-slate-100 text-slate-600",
  pending: "bg-amber-100 text-amber-900",
  success: "bg-emerald-100 text-emerald-800",
  warning: "bg-orange-100 text-orange-800",
};

function Chevron({ direction }: { direction: "left" | "right" }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className="h-4 w-4 fill-none stroke-current stroke-2">
      <path d={direction === "left" ? "m15 18-6-6 6-6" : "m9 18 6-6-6-6"} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function isWeekend(date: string) {
  const day = new Date(`${date}T12:00:00+07:00`).getUTCDay();
  return day === 0 || day === 6;
}

function calendarDaySurface(day: WorkShiftCalendarDay, selected: boolean) {
  if (selected) {
    return "z-[1] bg-[#DBEAFE] ring-2 ring-inset ring-[#2563EB]";
  }
  if (day.today) {
    return "z-[1] bg-[#EFF6FF] ring-1 ring-inset ring-[#3B82F6]";
  }
  if (isWeekend(day.date)) {
    return "bg-[#F7F9FC]";
  }
  return "bg-white";
}

function drawerActionLabel(slot: WorkShiftCalendarSlot, isAdmin: boolean) {
  if (isAdmin) return "Quản lý ca";
  if (slot.currentUserAssignment) return "Xem ca của tôi";
  if (slot.currentUserRequest?.status === "PENDING") return "Xem yêu cầu";
  if (slot.availableSlots > 0 && slot.registrationOpen !== false) return "Đăng ký ca";
  return "Xem chi tiết";
}

function assignmentAttendanceLabel(assignment: WorkShiftCalendarAssignment) {
  if (assignment.status === "ABSENT") return "Vắng mặt";
  if (assignment.late) return assignment.lateMinutes > 0 ? `Muộn ${assignment.lateMinutes} phút` : "Đi muộn";
  if (assignment.sessionStatus === "ACTIVE") return "Đang làm việc";
  if (
    assignment.status === "FULFILLED"
    || assignment.sessionStatus === "CLOSED"
    || assignment.sessionStatus === "AUTO_CLOSED"
  ) return "Đã hoàn thành";
  return "Đã phân công";
}

export default function WorkforceMonthCalendar({
  isAdmin,
  isStaff,
  employees,
  refreshSignal = 0,
  editorOverlayOpen = false,
  onScheduleChanged,
  onEditAssignment,
  onCancelAssignment,
}: WorkforceMonthCalendarProps) {
  const [month, setMonth] = useState(currentMonthKey);
  const [focusDate, setFocusDate] = useState(currentDateKey);
  const [calendarDisplay, setCalendarDisplay] = useState<CalendarDisplayMode>("MONTH");
  const [calendar, setCalendar] = useState<WorkShiftMonthCalendar | null>(null);
  const [supplementalDays, setSupplementalDays] = useState<WorkShiftCalendarDay[]>([]);
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
  const latestLoadRequest = useRef(0);
  const previousRefreshSignal = useRef(refreshSignal);
  const lastVisibilityRefreshAt = useRef(0);

  const requestedMonthsKey = useMemo(() => {
    const requested = new Set([month]);
    if (calendarDisplay === "WEEK") {
      const weekStart = startOfWorkWeek(focusDate);
      Array.from({ length: 7 }, (_, index) => shiftWorkDate(weekStart, index))
        .forEach((date) => requested.add(date.slice(0, 7)));
    }
    return Array.from(requested).join(",");
  }, [calendarDisplay, focusDate, month]);

  const loadCalendar = useCallback(async (showLoading = false, force = false) => {
    const requestId = ++latestLoadRequest.current;
    if (showLoading) {
      setLoading(true);
      setCalendar(null);
      setLoadError(null);
    }
    try {
      const requestedMonths = requestedMonthsKey.split(",");
      const responses = await Promise.all(requestedMonths.map((requestedMonth) => cachedGet(
        `/api/work-schedules/calendar?month=${requestedMonth}`,
        { ttlMs: 3_000, force },
      )));
      if (requestId !== latestLoadRequest.current) return;
      const loadedCalendars = responses.map((response) =>
        unwrapWorkScheduleApiData<WorkShiftMonthCalendar>(response));
      const primaryCalendar = loadedCalendars.find((item) => item.month === month) || loadedCalendars[0];
      setCalendar(primaryCalendar);
      setSupplementalDays(loadedCalendars
        .filter((item) => item.month !== primaryCalendar.month)
        .flatMap((item) => item.days));
    } catch (error) {
      if (requestId !== latestLoadRequest.current) return;
      const message = getApiErrorMessage(error, "Không thể tải lịch làm việc");
      if (showLoading) {
        setCalendar(null);
        setLoadError(message);
      }
      setToast({
        type: "error",
        message,
      });
    } finally {
      if (requestId === latestLoadRequest.current) setLoading(false);
    }
  }, [month, requestedMonthsKey]);

  useEffect(() => {
    void loadCalendar(true);
    return () => {
      latestLoadRequest.current += 1;
    };
  }, [loadCalendar]);

  useEffect(() => {
    if (previousRefreshSignal.current === refreshSignal) return;
    previousRefreshSignal.current = refreshSignal;
    void loadCalendar(false, true);
  }, [loadCalendar, refreshSignal]);

  useEffect(() => {
    const refreshWhenVisible = () => {
      const now = Date.now();
      if (document.visibilityState !== "visible" || now - lastVisibilityRefreshAt.current < 1_000) return;
      lastVisibilityRefreshAt.current = now;
      void loadCalendar(false);
    };
    window.addEventListener("focus", refreshWhenVisible);
    document.addEventListener("visibilitychange", refreshWhenVisible);
    return () => {
      window.removeEventListener("focus", refreshWhenVisible);
      document.removeEventListener("visibilitychange", refreshWhenVisible);
    };
  }, [loadCalendar]);

  useEffect(() => {
    setSelectedDate(null);
    setSelectedKey(null);
  }, [calendarDisplay, month]);

  const loadedDays = useMemo(() => {
    const byDate = new Map<string, WorkShiftCalendarDay>();
    calendar?.days.forEach((day) => byDate.set(day.date, day));
    supplementalDays.forEach((day) => byDate.set(day.date, day));
    return Array.from(byDate.values()).sort((left, right) => left.date.localeCompare(right.date));
  }, [calendar, supplementalDays]);

  const displayedDays = useMemo(() => {
    if (!calendar) return [];
    if (calendarDisplay === "MONTH") return calendar.days;
    if (calendarDisplay === "DAY") {
      const day = loadedDays.find((item) => item.date === focusDate);
      return day ? [day] : [];
    }
    const weekStart = startOfWorkWeek(focusDate);
    return Array.from({ length: 7 }, (_, index) => shiftWorkDate(weekStart, index))
      .flatMap((date) => {
        const day = loadedDays.find((item) => item.date === date);
        return day ? [day] : [];
      });
  }, [calendar, calendarDisplay, focusDate, loadedDays]);

  const selectedDay = useMemo(() => {
    if (!selectedDate) return null;
    return loadedDays.find((item) => item.date === selectedDate) || null;
  }, [loadedDays, selectedDate]);

  const selected = useMemo(() => {
    if (!selectedKey) return null;
    const day = loadedDays.find((item) => item.date === selectedKey.date);
    const slot = day?.slots.find(
      (item) => item.shiftTemplateId === selectedKey.shiftTemplateId,
    );
    return day && slot ? { day, slot } : null;
  }, [loadedDays, selectedKey]);

  const navigateCalendar = (direction: -1 | 1) => {
    setSelectedDate(null);
    setSelectedKey(null);

    if (calendarDisplay === "MONTH") {
      const nextMonth = shiftCalendarMonth(month, direction);
      setMonth(nextMonth);
      setFocusDate(`${nextMonth}-01`);
      return;
    }

    const nextDate = shiftWorkDate(
      focusDate,
      direction * (calendarDisplay === "WEEK" ? 7 : 1),
    );
    setFocusDate(nextDate);
    setMonth(nextDate.slice(0, 7));
  };

  const goToToday = () => {
    const today = currentDateKey();
    setSelectedDate(null);
    setSelectedKey(null);
    setFocusDate(today);
    setMonth(today.slice(0, 7));
  };

  const changeCalendarDisplay = (mode: CalendarDisplayMode) => {
    setSelectedDate(null);
    setSelectedKey(null);
    setCalendarDisplay(mode);
    setMonth(focusDate.slice(0, 7));
  };

  const openSlot = (day: WorkShiftCalendarDay, slot: WorkShiftCalendarSlot) => {
    setSelectedKey({ date: day.date, shiftTemplateId: slot.shiftTemplateId });
    setStaffNote(slot.currentUserRequest?.staffNote || "");
    setReviewReasons({});
    setDirectEmployeeId(0);
    setDirectNote("");
    setRequiredStaff(slot.requiredStaff);
    setRequirementNote(slot.requirementNote || "");
  };

  const closeCalendarModalStack = () => {
    setSelectedKey(null);
    setSelectedDate(null);
  };

  const completeMutation = async (message: string) => {
    await loadCalendar(false, true);
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
    if (displayedDays.length === 0) {
      return {
        required: 0,
        assigned: 0,
        available: 0,
        pending: 0,
        myAssignments: 0,
        myPending: 0,
      };
    }

    return displayedDays.reduce((summary, day) => {
      day.slots
        .filter((slot) => shiftFilter === "ALL" || workShiftPeriod(slot) === shiftFilter)
        .forEach((slot) => {
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
  }, [displayedDays, shiftFilter]);
  const pendingRequests = selected?.slot.requests.filter(
    (request) => request.status === "PENDING",
  ) || [];
  const reviewedRequests = selected?.slot.requests.filter(
    (request) => request.status !== "PENDING",
  ) || [];

  return (
    <>
      <section className="ops-panel overflow-hidden rounded-2xl border shadow-sm" aria-labelledby="work-calendar-title">
        <header className="bg-[#0F2A43] px-5 py-5 text-white md:px-6">
          <div className="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between">
            <div>
              <p className="text-[10px] font-bold uppercase tracking-[0.2em] text-[#D8C398]">
                {isAdmin ? "Kế hoạch nhân sự" : "Lịch làm việc của tôi"}
              </p>
              <h2 id="work-calendar-title" className="mt-1 font-serif text-2xl font-bold capitalize">
                {formatCalendarPeriod(calendarDisplay, month, focusDate)}
              </h2>
              <p className="mt-2 max-w-3xl text-xs leading-5 text-white/70">
                {isAdmin
                  ? "Chọn một ca để xem nhân viên, duyệt đăng ký và điều chỉnh nhu cầu nhân sự."
                  : "Ca của bạn, yêu cầu chờ duyệt và số chỗ còn trống được phân biệt bằng màu."}
              </p>
            </div>
            <div className="flex flex-wrap items-end gap-2">
              <div>
                <span className="mb-1 block text-[9px] font-bold uppercase tracking-[0.13em] text-white/60">
                  Chế độ xem
                </span>
                <div className="flex min-h-11 rounded-xl border border-white/20 bg-[#173D5F] p-1" aria-label="Chế độ hiển thị lịch làm việc">
                  {calendarDisplayOptions.map((option) => (
                    <button
                      key={option.value}
                      type="button"
                      aria-pressed={calendarDisplay === option.value}
                      onClick={() => changeCalendarDisplay(option.value)}
                      className={`min-w-14 cursor-pointer rounded-lg px-3 text-xs font-bold transition duration-200 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-[#D8C398] ${
                        calendarDisplay === option.value
                          ? "bg-white text-[#0F2A43] shadow-sm"
                          : "text-white/75 hover:bg-white/10 hover:text-white"
                      }`}
                    >
                      {option.label}
                    </button>
                  ))}
                </div>
              </div>
              <label className="min-w-36 text-[9px] font-bold uppercase tracking-[0.13em] text-white/60">
                Ca làm việc
                <select
                  aria-label="Lọc ca trên lịch làm việc"
                  value={shiftFilter}
                  onChange={(event) => setShiftFilter(event.target.value as ShiftFilter)}
                  className="mt-1 block min-h-11 w-full cursor-pointer rounded-xl border border-white/20 bg-[#173D5F] px-3 text-xs font-bold normal-case tracking-normal text-white outline-none transition hover:border-white/35 focus-visible:border-[#D8C398] focus-visible:ring-2 focus-visible:ring-[#D8C398]/25"
                >
                  {shiftFilterOptions.map((option) => (
                    <option key={option.value} value={option.value}>{option.label}</option>
                  ))}
                </select>
              </label>
              <div className="flex items-center gap-2" aria-label="Điều hướng lịch làm việc">
                <button
                  type="button"
                  aria-label={calendarDisplay === "MONTH" ? "Tháng trước" : calendarDisplay === "WEEK" ? "Tuần trước" : "Ngày trước"}
                  onClick={() => navigateCalendar(-1)}
                  className="flex min-h-11 min-w-11 cursor-pointer items-center justify-center rounded-xl border border-white/20 bg-white/10 text-white transition duration-200 hover:bg-white/20 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#D8C398]"
                >
                  <Chevron direction="left" />
                </button>
                <button
                  type="button"
                  onClick={goToToday}
                  className="min-h-11 cursor-pointer rounded-xl border border-white/20 bg-white/10 px-4 text-xs font-bold text-white transition duration-200 hover:bg-white/20 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#D8C398]"
                >
                  Hôm nay
                </button>
                <button
                  type="button"
                  aria-label={calendarDisplay === "MONTH" ? "Tháng sau" : calendarDisplay === "WEEK" ? "Tuần sau" : "Ngày sau"}
                  onClick={() => navigateCalendar(1)}
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
          <div className="grid gap-3 p-5 sm:grid-cols-2 lg:grid-cols-4" aria-label="Đang tải lịch làm việc">
            {Array.from({ length: 8 }, (_, index) => (
              <div key={index} className="h-40 animate-pulse rounded-xl bg-[#0F2A43]/6" />
            ))}
          </div>
        ) : loadError ? (
          <div className="flex min-h-64 flex-col items-center justify-center px-5 py-10 text-center" role="alert">
            <div className="flex h-11 w-11 items-center justify-center rounded-full bg-rose-50 text-lg font-bold text-rose-700" aria-hidden="true">!</div>
            <h3 className="mt-3 text-base font-bold text-[#0F2A43]">Chưa tải được lịch làm việc</h3>
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
            {calendarDisplay === "MONTH" ? (
              <>
            <div className="grid grid-cols-7 rounded-t-2xl border border-b-0 border-[#D8E0E8] bg-[#F7F9FC] px-px pt-px">
              {weekdays.map((day, index) => (
                <div
                  key={day}
                  className={`py-2 text-center text-[9px] font-black uppercase tracking-[0.12em] sm:text-[10px] ${index >= 5 ? "text-[#496579]" : "text-[#526372]"}`}
                >
                  {day}
                </div>
              ))}
            </div>

            <div className="grid grid-cols-7 gap-px overflow-hidden rounded-b-2xl border border-[#D8E0E8] bg-[#D8E0E8] p-px">
              {Array.from({ length: leadingDays }, (_, index) => (
                <div key={`empty-${index}`} aria-hidden="true" className="min-h-[104px] bg-[#EEF2F3] opacity-70 sm:min-h-[116px]" />
              ))}

              {calendar.days.map((day) => {
                const visibleSlots = day.slots.filter((slot) => (
                  shiftFilter === "ALL" || workShiftPeriod(slot) === shiftFilter
                ));
                const selectedDateActive = selectedDate === day.date;
                const pendingCount = day.slots.reduce((total, slot) => total + slot.pendingRequestCount, 0);

                return (
                  <article
                    key={day.date}
                    onClick={() => {
                      setFocusDate(day.date);
                      setSelectedDate(day.date);
                    }}
                    className={`group relative min-h-[104px] overflow-hidden text-left transition duration-200 hover:z-[1] hover:shadow-[inset_0_0_0_1px_rgba(59,130,246,0.24)] sm:min-h-[116px] ${calendarDaySurface(day, selectedDateActive)}`}
                  >
                    <div className="relative z-[1] p-1.5 sm:p-2">
                    <button
                      type="button"
                      onClick={(event) => {
                        event.stopPropagation();
                        setFocusDate(day.date);
                        setSelectedDate(day.date);
                      }}
                      aria-label={`Mở tổng quan ${formatDay(day.date)}. ${day.slots.map((slot) => `${slot.shiftName}: ${workShiftCalendarStatus(slot, isAdmin, day.past).label}`).join(". ")}`}
                      className="mb-1.5 flex min-h-7 w-full cursor-pointer items-center justify-between gap-1 rounded-md text-left outline-none transition hover:bg-[#0F2A43]/[0.04] focus-visible:ring-2 focus-visible:ring-[#B8944F]"
                    >
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
                          <span className="hidden rounded-full bg-[#DBEAFE] px-1.5 py-0.5 text-[7px] font-black uppercase tracking-wide text-[#1D4E89] xl:inline">Hôm nay</span>
                        ) : null}
                      </span>
                    </button>

                    <span className="block space-y-1">
                      {visibleSlots.length > 0 ? visibleSlots.map((slot) => {
                        const period = workShiftPeriod(slot);
                        const visual = shiftVisuals[period];
                        const status = workShiftCalendarStatus(slot, isAdmin, day.past);
                        const segments = workShiftStaffingSegments(slot.assignedCount, slot.requiredStaff);

                        return (
                          <button
                            type="button"
                            key={slot.shiftTemplateId}
                            onClick={(event) => {
                              event.stopPropagation();
                              openSlot(day, slot);
                            }}
                            aria-label={`Mở chi tiết ${slot.shiftName} ngày ${formatDay(day.date, false)}. ${status.label}`}
                            title={`${slot.shiftName} ${formatShiftTime(slot.startTime)}–${formatShiftTime(slot.endTime)} · ${slot.assignedCount}/${slot.requiredStaff} đã phân · ${status.label}`}
                            className={`pointer-events-auto relative z-10 grid min-h-6 w-full cursor-pointer grid-cols-[16px_1fr] items-center gap-1 rounded-md border px-1 py-0.5 text-left transition duration-150 hover:-translate-y-px hover:shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#0F2A43] sm:grid-cols-[18px_24px_1fr] ${visual.row}`}
                          >
                            <span className={`flex h-4 w-4 items-center justify-center rounded text-[8px] font-black sm:h-[18px] sm:w-[18px] sm:text-[9px] ${visual.compact}`}>
                              {compactWorkShiftLabel(period)}
                            </span>
                            <span className="hidden grid-cols-3 gap-0.5 sm:grid" aria-label={`${slot.assignedCount}/${slot.requiredStaff} vị trí đã phân`}>
                              {segments.map((filled, segmentIndex) => (
                                <i key={segmentIndex} className={`h-1.5 rounded-full ${filled ? visual.marker : "bg-white/80 ring-1 ring-inset ring-current/15"}`} aria-hidden="true" />
                              ))}
                            </span>
                            <span className={`truncate rounded px-1 py-0.5 text-[8px] font-bold leading-none sm:text-[9px] lg:text-[10px] ${calendarStatusTone[status.tone]}`}>
                              <span className="sm:hidden">{status.compactLabel}</span>
                              <span className="hidden sm:inline">{status.label}</span>
                            </span>
                          </button>
                        );
                      }) : (
                        <span className="flex min-h-[70px] items-center justify-center text-xs font-bold text-[#98A1A8]" aria-hidden="true">—</span>
                      )}
                    </span>
                    </div>
                  </article>
                );
              })}

              {Array.from({ length: trailingDays }, (_, index) => (
                <div key={`trailing-${index}`} aria-hidden="true" className="min-h-[104px] bg-[#EEF2F3] opacity-70 sm:min-h-[116px]" />
              ))}
            </div>

            <p className="mt-3 text-center text-[10px] font-semibold text-[#66727C]">
              Chọn nền ô ngày để xem tổng quan; chọn trực tiếp S/C/T để mở chi tiết ca.
            </p>
              </>
            ) : (
              <div className={calendarDisplay === "DAY" ? "mx-auto max-w-6xl" : ""}>
                <div className={`grid gap-3 ${
                  calendarDisplay === "DAY"
                    ? "grid-cols-1"
                    : "sm:grid-cols-2 lg:grid-cols-4 2xl:grid-cols-7"
                }`}>
                  {displayedDays.map((day) => {
                    const visibleSlots = day.slots.filter((slot) => (
                      shiftFilter === "ALL" || workShiftPeriod(slot) === shiftFilter
                    ));
                    const required = visibleSlots.reduce((total, slot) => total + slot.requiredStaff, 0);
                    const assigned = visibleSlots.reduce((total, slot) => total + slot.assignedCount, 0);
                    const pending = visibleSlots.reduce((total, slot) => total + slot.pendingRequestCount, 0);

                    return (
                      <article
                        key={day.date}
                        className={`overflow-hidden rounded-2xl border bg-white shadow-sm ${
                          day.today
                            ? "border-[#B8944F] ring-1 ring-[#B8944F]/25"
                            : "border-[#0F2A43]/10"
                        }`}
                      >
                        <button
                          type="button"
                          onClick={() => {
                            setFocusDate(day.date);
                            setSelectedDate(day.date);
                          }}
                          className={`flex min-h-16 w-full cursor-pointer items-center justify-between gap-3 border-b px-4 py-3 text-left transition hover:bg-[#F8F5EE] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[#B8944F] ${
                            isWeekend(day.date) ? "bg-[#F8F5EE]/75" : "bg-white"
                          }`}
                        >
                          <span>
                            <span className="block text-[9px] font-black uppercase tracking-[0.14em] text-[#80632F]">
                              {new Intl.DateTimeFormat("vi-VN", { weekday: "long", timeZone: HOTEL_TIME_ZONE }).format(new Date(`${day.date}T12:00:00+07:00`))}
                            </span>
                            <span className="mt-0.5 block text-lg font-black tabular-nums text-[#0F2A43]">
                              {calendarDisplay === "WEEK" ? formatCompactDate(day.date) : formatDay(day.date, false)}
                            </span>
                          </span>
                          <span className="text-right">
                            {day.today ? (
                              <span className="mb-1 inline-flex rounded-full bg-[#0F2A43] px-2 py-1 text-[8px] font-black uppercase tracking-wide text-white">Hôm nay</span>
                            ) : null}
                            <span className="block text-[10px] font-bold text-[#66727C]">
                              {assigned}/{required} nhân sự{pending > 0 ? ` · ${pending} chờ` : ""}
                            </span>
                          </span>
                        </button>

                        <div className={`grid gap-3 p-3 ${calendarDisplay === "DAY" ? "md:grid-cols-3 md:p-4" : ""}`}>
                          {visibleSlots.length > 0 ? visibleSlots.map((slot) => {
                            const period = workShiftPeriod(slot);
                            const visual = shiftVisuals[period];
                            const status = workShiftCalendarStatus(slot, isAdmin, day.past);
                            const employeeNames = slot.assignments.map((assignment) => assignment.employeeName);

                            return (
                              <button
                                type="button"
                                key={slot.shiftTemplateId}
                                onClick={() => openSlot(day, slot)}
                                className={`group min-h-32 w-full cursor-pointer rounded-xl border p-3 text-left transition duration-200 hover:-translate-y-0.5 hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#0F2A43] ${visual.row}`}
                              >
                                <span className="flex items-start justify-between gap-2">
                                  <span className="flex items-center gap-2">
                                    <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg text-base font-black ${visual.compact}`} aria-hidden="true">
                                      {visual.icon}
                                    </span>
                                    <span>
                                      <span className="block text-sm font-black text-[#0F2A43]">{slot.shiftName}</span>
                                      <span className="mt-0.5 block text-[10px] font-bold tabular-nums text-[#66727C]">
                                        {formatShiftTime(slot.startTime)}–{formatShiftTime(slot.endTime)}
                                      </span>
                                    </span>
                                  </span>
                                  <span className={`max-w-[9rem] rounded-full px-2 py-1 text-right text-[9px] font-black leading-3 ${calendarStatusTone[status.tone]}`}>
                                    {status.label}
                                  </span>
                                </span>

                                <span className="mt-3 block border-t border-current/10 pt-2">
                                  <span className="flex items-center justify-between text-[10px] font-bold text-[#526372]">
                                    <span>Nhân sự</span>
                                    <span className="tabular-nums">{slot.assignedCount}/{slot.requiredStaff}</span>
                                  </span>
                                  {isAdmin ? (
                                    <span className="mt-2 block min-h-8 text-[10px] leading-4 text-[#66727C]">
                                      {employeeNames.length > 0
                                        ? employeeNames.slice(0, 2).join(" · ")
                                        : "Chưa phân nhân viên"}
                                      {employeeNames.length > 2 ? ` +${employeeNames.length - 2}` : ""}
                                    </span>
                                  ) : (
                                    <span className="mt-2 block min-h-8 text-[10px] leading-4 text-[#66727C]">
                                      {slot.currentUserAssignment
                                        ? assignmentAttendanceLabel(slot.currentUserAssignment)
                                        : drawerActionLabel(slot, false)}
                                    </span>
                                  )}
                                </span>
                              </button>
                            );
                          }) : (
                            <div className="flex min-h-32 items-center justify-center rounded-xl border border-dashed border-[#0F2A43]/15 bg-[#F8F5EE]/60 px-4 text-center text-xs font-bold text-[#66727C]">
                              Không có ca phù hợp bộ lọc
                            </div>
                          )}
                        </div>
                      </article>
                    );
                  })}
                </div>

                <p className="mt-4 text-center text-[10px] font-semibold text-[#66727C]">
                  Chọn ngày để xem tổng quan; chọn trực tiếp một ca để xem nhân viên và thao tác chi tiết.
                </p>
              </div>
            )}
          </div>
        ) : null}
      </section>

      <ViewportModal
        open={Boolean(selectedDay)}
        onClose={() => setSelectedDate(null)}
        onBackdropClose={closeCalendarModalStack}
        labelledBy="workforce-day-title"
        ariaModal={!selected}
        panelClassName="max-w-5xl bg-[#FBFAF6]"
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

            <div className="lux-scrollbar min-h-0 flex-1 overflow-y-auto p-4 sm:p-5">
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

              <div className="mt-4 grid gap-3 lg:grid-cols-3">
              {selectedDay.slots.map((slot) => {
                const period = workShiftPeriod(slot);
                const visual = shiftVisuals[period];
                const status = workShiftCalendarStatus(slot, isAdmin, selectedDay.past);

                return (
                  <article key={slot.shiftTemplateId} className={`flex h-full flex-col overflow-hidden rounded-2xl border bg-white shadow-sm ${visual.row}`}>
                    <div className="flex flex-1 flex-col bg-white/85 p-4">
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
                        <span className={`shrink-0 rounded-full px-2.5 py-1 text-[9px] font-black ${calendarStatusTone[status.tone]}`}>
                          {status.label}
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
                                {assignment.employeeName} · {assignmentAttendanceLabel(assignment)}
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
                        className="mt-auto min-h-11 w-full cursor-pointer rounded-xl bg-[#0F2A43] px-4 text-xs font-black text-white transition duration-200 hover:bg-[#173D5F] active:scale-[0.99] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#B8944F]"
                      >
                        {drawerActionLabel(slot, isAdmin)}
                      </button>
                    </div>
                  </article>
                );
              })}
              </div>
            </div>
          </div>
        )}
      </ViewportModal>

      <ViewportModal
        open={Boolean(selected)}
        onClose={() => setSelectedKey(null)}
        onBackdropClose={closeCalendarModalStack}
        labelledBy="workforce-slot-title"
        ariaModal={!editorOverlayOpen}
        busy={submitting}
        panelClassName="max-w-5xl bg-[#FBFAF6]"
        backdropClassName="bg-[#091E30]/60 backdrop-blur-[2px]"
        zIndexClassName="z-[95]"
      >
        {selected && (
          <div className="flex min-h-0 flex-1 flex-col">
            <header className="border-b border-white/10 bg-[#0F2A43] px-5 py-5 text-white">
              <div className="flex items-start justify-between gap-4">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#D8C398]">
                      {isAdmin ? "Chi tiết & quản lý ca" : "Chi tiết ca làm việc"}
                    </p>
                    <span className={`rounded-full px-2.5 py-1 text-[9px] font-black ${calendarStatusTone[workShiftCalendarStatus(selected.slot, isAdmin, selected.day.past).tone]}`}>
                      {workShiftCalendarStatus(selected.slot, isAdmin, selected.day.past).label}
                    </span>
                  </div>
                  <h2 id="workforce-slot-title" className="mt-1 truncate font-serif text-2xl font-bold">
                    {selected.slot.shiftName} · {formatDay(selected.day.date)}
                  </h2>
                  <p className="mt-1 text-xs font-semibold text-white/65">
                    {formatShiftTime(selected.slot.startTime)}–{formatShiftTime(selected.slot.endTime)}
                    {selected.slot.crossesMidnight ? " · kết thúc ngày hôm sau" : ""}
                  </p>
                </div>
                <button
                  type="button"
                  aria-label={selectedDay ? "Quay lại lịch trong ngày" : "Đóng chi tiết ca"}
                  disabled={submitting}
                  onClick={() => setSelectedKey(null)}
                  className="flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center rounded-full border border-white/20 bg-white/10 text-lg font-bold transition hover:bg-white/20 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#D8C398] disabled:cursor-not-allowed disabled:opacity-50"
                >
                  ×
                </button>
              </div>
            </header>

            <div className="lux-scrollbar min-h-0 flex-1 overflow-y-auto p-4 sm:p-5">
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
                      {selected.slot.assignments.length ? selected.slot.assignments.map((assignment) => {
                        const editable = assignment.status === "SCHEDULED"
                          && !assignment.sessionStatus
                          && !selected.day.past;
                        return (
                        <div key={assignment.id} className="flex flex-col gap-3 rounded-lg bg-[#F4EFE5] px-3 py-2.5 sm:flex-row sm:items-center sm:justify-between">
                          <div className="min-w-0">
                            <p className="text-sm font-bold text-[#0F2A43]">{assignment.employeeName}</p>
                            <p className="mt-0.5 text-[10px] text-[#66727C]">Lịch #{assignment.id}</p>
                          </div>
                          <div className="flex flex-wrap items-center gap-2 sm:justify-end">
                            <span className="rounded-full bg-white px-2 py-1 text-[9px] font-bold text-[#27445F]">{assignmentAttendanceLabel(assignment)}</span>
                            {editable && onEditAssignment && (
                              <button
                                type="button"
                                disabled={submitting}
                                onClick={() => {
                                  void onEditAssignment(assignment.id);
                                }}
                                className="min-h-9 cursor-pointer rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-[10px] font-bold text-[#0F2A43] transition duration-200 hover:border-[#B8944F] hover:bg-[#FFF9EA] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] disabled:cursor-not-allowed disabled:opacity-50"
                              >
                                Đổi nhân viên
                              </button>
                            )}
                            {editable && onCancelAssignment && (
                              <button
                                type="button"
                                disabled={submitting}
                                onClick={() => {
                                  void onCancelAssignment(assignment.id);
                                }}
                                className="min-h-9 cursor-pointer rounded-lg border border-rose-200 bg-white px-3 text-[10px] font-bold text-rose-700 transition duration-200 hover:bg-rose-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-400 disabled:cursor-not-allowed disabled:opacity-50"
                              >
                                Hủy phân ca
                              </button>
                            )}
                          </div>
                        </div>
                      );
                      }) : (
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

            <footer className="flex justify-end border-t border-[#0F2A43]/10 bg-white px-5 py-4">
              <button
                type="button"
                onClick={() => setSelectedKey(null)}
                className="min-h-11 rounded-lg border px-5 text-sm font-bold text-[#0F2A43] transition hover:bg-[#F4EFE5]"
              >
                {selectedDay ? "Quay lại lịch trong ngày" : "Đóng chi tiết"}
              </button>
            </footer>
          </div>
        )}
      </ViewportModal>

      {toast && <Toast {...toast} onClose={() => setToast(null)} />}
    </>
  );
}
