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
  formatShiftTime,
  shiftCalendarMonth,
  staffCalendarSlotLabel,
  unwrapWorkScheduleApiData,
  type WorkScheduleEmployee,
  type WorkShiftCalendarDay,
  type WorkShiftCalendarSlot,
  type WorkShiftMonthCalendar,
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

const HOTEL_TIME_ZONE = "Asia/Ho_Chi_Minh";
const weekdays = ["T2", "T3", "T4", "T5", "T6", "T7", "CN"];
const inputClass = "ops-control min-h-11 w-full rounded-lg border px-3 py-2.5 text-sm font-semibold text-[#0F2A43] outline-none transition hover:border-[#0F2A43]/30 focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20 disabled:cursor-not-allowed disabled:opacity-60";
const labelClass = "mb-2 block text-[11px] font-bold uppercase tracking-[0.08em] text-[#66727C]";

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

function slotTone(slot: WorkShiftCalendarSlot, isAdmin: boolean, past = false) {
  if (!isAdmin && slot.currentUserAssignment) {
    return "border-emerald-300 bg-emerald-50 text-emerald-900";
  }
  if (!isAdmin && slot.currentUserRequest?.status === "PENDING") {
    return "border-amber-300 bg-amber-50 text-amber-900";
  }
  if (isAdmin && slot.pendingRequestCount > 0) {
    return "border-amber-300 bg-amber-50 text-amber-900";
  }
  if (past || slot.registrationOpen === false) {
    return "border-slate-200 bg-slate-50 text-slate-500";
  }
  if (slot.availableSlots > 0) {
    return "border-[#B8944F]/45 bg-[#FBF7EE] text-[#0F2A43]";
  }
  return isAdmin
    ? "border-emerald-200 bg-emerald-50 text-emerald-900"
    : "border-slate-200 bg-slate-50 text-slate-600";
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
  const [selectedKey, setSelectedKey] = useState<SlotKey | null>(null);
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

  const selected = useMemo(() => {
    if (!selectedKey || !calendar) return null;
    const day = calendar.days.find((item) => item.date === selectedKey.date);
    const slot = day?.slots.find(
      (item) => item.shiftTemplateId === selectedKey.shiftTemplateId,
    );
    return day && slot ? { day, slot } : null;
  }, [calendar, selectedKey]);

  const openSlot = (day: WorkShiftCalendarDay, slot: WorkShiftCalendarSlot) => {
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
            <div className="flex items-center gap-2">
              <button
                type="button"
                aria-label="Tháng trước"
                onClick={() => setMonth((value) => shiftCalendarMonth(value, -1))}
                className="flex min-h-11 min-w-11 items-center justify-center rounded-xl border border-white/20 bg-white/10 text-white transition duration-200 hover:bg-white/20 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#D8C398]"
              >
                <Chevron direction="left" />
              </button>
              <button
                type="button"
                onClick={() => setMonth(currentMonthKey())}
                className="min-h-11 rounded-xl border border-white/20 bg-white/10 px-4 text-xs font-bold text-white transition duration-200 hover:bg-white/20 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#D8C398]"
              >
                Hôm nay
              </button>
              <button
                type="button"
                aria-label="Tháng sau"
                onClick={() => setMonth((value) => shiftCalendarMonth(value, 1))}
                className="flex min-h-11 min-w-11 items-center justify-center rounded-xl border border-white/20 bg-white/10 text-white transition duration-200 hover:bg-white/20 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#D8C398]"
              >
                <Chevron direction="right" />
              </button>
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
            <div className="mt-3 flex flex-wrap items-center gap-x-5 gap-y-2 text-[10px] font-semibold text-[#66727C]" aria-label="Chú thích trạng thái lịch">
              {(isAdmin
                ? [
                    ["bg-emerald-500", "Đủ nhân sự"],
                    ["bg-[#B8944F]", "Còn thiếu nhân sự"],
                    ["bg-amber-500", "Có yêu cầu chờ duyệt"],
                  ]
                : [
                    ["bg-emerald-500", "Ca của bạn"],
                    ["bg-[#B8944F]", "Ca còn trống"],
                    ["bg-amber-500", "Yêu cầu chờ duyệt"],
                  ]
              ).map(([tone, label]) => (
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
          <>
            <div className="lux-scrollbar hidden overflow-x-auto lg:block">
              <div className="min-w-[1120px] p-5">
                <div className="mb-2 grid grid-cols-7 gap-2 rounded-xl bg-[#0F2A43]/[0.045] p-2">
                  {weekdays.map((day) => (
                    <div key={day} className="px-2 py-1 text-center text-[10px] font-bold uppercase tracking-[0.16em] text-[#526372]">
                      {day}
                    </div>
                  ))}
                </div>
                <div className="grid grid-cols-7 gap-2.5">
                  {Array.from({ length: leadingDays }, (_, index) => (
                    <div key={`empty-${index}`} aria-hidden="true" className="min-h-[186px] rounded-xl bg-[#0F2A43]/[0.018]" />
                  ))}
                  {calendar.days.map((day) => (
                    <article
                      key={day.date}
                      className={`min-h-[186px] rounded-xl border p-2.5 transition ${
                        day.today
                          ? "border-[#B8944F] bg-[#FFF9EA] shadow-[0_8px_22px_rgba(15,42,67,0.08)]"
                          : day.past
                            ? "border-slate-200 bg-slate-50/75"
                            : "border-[#0F2A43]/10 bg-white hover:border-[#0F2A43]/20"
                      }`}
                    >
                      <div className="mb-2.5 flex items-center justify-between border-b border-[#0F2A43]/7 pb-2">
                        <span className={`flex h-8 min-w-8 items-center justify-center rounded-full px-1 text-xs font-bold ${day.today ? "bg-[#0F2A43] text-white" : "text-[#0F2A43]"}`}>
                          {Number(day.date.slice(-2))}
                        </span>
                        {day.today ? (
                          <span className="rounded-full bg-[#B8944F]/15 px-2 py-1 text-[8px] font-bold uppercase tracking-wide text-[#80632F]">Hôm nay</span>
                        ) : null}
                      </div>
                      <div className="space-y-2">
                        {day.slots.map((slot) => (
                          <button
                            key={slot.shiftTemplateId}
                            type="button"
                            onClick={() => openSlot(day, slot)}
                            style={{ boxShadow: `inset 3px 0 0 ${slot.shiftColor}` }}
                            className={`min-h-[54px] w-full rounded-lg border px-2.5 py-2 text-left transition duration-200 hover:-translate-y-0.5 hover:shadow-md focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-[#B8944F] ${slotTone(slot, isAdmin, day.past)}`}
                          >
                            <span className="flex items-start justify-between gap-2">
                              <span className="min-w-0">
                                <strong className="block truncate text-[11px] leading-4">{slot.shiftName}</strong>
                                <span className="mt-0.5 block text-[9px] font-semibold opacity-70">
                                  {formatShiftTime(slot.startTime)}–{formatShiftTime(slot.endTime)}
                                </span>
                              </span>
                              {isAdmin && (
                                <span className="shrink-0 rounded-full bg-white/80 px-1.5 py-0.5 text-[9px] font-bold tabular-nums">
                                  {slot.assignedCount}/{slot.requiredStaff}
                                </span>
                              )}
                            </span>
                            <span className="mt-1 block truncate text-[9px] font-semibold opacity-80">
                              {isAdmin
                                ? slot.pendingRequestCount
                                  ? `${slot.pendingRequestCount} yêu cầu chờ duyệt`
                                  : slot.availableSlots > 0
                                    ? `Còn thiếu ${slot.availableSlots} người`
                                    : "Đủ nhân sự"
                                : staffCalendarSlotLabel(slot, day.past)}
                            </span>
                          </button>
                        ))}
                      </div>
                    </article>
                  ))}
                </div>
              </div>
            </div>

            <div className="space-y-3 p-4 lg:hidden">
              {calendar.days.map((day) => (
                <article
                  key={day.date}
                  className={`rounded-xl border p-3 ${
                    day.today
                      ? "border-[#B8944F] bg-[#FFF9EA] shadow-sm"
                      : day.past
                        ? "border-slate-200 bg-slate-50/70"
                        : "border-[#0F2A43]/10 bg-white"
                  }`}
                >
                  <div className="mb-3 flex items-center justify-between gap-3">
                    <h3 className="text-sm font-bold capitalize text-[#0F2A43]">{formatDay(day.date)}</h3>
                    {day.today && <span className="rounded-full bg-[#0F2A43] px-2 py-1 text-[9px] font-bold uppercase tracking-wide text-white">Hôm nay</span>}
                  </div>
                  <div className="grid gap-2 sm:grid-cols-3">
                    {day.slots.map((slot) => (
                      <button
                        key={slot.shiftTemplateId}
                        type="button"
                        onClick={() => openSlot(day, slot)}
                        style={{ boxShadow: `inset 3px 0 0 ${slot.shiftColor}` }}
                        className={`min-h-[62px] rounded-lg border px-3 py-2.5 text-left transition active:scale-[0.99] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-[#B8944F] ${slotTone(slot, isAdmin, day.past)}`}
                      >
                        <span className="flex items-center justify-between gap-2">
                          <strong className="text-xs">{slot.shiftName}</strong>
                          <span className="text-[9px] font-semibold opacity-70">{formatShiftTime(slot.startTime)}–{formatShiftTime(slot.endTime)}</span>
                        </span>
                        <span className="mt-1 block text-[10px] font-semibold opacity-80">
                          {isAdmin
                            ? `${slot.assignedCount}/${slot.requiredStaff} đã phân${slot.pendingRequestCount ? ` · ${slot.pendingRequestCount} chờ` : ""}`
                            : staffCalendarSlotLabel(slot, day.past)}
                        </span>
                      </button>
                    ))}
                  </div>
                </article>
              ))}
            </div>
          </>
        ) : null}
      </section>

      <ViewportModal
        open={Boolean(selected)}
        onClose={() => setSelectedKey(null)}
        labelledBy="workforce-slot-title"
        busy={submitting}
        panelClassName="max-w-4xl"
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
