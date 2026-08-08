"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { apiClient, cachedGet, getApiErrorMessage } from "@/lib/api";
import {
  clearIdempotencyKey,
  getOrCreateIdempotencyKey,
} from "@/lib/idempotency";
import Toast from "@/components/UI/Toast";
import ViewportModal from "@/components/UI/ViewportModal";
import WorkAttendanceStatistics from "@/components/dashboard/WorkAttendanceStatistics";
import WorkScheduleListView from "@/components/dashboard/WorkScheduleListView";
import WorkforceMonthCalendar from "@/components/dashboard/WorkforceMonthCalendar";
import WorkDailyShiftModals, {
  type WorkDailyShiftAction,
} from "@/components/dashboard/WorkDailyShiftModals";
import WorkShiftTemplateManagerModal from "@/components/dashboard/WorkShiftTemplateManagerModal";
import { useDashboardRole } from "@/hooks/use-dashboard-role";
import {
  formatShiftTime,
  formatWorkDateTime,
  isCheckInAvailable,
  shiftWorkDate,
  unwrapWorkScheduleApiData,
  workScheduleDisplayStatus,
  workScheduleTone,
  workShiftColorForStartTime,
  workShiftSortOrderForStartTime,
  type WorkSchedule,
  type WorkScheduleEmployee,
  type WorkScheduleForm,
  type WorkScheduleStatus,
  type WorkShiftTemplate,
  type WorkShiftTemplateForm,
} from "@/lib/work-schedules";

type ToastState = { message: string; type: "success" | "error" | "info" };
type DateRangePreset = "DAY" | "WEEK" | "MONTH" | "CUSTOM";
type WorkScheduleView = "calendar" | "statistics" | "list";
type WorkScheduleOperationalFilter = "ALL" | "ACTION_REQUIRED" | "ACTIVE" | WorkScheduleStatus;

const HOTEL_TIME_ZONE = "Asia/Ho_Chi_Minh";
const inputClass = "ops-control min-h-11 w-full rounded-lg border px-3 py-2.5 text-sm font-semibold text-[#0F2A43] outline-none transition hover:border-[#0F2A43]/30 focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20 disabled:cursor-not-allowed disabled:opacity-60";
const labelClass = "mb-2 block text-xs font-bold text-[#66727C]";

const dateKey = (date = new Date()) => new Intl.DateTimeFormat("en-CA", {
  timeZone: HOTEL_TIME_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
}).format(date);

const dateRangeForPreset = (preset: Exclude<DateRangePreset, "CUSTOM">) => {
  const today = dateKey();
  if (preset === "DAY") return { from: today, to: today };

  if (preset === "WEEK") {
    const [year, month, day] = today.split("-").map(Number);
    const sundayFirst = new Date(Date.UTC(year, month - 1, day)).getUTCDay();
    const daysFromMonday = (sundayFirst + 6) % 7;
    const from = shiftWorkDate(today, -daysFromMonday);
    return { from, to: shiftWorkDate(from, 6) };
  }

  const [year, month] = today.split("-").map(Number);
  const from = `${today.slice(0, 7)}-01`;
  const to = new Date(Date.UTC(year, month, 0)).toISOString().slice(0, 10);
  return { from, to };
};

const formatWorkDate = (value: string) => new Intl.DateTimeFormat("vi-VN", {
  weekday: "long",
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  timeZone: HOTEL_TIME_ZONE,
}).format(new Date(`${value}T12:00:00+07:00`));

const emptyScheduleForm = (): WorkScheduleForm => ({
  employeeId: 0,
  shiftTemplateId: 0,
  workDate: dateKey(),
  note: "",
});

const emptyTemplateForm = (): WorkShiftTemplateForm => ({
  code: "",
  name: "",
  startTime: "07:00",
  endTime: "12:00",
  checkInEarlyMinutes: 30,
  lateToleranceMinutes: 10,
  color: "#B8944F",
  sortOrder: 0,
  active: true,
});

const scheduleToneClass: Record<ReturnType<typeof workScheduleTone>, string> = {
  active: "border-emerald-300 bg-emerald-50 text-emerald-800",
  danger: "border-rose-300 bg-rose-50 text-rose-800",
  muted: "border-slate-200 bg-slate-50 text-slate-600",
  success: "border-blue-200 bg-blue-50 text-blue-800",
  scheduled: "border-amber-200 bg-amber-50 text-amber-900",
};

function ScheduleStatusBadge({ schedule }: { schedule: WorkSchedule }) {
  return <span className={`inline-flex min-h-7 items-center rounded-full border px-2.5 text-[10px] font-bold uppercase tracking-[0.08em] ${scheduleToneClass[workScheduleTone(schedule)]}`}>{workScheduleDisplayStatus(schedule)}</span>;
}

function EmptySchedule({ admin }: { admin: boolean }) {
  return <div className="rounded-xl border border-dashed border-[#0F2A43]/20 bg-white/60 px-6 py-12 text-center"><span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-[#0F2A43]/6 text-2xl" aria-hidden="true">◷</span><h3 className="mt-4 font-serif text-xl font-bold text-[#0F2A43]">Chưa có lịch trong khoảng này</h3><p className="mx-auto mt-2 max-w-lg text-sm leading-6 text-[#66727C]">{admin ? "Chọn Phân ca để tạo lịch mới hoặc đổi khoảng ngày đang xem." : "Lịch làm việc mới sẽ xuất hiện sau khi quản trị viên phân công."}</p></div>;
}

function DateRangePresetFilter({
  value,
  onChange,
}: {
  value: DateRangePreset;
  onChange: (value: Exclude<DateRangePreset, "CUSTOM">) => void;
}) {
  const presets: Array<[Exclude<DateRangePreset, "CUSTOM">, string]> = [
    ["DAY", "Hôm nay"],
    ["WEEK", "Tuần này"],
    ["MONTH", "Tháng này"],
  ];

  return (
    <div className="flex flex-wrap items-center gap-1 rounded-lg border border-white/15 bg-white/10 p-1" role="group" aria-label="Khoảng thời gian nhanh">
      {presets.map(([preset, label]) => (
        <button
          key={preset}
          type="button"
          aria-pressed={value === preset}
          onClick={() => onChange(preset)}
          className={`min-h-11 rounded-md px-3 text-[11px] font-bold transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#D8C398] ${
            value === preset
              ? "bg-white text-[#0F2A43] shadow-sm"
              : "text-white/75 hover:bg-white/10 hover:text-white"
          }`}
        >
          {label}
        </button>
      ))}
      {value === "CUSTOM" && (
        <span className="inline-flex min-h-9 items-center rounded-md bg-[#B8944F] px-3 text-[11px] font-bold text-[#0F2A43]">
          Tùy chọn
        </span>
      )}
    </div>
  );
}

export default function WorkSchedulesPage() {
  const { role, isAdmin, isStaff } = useDashboardRole();
  const [templates, setTemplates] = useState<WorkShiftTemplate[]>([]);
  const [employees, setEmployees] = useState<WorkScheduleEmployee[]>([]);
  const [schedules, setSchedules] = useState<WorkSchedule[]>([]);
  const [currentSchedule, setCurrentSchedule] = useState<WorkSchedule | null>(null);
  const [from, setFrom] = useState(() => dateRangeForPreset("MONTH").from);
  const [to, setTo] = useState(() => dateRangeForPreset("MONTH").to);
  const [rangePreset, setRangePreset] = useState<DateRangePreset>("MONTH");
  const [employeeFilter, setEmployeeFilter] = useState(0);
  const [statusFilter, setStatusFilter] = useState<WorkScheduleOperationalFilter>("ALL");
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [toast, setToast] = useState<ToastState | null>(null);
  const [now, setNow] = useState(() => new Date());
  const [scheduleModalOpen, setScheduleModalOpen] = useState(false);
  const [scheduleEditing, setScheduleEditing] = useState<WorkSchedule | null>(null);
  const [scheduleForm, setScheduleForm] = useState<WorkScheduleForm>(emptyScheduleForm);
  const [scheduleError, setScheduleError] = useState("");
  const [cancelTarget, setCancelTarget] = useState<WorkSchedule | null>(null);
  const [cancelReason, setCancelReason] = useState("");
  const [cancelError, setCancelError] = useState("");
  const [templatesModalOpen, setTemplatesModalOpen] = useState(false);
  const [templateEditing, setTemplateEditing] = useState<WorkShiftTemplate | null>(null);
  const [templateForm, setTemplateForm] = useState<WorkShiftTemplateForm>(emptyTemplateForm);
  const [templateError, setTemplateError] = useState("");
  const [checkoutTarget, setCheckoutTarget] = useState<WorkSchedule | null>(null);
  const [attendanceNote, setAttendanceNote] = useState("");
  const [attendanceError, setAttendanceError] = useState("");
  const [viewMode, setViewMode] = useState<WorkScheduleView>("calendar");
  const [calendarRefreshSignal, setCalendarRefreshSignal] = useState(0);
  const [calendarOverlayActive, setCalendarOverlayActive] = useState(false);
  const [dailyShiftAction, setDailyShiftAction] = useState<WorkDailyShiftAction>(null);
  const lastVisibilityRefreshAt = useRef(0);
  const latestLoadRequest = useRef(0);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 60_000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    const color = workShiftColorForStartTime(templateForm.startTime);
    const sortOrder = workShiftSortOrderForStartTime(templateForm.startTime);
    if (templateForm.color === color && templateForm.sortOrder === sortOrder) return;
    setTemplateForm((current) => ({ ...current, color, sortOrder }));
  }, [templateForm.color, templateForm.sortOrder, templateForm.startTime]);

  useEffect(() => {
    if (rangePreset === "CUSTOM") return;
    const expected = dateRangeForPreset(rangePreset);
    if (from !== expected.from || to !== expected.to) setRangePreset("CUSTOM");
  }, [from, rangePreset, to]);

  const loadData = useCallback(async (showLoading = false, force = false) => {
    if (!role) return;
    const requestId = ++latestLoadRequest.current;
    if (showLoading) setLoading(true);
    try {
      const params = new URLSearchParams({ from, to });
      if (employeeFilter) params.set("employeeId", String(employeeFilter));
      const shouldLoadSchedules = isStaff || viewMode !== "calendar";
      const [templateResult, scheduleResult, userResult, currentResult] = await Promise.all([
        cachedGet(`/api/work-schedules/templates?includeInactive=${isAdmin}`, { ttlMs: 60_000, force }),
        shouldLoadSchedules
          ? cachedGet(`/api/work-schedules/assignments?${params}`, { ttlMs: 5_000, force })
          : Promise.resolve(null),
        isAdmin
          ? cachedGet("/api/user/list?size=100", { ttlMs: 60_000 })
          : Promise.resolve(null),
        isStaff
          ? cachedGet("/api/work-schedules/current", { ttlMs: 3_000, force })
          : Promise.resolve(null),
      ]) as Array<{ data?: unknown } | null>;
      if (requestId !== latestLoadRequest.current) return;
      setTemplates(unwrapWorkScheduleApiData<WorkShiftTemplate[]>(templateResult));
      if (scheduleResult) {
        setSchedules(unwrapWorkScheduleApiData<WorkSchedule[]>(scheduleResult));
      }
      if (isAdmin && userResult) {
        const usersPayload = unwrapWorkScheduleApiData<{ users?: WorkScheduleEmployee[] }>(userResult);
        setEmployees((usersPayload?.users || []).filter((user) => user.type === "STAFF" && user.status === "ACTIVE"));
      }
      if (isStaff && currentResult) {
        setCurrentSchedule(unwrapWorkScheduleApiData<WorkSchedule | null>(currentResult));
      }
    } catch (error) {
      if (requestId !== latestLoadRequest.current) return;
      setToast({ type: "error", message: getApiErrorMessage(error, "Không thể tải lịch làm việc") });
    } finally {
      if (requestId === latestLoadRequest.current) setLoading(false);
    }
  }, [employeeFilter, from, isAdmin, isStaff, role, to, viewMode]);

  useEffect(() => {
    void loadData(true);
    return () => {
      latestLoadRequest.current += 1;
    };
  }, [loadData]);

  useEffect(() => {
    if (!role) return;
    const refreshWhenVisible = () => {
      const nowMs = Date.now();
      if (document.visibilityState !== "visible" || nowMs - lastVisibilityRefreshAt.current < 1_000) return;
      lastVisibilityRefreshAt.current = nowMs;
      void loadData();
    };
    window.addEventListener("focus", refreshWhenVisible);
    document.addEventListener("visibilitychange", refreshWhenVisible);
    return () => {
      window.removeEventListener("focus", refreshWhenVisible);
      document.removeEventListener("visibilitychange", refreshWhenVisible);
    };
  }, [loadData, role, viewMode]);

  const listSchedules = useMemo(() => schedules.filter((schedule) => {
    if (statusFilter === "ALL") return true;
    if (statusFilter === "ACTIVE") return schedule.sessionStatus === "ACTIVE";
    if (statusFilter === "ACTION_REQUIRED") {
      return schedule.status === "ABSENT"
        || schedule.late
        || (schedule.status === "SCHEDULED"
          && !schedule.sessionId
          && new Date(schedule.scheduledEndUtc).getTime() <= now.getTime());
    }
    return schedule.status === statusFilter;
  }), [now, schedules, statusFilter]);
  const activeTemplates = useMemo(() => templates.filter((template) => template.active), [templates]);
  const upcomingStaffSchedule = useMemo(() => schedules
    .filter((schedule) => schedule.status === "SCHEDULED"
      && !schedule.sessionId
      && new Date(schedule.scheduledEndUtc).getTime() > now.getTime())
    .sort((left, right) => new Date(left.scheduledStartUtc).getTime() - new Date(right.scheduledStartUtc).getTime())[0] || null, [now, schedules]);
  const attendanceHero = currentSchedule || upcomingStaffSchedule;
  const applyRangePreset = (preset: Exclude<DateRangePreset, "CUSTOM">) => {
    const range = dateRangeForPreset(preset);
    setRangePreset(preset);
    setFrom(range.from);
    setTo(range.to);
  };

  const openEditSchedule = (schedule: WorkSchedule, fromCalendar = false) => {
    setCalendarOverlayActive(fromCalendar);
    setScheduleEditing(schedule);
    setScheduleForm({ employeeId: schedule.employeeId, shiftTemplateId: schedule.shiftTemplateId, workDate: schedule.workDate, note: schedule.note || "" });
    setScheduleError("");
    setScheduleModalOpen(true);
  };

  const loadAssignmentForAction = async (
    assignmentId: number,
    action: "edit" | "cancel",
    fromCalendar = false,
  ) => {
    try {
      const response = await cachedGet(
        `/api/work-schedules/assignments/${assignmentId}`,
        { ttlMs: 0, force: true },
      );
      const schedule = unwrapWorkScheduleApiData<WorkSchedule>(response);
      if (!schedule) throw new Error("Không tìm thấy lịch làm việc");
      if (action === "edit") {
        openEditSchedule(schedule, fromCalendar);
        return;
      }
      setCalendarOverlayActive(fromCalendar);
      setCancelTarget(schedule);
      setCancelReason("");
      setCancelError("");
    } catch (error) {
      setToast({
        type: "error",
        message: getApiErrorMessage(error, "Không thể tải thông tin phân ca"),
      });
    }
  };

  const saveSchedule = async (event: FormEvent) => {
    event.preventDefault();
    if (!scheduleForm.employeeId || !scheduleForm.shiftTemplateId || !scheduleForm.workDate) return setScheduleError("Vui lòng chọn nhân viên, mẫu ca và ngày làm việc.");
    setSubmitting(true);
    setScheduleError("");
    const scope = `work-schedule:${scheduleEditing ? `update:${scheduleEditing.id}` : "create"}:${JSON.stringify(scheduleForm)}`;
    try {
      const config = { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } };
      if (scheduleEditing) await apiClient.put(`/api/work-schedules/assignments/${scheduleEditing.id}`, scheduleForm, config);
      else await apiClient.post("/api/work-schedules/assignments", scheduleForm, config);
      clearIdempotencyKey(scope);
      setScheduleModalOpen(false);
      setCalendarOverlayActive(false);
      setToast({ type: "success", message: scheduleEditing ? "Đã cập nhật lịch làm việc" : "Đã phân ca làm việc" });
      setCalendarRefreshSignal((current) => current + 1);
      await loadData(false, true);
    } catch (error) { setScheduleError(getApiErrorMessage(error, "Không thể lưu lịch làm việc")); }
    finally { setSubmitting(false); }
  };

  const cancelSchedule = async () => {
    if (!cancelTarget || !cancelReason.trim()) return setCancelError("Lý do hủy lịch là bắt buộc.");
    setSubmitting(true);
    setCancelError("");
    const scope = `work-schedule:cancel:${cancelTarget.id}:${cancelReason.trim()}`;
    try {
      await apiClient.post(`/api/work-schedules/assignments/${cancelTarget.id}/cancel`, { reason: cancelReason.trim() }, { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } });
      clearIdempotencyKey(scope);
      setCancelTarget(null);
      setCalendarOverlayActive(false);
      setCancelReason("");
      setCancelError("");
      setToast({ type: "success", message: "Đã hủy lịch làm việc" });
      setCalendarRefreshSignal((current) => current + 1);
      await loadData(false, true);
    } catch (error) { setCancelError(getApiErrorMessage(error, "Không thể hủy lịch làm việc")); }
    finally { setSubmitting(false); }
  };

  const openTemplateEditor = (template?: WorkShiftTemplate) => {
    setTemplateEditing(template || null);
    setTemplateForm(template ? { code: template.code, name: template.name, startTime: formatShiftTime(template.startTime), endTime: formatShiftTime(template.endTime), checkInEarlyMinutes: template.checkInEarlyMinutes, lateToleranceMinutes: template.lateToleranceMinutes, color: workShiftColorForStartTime(formatShiftTime(template.startTime)), sortOrder: workShiftSortOrderForStartTime(formatShiftTime(template.startTime)), active: template.active } : emptyTemplateForm());
    setTemplateError("");
  };

  const saveTemplate = async (event: FormEvent) => {
    event.preventDefault();
    if (!templateForm.code.trim() || !templateForm.name.trim()) return setTemplateError("Mã ca và tên ca là bắt buộc.");
    if (templateForm.startTime === templateForm.endTime) return setTemplateError("Giờ bắt đầu và giờ kết thúc phải khác nhau.");
    setSubmitting(true);
    setTemplateError("");
    const payload = {
      ...templateForm,
      color: workShiftColorForStartTime(templateForm.startTime),
      sortOrder: workShiftSortOrderForStartTime(templateForm.startTime),
    };
    const scope = `work-shift-template:${templateEditing ? `update:${templateEditing.id}` : "create"}:${JSON.stringify(payload)}`;
    try {
      const config = { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } };
      if (templateEditing) await apiClient.put(`/api/work-schedules/templates/${templateEditing.id}`, payload, config);
      else await apiClient.post("/api/work-schedules/templates", payload, config);
      clearIdempotencyKey(scope);
      setToast({ type: "success", message: templateEditing ? "Đã cập nhật mẫu ca" : "Đã tạo mẫu ca" });
      openTemplateEditor();
      await loadData(false, true);
    } catch (error) { setTemplateError(getApiErrorMessage(error, "Không thể lưu mẫu ca")); }
    finally { setSubmitting(false); }
  };

  const checkIn = async (schedule: WorkSchedule) => {
    setSubmitting(true);
    const scope = `work-shift:check-in:${schedule.id}`;
    try {
      await apiClient.post(`/api/work-schedules/assignments/${schedule.id}/check-in`, { note: null }, { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } });
      clearIdempotencyKey(scope);
      setToast({ type: "success", message: "Đã check-in; ca thu ngân đã mở tự động" });
      await loadData(false, true);
    } catch (error) { setToast({ type: "error", message: getApiErrorMessage(error, "Không thể check-in ca làm việc") }); }
    finally { setSubmitting(false); }
  };

  const checkOut = async () => {
    if (!checkoutTarget) return;
    const isEarly = now.getTime() < new Date(checkoutTarget.scheduledEndUtc).getTime();
    if (isEarly && !attendanceNote.trim()) return setAttendanceError("Checkout sớm phải nhập lý do để quản lý đối chiếu.");
    setSubmitting(true);
    setAttendanceError("");
    const scope = `work-shift:check-out:${checkoutTarget.id}:${attendanceNote.trim()}`;
    try {
      await apiClient.post(`/api/work-schedules/assignments/${checkoutTarget.id}/check-out`, { note: attendanceNote.trim() || null }, { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } });
      clearIdempotencyKey(scope);
      setCheckoutTarget(null);
      setAttendanceNote("");
      setToast({ type: "success", message: "Đã checkout; ca thu ngân đã đóng tự động" });
      await loadData(false, true);
    } catch (error) { setAttendanceError(getApiErrorMessage(error, "Không thể checkout ca làm việc")); }
    finally { setSubmitting(false); }
  };

  const openTemplateManager = () => {
    setTemplatesModalOpen(true);
    openTemplateEditor(templates.find((template) => template.active) || templates[0]);
  };

  const handleDailyShiftChanged = async (message: string) => {
    setToast({ type: "success", message });
    setCalendarRefreshSignal((current) => current + 1);
    await loadData(false, true);
  };

  if (!role || loading) return <div className="ops-page mx-auto w-full max-w-[1600px] space-y-4 p-5 md:p-8"><div className="h-32 animate-pulse rounded-xl bg-[#0F2A43]/8" /><div className="h-96 animate-pulse rounded-xl bg-[#0F2A43]/5" /></div>;

  return <div className="ops-page mx-auto w-full max-w-[1600px] space-y-6 p-5 md:p-8">
    <header className="ops-panel-strong overflow-hidden rounded-xl border"><div className="grid gap-6 px-5 py-6 md:grid-cols-[1fr_auto] md:items-end md:px-7"><div><p className="text-[10px] font-bold uppercase tracking-[0.22em] text-[#80632F]">{isAdmin ? "Quản lý nhân sự" : "Ca làm việc của tôi"}</p><h1 className="mt-2 font-serif text-3xl font-bold text-[#0F2A43] md:text-4xl">Lịch làm việc & điểm danh</h1><p className="mt-2 max-w-3xl text-sm leading-6 text-[#66727C]">{isAdmin ? "Chủ động mở nhiều ca theo từng ngày, tạo nhanh theo tuần/tháng rồi phân công nhân viên. Điểm danh và ca thu ngân vẫn được liên kết nguyên tử." : "Check-in để bắt đầu ca làm việc và mở ca thu ngân tự động. Check-out sẽ kết thúc cả hai trong cùng một thao tác."}</p></div>{isAdmin && <div className="flex flex-wrap gap-2"><button type="button" onClick={openTemplateManager} className="min-h-11 rounded-lg border border-[#0F2A43]/18 bg-white px-4 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F8F4EA]">Mẫu ca</button><button type="button" onClick={() => setDailyShiftAction({ kind: "bulk" })} className="min-h-11 rounded-lg border border-[#B8944F] bg-[#FFF9EA] px-4 text-sm font-bold text-[#0F2A43] transition hover:bg-[#F4E7C6]">Tạo nhanh</button><button type="button" onClick={() => setDailyShiftAction({ kind: "create", date: dateKey() })} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#173D5F]">+ Tạo ca</button></div>}</div></header>

    <section className="flex flex-col gap-3 rounded-xl border border-[#0F2A43]/10 bg-white p-3 shadow-sm sm:flex-row sm:items-center sm:justify-between">
      <div className="px-1">
        <p className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#80632F]">Lịch & chấm công</p>
        <p className="mt-0.5 text-xs text-[#66727C]">Phân ca, xem thống kê đi làm hoặc tra cứu chi tiết từng ca.</p>
      </div>
      <nav className="grid grid-cols-3 rounded-lg bg-[#F1F0EA] p-1 sm:flex" aria-label="Khu vực lịch làm việc">
        <button type="button" onClick={() => setViewMode("calendar")} aria-pressed={viewMode === "calendar"} className={`min-h-11 flex-1 rounded-lg px-2 text-xs font-bold leading-4 transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] sm:flex-none sm:px-5 sm:text-sm ${viewMode === "calendar" ? "bg-[#0F2A43] text-white shadow-sm" : "text-[#66727C] hover:bg-white hover:text-[#0F2A43]"}`}>Lịch làm việc</button>
        <button type="button" onClick={() => { if (rangePreset === "CUSTOM") applyRangePreset("MONTH"); setViewMode("statistics"); }} aria-pressed={viewMode === "statistics"} className={`min-h-11 flex-1 rounded-lg px-2 text-xs font-bold leading-4 transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] sm:flex-none sm:px-5 sm:text-sm ${viewMode === "statistics" ? "bg-[#0F2A43] text-white shadow-sm" : "text-[#66727C] hover:bg-white hover:text-[#0F2A43]"}`}>Thống kê chấm công</button>
        <button type="button" onClick={() => setViewMode("list")} aria-pressed={viewMode === "list"} className={`min-h-11 flex-1 rounded-lg px-2 text-xs font-bold leading-4 transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] sm:flex-none sm:px-5 sm:text-sm ${viewMode === "list" ? "bg-[#0F2A43] text-white shadow-sm" : "text-[#66727C] hover:bg-white hover:text-[#0F2A43]"}`}>{isAdmin ? "Quản lý ca" : "Ca của tôi"}</button>
      </nav>
    </section>

    {isStaff && viewMode !== "statistics" && <section className="ops-panel overflow-hidden rounded-xl border"><div className="ops-section-header px-5 py-4"><p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#D8C398]">Trạng thái hiện tại</p><h2 className="mt-1 text-lg font-bold text-white">{currentSchedule ? "Bạn đang trong ca làm việc" : attendanceHero ? "Ca làm việc tiếp theo" : "Chưa có ca được phân công"}</h2></div>{attendanceHero ? <div className="grid gap-5 p-5 lg:grid-cols-[1fr_auto] lg:items-center"><div className="flex min-w-0 gap-4"><span className="mt-1 h-12 w-2 shrink-0 rounded-full" style={{ backgroundColor: attendanceHero.shiftColor }} /><div><div className="flex flex-wrap items-center gap-2"><h3 className="font-serif text-2xl font-bold text-[#0F2A43]">{attendanceHero.shiftName}</h3><ScheduleStatusBadge schedule={attendanceHero} />{attendanceHero.late && <span className="rounded-full bg-orange-100 px-2.5 py-1 text-[10px] font-bold text-orange-800">Muộn {attendanceHero.lateMinutes} phút</span>}</div><p className="mt-2 text-sm font-semibold text-[#27445F]">{formatWorkDateTime(attendanceHero.scheduledStartUtc)} → {formatWorkDateTime(attendanceHero.scheduledEndUtc)}</p><p className="mt-2 text-xs leading-5 text-[#66727C]">{currentSchedule ? `Check-in lúc ${formatWorkDateTime(currentSchedule.actualCheckInUtc)} · Ca thu ngân #${currentSchedule.cashierShiftId || "đang đồng bộ"}` : `Có thể check-in sớm ${attendanceHero.checkInEarlyMinutes} phút; sau ngưỡng ${attendanceHero.lateToleranceMinutes} phút sẽ ghi nhận đi muộn.`}</p></div></div><div className="flex flex-wrap gap-2 lg:justify-end">{currentSchedule ? <button type="button" disabled={submitting} onClick={() => { setCheckoutTarget(currentSchedule); setAttendanceNote(""); setAttendanceError(""); }} className="min-h-11 rounded-lg bg-[#B8944F] px-5 text-sm font-bold text-[#0F2A43] transition hover:bg-[#C7A865] disabled:opacity-60">{submitting ? "Đang xử lý..." : "Check-out cuối ca"}</button> : <button type="button" disabled={submitting || !isCheckInAvailable(attendanceHero, now)} onClick={() => void checkIn(attendanceHero)} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#173D5F] disabled:cursor-not-allowed disabled:opacity-45">{submitting ? "Đang xử lý..." : isCheckInAvailable(attendanceHero, now) ? "Check-in bắt đầu ca" : "Chưa đến giờ check-in"}</button>}</div></div> : <EmptySchedule admin={false} />}</section>}

    {viewMode === "calendar" ? (
      <WorkforceMonthCalendar
        isAdmin={isAdmin}
        isStaff={isStaff}
        employees={employees}
        refreshSignal={calendarRefreshSignal}
        editorOverlayOpen={Boolean(dailyShiftAction) || (calendarOverlayActive && (scheduleModalOpen || Boolean(cancelTarget)))}
        onScheduleChanged={() => loadData(false, true)}
        onEditAssignment={(assignmentId) => loadAssignmentForAction(assignmentId, "edit", true)}
        onCancelAssignment={(assignmentId) => loadAssignmentForAction(assignmentId, "cancel", true)}
        onCreateDailyShift={(date, usedTemplateIds) => setDailyShiftAction({ kind: "create", date, usedTemplateIds })}
        onEditDailyShift={(date, slot) => setDailyShiftAction({ kind: "edit", date, slot })}
        onCancelDailyShift={(date, slot) => setDailyShiftAction({ kind: "cancel", date, slot })}
        onRestoreDailyShift={(date, slot) => setDailyShiftAction({ kind: "restore", date, slot })}
        onDeleteDailyShift={(date, slot) => setDailyShiftAction({ kind: "delete", date, slot })}
      />
    ) : viewMode === "statistics" ? (
      <>
        <section className="flex flex-col gap-4 rounded-2xl bg-[#0F2A43] px-5 py-5 text-white shadow-[0_14px_36px_rgba(15,42,67,0.14)] lg:flex-row lg:items-end lg:justify-between" aria-labelledby="attendance-report-title">
          <div>
            <p className="text-[10px] font-black uppercase tracking-[0.18em] text-[#D8C398]">Báo cáo nhân sự</p>
            <h2 id="attendance-report-title" className="mt-1 font-serif text-2xl font-bold">Thống kê chấm công</h2>
            <p className="mt-2 max-w-2xl text-xs leading-5 text-white/65">Số liệu tự động lấy từ check-in/check-out thực tế; lịch đã hủy không được tính vào tổng ca.</p>
          </div>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
            {isAdmin && (
              <label className="text-[10px] font-bold uppercase tracking-wide text-white/65">
                Nhân viên
                <select value={employeeFilter} onChange={(event) => setEmployeeFilter(Number(event.target.value))} className="mt-1 block min-h-10 w-full min-w-48 rounded-lg border border-white/15 bg-[#173D5F] px-3 text-xs text-white outline-none focus:border-[#B8944F]">
                  <option value={0}>Tất cả nhân viên</option>
                  {employees.map((employee) => <option key={employee.id} value={employee.id}>{employee.fullName}</option>)}
                </select>
              </label>
            )}
            <DateRangePresetFilter value={rangePreset} onChange={applyRangePreset} />
          </div>
        </section>
        <WorkAttendanceStatistics
          schedules={schedules}
          isAdmin={isAdmin}
          periodLabel={from === to ? formatWorkDate(from) : `${formatWorkDate(from)} đến ${formatWorkDate(to)}`}
          now={now}
        />
      </>
    ) : (
      <>
        <section className="overflow-hidden rounded-2xl bg-[#0F2A43] text-white shadow-[0_14px_36px_rgba(15,42,67,0.14)]" aria-labelledby="schedule-range-title">
          <div className="flex flex-col gap-4 px-5 py-5 lg:flex-row lg:items-end lg:justify-between">
            <div>
              <p className="text-[10px] font-black uppercase tracking-[0.18em] text-[#D8C398]">{isAdmin ? "Tra cứu & xử lý" : "Lịch cá nhân"}</p>
              <h2 id="schedule-range-title" className="mt-1 font-serif text-2xl font-bold">{isAdmin ? "Quản lý ca làm việc" : "Lịch sử và ca sắp tới"}</h2>
              <p className="mt-2 max-w-2xl text-xs leading-5 text-white/65">{isAdmin ? "Tra cứu theo thời gian, nhân viên và trạng thái để sửa lịch, đổi người hoặc xử lý ca cần lưu ý." : "Xem ca sắp tới, lịch sử điểm danh và thao tác với ca đang hoạt động."}</p>
            </div>
            <DateRangePresetFilter value={rangePreset} onChange={applyRangePreset} />
          </div>
          <div className="grid gap-3 border-t border-white/10 bg-white/5 px-5 py-4 sm:grid-cols-2 xl:grid-cols-4">
            <label className="text-[10px] font-bold uppercase tracking-wide text-white/65">
              Từ ngày
              <input type="date" value={from} onChange={(event) => setFrom(event.target.value)} className="mt-1 min-h-11 w-full rounded-lg border border-white/15 bg-white/10 px-3 text-xs font-semibold text-white outline-none transition hover:border-white/30 focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20" />
            </label>
            <label className="text-[10px] font-bold uppercase tracking-wide text-white/65">
              Đến ngày
              <input type="date" value={to} min={from} onChange={(event) => setTo(event.target.value)} className="mt-1 min-h-11 w-full rounded-lg border border-white/15 bg-white/10 px-3 text-xs font-semibold text-white outline-none transition hover:border-white/30 focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20" />
            </label>
            {isAdmin && (
              <label className="text-[10px] font-bold uppercase tracking-wide text-white/65">
                Nhân viên
                <select value={employeeFilter} onChange={(event) => setEmployeeFilter(Number(event.target.value))} className="mt-1 min-h-11 w-full rounded-lg border border-white/15 bg-[#173D5F] px-3 text-xs font-semibold text-white outline-none transition hover:border-white/30 focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20">
                  <option value={0}>Tất cả nhân viên</option>
                  {employees.map((employee) => <option key={employee.id} value={employee.id}>{employee.fullName}</option>)}
                </select>
              </label>
            )}
            <label className="text-[10px] font-bold uppercase tracking-wide text-white/65">
              Trạng thái
              <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value as WorkScheduleOperationalFilter)} className="mt-1 min-h-11 w-full rounded-lg border border-white/15 bg-[#173D5F] px-3 text-xs font-semibold text-white outline-none transition hover:border-white/30 focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20">
                <option value="ALL">Tất cả trạng thái</option>
                <option value="ACTION_REQUIRED">Cần xử lý</option>
                <option value="ACTIVE">Đang làm việc</option>
                <option value="SCHEDULED">Đã phân công</option>
                <option value="FULFILLED">Đã hoàn thành</option>
                <option value="ABSENT">Vắng mặt</option>
                <option value="CANCELLED">Đã hủy</option>
              </select>
            </label>
          </div>
        </section>
        <WorkScheduleListView
          schedules={listSchedules}
          isAdmin={isAdmin}
          isStaff={isStaff}
          periodLabel={from === to ? formatWorkDate(from) : `${formatWorkDate(from)} đến ${formatWorkDate(to)}`}
          now={now}
          onEdit={openEditSchedule}
          onCancel={(schedule) => {
            setCalendarOverlayActive(false);
            setCancelTarget(schedule);
            setCancelReason("");
            setCancelError("");
          }}
          onCheckout={(schedule) => {
            setCheckoutTarget(schedule);
            setAttendanceNote("");
            setAttendanceError("");
          }}
        />
      </>
    )}

    <ViewportModal open={scheduleModalOpen} onClose={() => { setScheduleModalOpen(false); setCalendarOverlayActive(false); }} labelledBy="schedule-form-title" busy={submitting} panelClassName="max-w-2xl" backdropClassName="bg-[#091E30]/48 backdrop-blur-[2px]" zIndexClassName="z-[115]"><form onSubmit={saveSchedule} className="flex min-h-0 flex-1 flex-col"><header className="border-b px-5 py-4"><p className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#80632F]">Lịch làm việc</p><h2 id="schedule-form-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">{scheduleEditing ? "Điều chỉnh lịch" : "Phân ca cho nhân viên"}</h2></header><div className="lux-scrollbar grid min-h-0 flex-1 gap-4 overflow-y-auto p-5 sm:grid-cols-2"><label><span className={labelClass}>Nhân viên *</span><select data-modal-autofocus value={scheduleForm.employeeId} onChange={(event) => setScheduleForm((current) => ({ ...current, employeeId: Number(event.target.value) }))} className={inputClass}><option value={0}>Chọn nhân viên</option>{employees.map((employee) => <option key={employee.id} value={employee.id}>{employee.fullName} · {employee.username}</option>)}</select></label><label><span className={labelClass}>Mẫu ca *</span><select value={scheduleForm.shiftTemplateId} onChange={(event) => setScheduleForm((current) => ({ ...current, shiftTemplateId: Number(event.target.value) }))} className={inputClass}><option value={0}>Chọn mẫu ca</option>{activeTemplates.map((template) => <option key={template.id} value={template.id}>{template.name} · {formatShiftTime(template.startTime)}–{formatShiftTime(template.endTime)}</option>)}</select></label><label><span className={labelClass}>Ngày làm việc *</span><input type="date" value={scheduleForm.workDate} onChange={(event) => setScheduleForm((current) => ({ ...current, workDate: event.target.value }))} className={inputClass} /></label><label className="sm:col-span-2"><span className={labelClass}>Ghi chú</span><textarea value={scheduleForm.note} maxLength={1000} onChange={(event) => setScheduleForm((current) => ({ ...current, note: event.target.value }))} rows={3} className={`${inputClass} resize-y`} /></label>{scheduleError && <p role="alert" className="sm:col-span-2 rounded-lg border border-rose-200 bg-rose-50 p-3 text-xs font-semibold text-rose-700">{scheduleError}</p>}</div><footer className="flex justify-end gap-2 border-t px-5 py-4"><button type="button" disabled={submitting} onClick={() => { setScheduleModalOpen(false); setCalendarOverlayActive(false); }} className="min-h-11 rounded-lg border px-4 text-sm font-bold">{calendarOverlayActive ? "Quay lại chi tiết ca" : "Đóng"}</button><button type="submit" disabled={submitting} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white disabled:opacity-60">{submitting ? "Đang lưu..." : "Lưu lịch"}</button></footer></form></ViewportModal>

    <WorkShiftTemplateManagerModal
      open={templatesModalOpen}
      busy={submitting}
      templates={templates}
      editing={templateEditing}
      form={templateForm}
      error={templateError}
      onClose={() => setTemplatesModalOpen(false)}
      onNew={() => openTemplateEditor()}
      onSelect={openTemplateEditor}
      setForm={setTemplateForm}
      onSubmit={saveTemplate}
    />

    <ViewportModal open={Boolean(cancelTarget)} onClose={() => { setCancelTarget(null); setCancelError(""); setCalendarOverlayActive(false); }} labelledBy="cancel-schedule-title" busy={submitting} panelClassName="max-w-lg" backdropClassName="bg-[#091E30]/48 backdrop-blur-[2px]" zIndexClassName="z-[115]"><div className="flex min-h-0 flex-1 flex-col"><header className="border-b px-5 py-4"><p className="text-[10px] font-bold uppercase tracking-[0.16em] text-rose-700">Thay đổi lịch</p><h2 id="cancel-schedule-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">Hủy lịch làm việc</h2></header><div className="p-5"><p className="text-sm leading-6 text-[#66727C]">{cancelTarget && `Hủy ${cancelTarget.shiftName} của ${cancelTarget.employeeName} ngày ${formatWorkDate(cancelTarget.workDate)}.`}</p><label className="mt-4 block"><span className={labelClass}>Lý do hủy *</span><textarea data-modal-autofocus value={cancelReason} maxLength={500} onChange={(event) => { setCancelReason(event.target.value); if (cancelError) setCancelError(""); }} rows={4} className={`${inputClass} resize-y`} /></label>{cancelError && <p role="alert" className="mt-3 text-xs font-semibold text-rose-700">{cancelError}</p>}</div><footer className="flex justify-end gap-2 border-t px-5 py-4"><button type="button" onClick={() => { setCancelTarget(null); setCancelError(""); setCalendarOverlayActive(false); }} className="min-h-11 rounded-lg border px-4 text-sm font-bold">{calendarOverlayActive ? "Quay lại chi tiết ca" : "Quay lại"}</button><button type="button" disabled={submitting} onClick={() => void cancelSchedule()} className="min-h-11 rounded-lg bg-rose-700 px-5 text-sm font-bold text-white disabled:opacity-60">{submitting ? "Đang hủy..." : "Xác nhận hủy"}</button></footer></div></ViewportModal>

    <ViewportModal open={Boolean(checkoutTarget)} onClose={() => setCheckoutTarget(null)} labelledBy="work-checkout-title" busy={submitting} panelClassName="max-w-lg"><div className="flex min-h-0 flex-1 flex-col"><header className="border-b px-5 py-4"><p className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#80632F]">Kết thúc phiên làm việc</p><h2 id="work-checkout-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">Check-out cuối ca</h2></header><div className="p-5"><p className="rounded-lg bg-[#0F2A43]/5 p-3 text-sm leading-6 text-[#27445F]">Ca thu ngân liên kết sẽ được đóng tự động trong cùng giao dịch. Nếu thao tác lỗi, cả hai trạng thái đều được giữ nguyên.</p><label className="mt-4 block"><span className={labelClass}>{checkoutTarget && now.getTime() < new Date(checkoutTarget.scheduledEndUtc).getTime() ? "Lý do checkout sớm *" : "Ghi chú bàn giao"}</span><textarea data-modal-autofocus value={attendanceNote} maxLength={1000} onChange={(event) => setAttendanceNote(event.target.value)} rows={4} className={`${inputClass} resize-y`} /></label>{attendanceError && <p role="alert" className="mt-3 text-xs font-semibold text-rose-700">{attendanceError}</p>}</div><footer className="flex justify-end gap-2 border-t px-5 py-4"><button type="button" onClick={() => setCheckoutTarget(null)} className="min-h-11 rounded-lg border px-4 text-sm font-bold">Chưa kết thúc</button><button type="button" disabled={submitting} onClick={() => void checkOut()} className="min-h-11 rounded-lg bg-[#B8944F] px-5 text-sm font-bold text-[#0F2A43] disabled:opacity-60">{submitting ? "Đang xử lý..." : "Xác nhận check-out"}</button></footer></div></ViewportModal>

    <WorkDailyShiftModals
      action={dailyShiftAction}
      templates={templates}
      onClose={() => setDailyShiftAction(null)}
      onChanged={handleDailyShiftChanged}
    />

    {toast && <Toast {...toast} onClose={() => setToast(null)} />}
  </div>;
}
