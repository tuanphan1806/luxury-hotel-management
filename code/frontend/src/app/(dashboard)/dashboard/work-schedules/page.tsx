"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { apiClient, cachedGet, getApiErrorMessage } from "@/lib/api";
import {
  clearIdempotencyKey,
  getOrCreateIdempotencyKey,
} from "@/lib/idempotency";
import Toast from "@/components/UI/Toast";
import ViewportModal from "@/components/UI/ViewportModal";
import WorkforceMonthCalendar from "@/components/dashboard/WorkforceMonthCalendar";
import { useDashboardRole } from "@/hooks/use-dashboard-role";
import {
  formatShiftTime,
  formatWorkDateTime,
  groupWorkSchedulesByDate,
  isCheckInAvailable,
  shiftWorkDate,
  unwrapWorkScheduleApiData,
  workScheduleDisplayStatus,
  workScheduleTone,
  type WorkSchedule,
  type WorkScheduleEmployee,
  type WorkScheduleForm,
  type WorkScheduleStatus,
  type WorkShiftTemplate,
  type WorkShiftTemplateForm,
} from "@/lib/work-schedules";

type ToastState = { message: string; type: "success" | "error" | "info" };

const HOTEL_TIME_ZONE = "Asia/Ho_Chi_Minh";
const inputClass = "ops-control min-h-11 w-full rounded-lg border px-3 py-2.5 text-sm font-semibold text-[#0F2A43] outline-none transition hover:border-[#0F2A43]/30 focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20 disabled:cursor-not-allowed disabled:opacity-60";
const labelClass = "mb-2 block text-xs font-bold text-[#66727C]";

const dateKey = (date = new Date()) => new Intl.DateTimeFormat("en-CA", {
  timeZone: HOTEL_TIME_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
}).format(date);

const shiftDate = (days: number) => shiftWorkDate(dateKey(), days);

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
  endTime: "15:00",
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

export default function WorkSchedulesPage() {
  const { role, isAdmin, isStaff } = useDashboardRole();
  const [templates, setTemplates] = useState<WorkShiftTemplate[]>([]);
  const [employees, setEmployees] = useState<WorkScheduleEmployee[]>([]);
  const [schedules, setSchedules] = useState<WorkSchedule[]>([]);
  const [currentSchedule, setCurrentSchedule] = useState<WorkSchedule | null>(null);
  const [from, setFrom] = useState(shiftDate(-7));
  const [to, setTo] = useState(shiftDate(21));
  const [employeeFilter, setEmployeeFilter] = useState(0);
  const [statusFilter, setStatusFilter] = useState<"ALL" | WorkScheduleStatus>("ALL");
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
  const [templatesModalOpen, setTemplatesModalOpen] = useState(false);
  const [templateEditing, setTemplateEditing] = useState<WorkShiftTemplate | null>(null);
  const [templateForm, setTemplateForm] = useState<WorkShiftTemplateForm>(emptyTemplateForm);
  const [templateError, setTemplateError] = useState("");
  const [checkoutTarget, setCheckoutTarget] = useState<WorkSchedule | null>(null);
  const [attendanceNote, setAttendanceNote] = useState("");
  const [attendanceError, setAttendanceError] = useState("");
  const [viewMode, setViewMode] = useState<"calendar" | "list">("calendar");
  const lastVisibilityRefreshAt = useRef(0);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 60_000);
    return () => window.clearInterval(timer);
  }, []);

  const loadData = useCallback(async (showLoading = false) => {
    if (!role) return;
    if (showLoading) setLoading(true);
    try {
      const params = new URLSearchParams({ from, to });
      if (employeeFilter) params.set("employeeId", String(employeeFilter));
      if (statusFilter !== "ALL") params.set("status", statusFilter);
      const requests: Promise<unknown>[] = [
        cachedGet(`/api/work-schedules/templates?includeInactive=${isAdmin}`, { ttlMs: 60_000 }),
        cachedGet(`/api/work-schedules/assignments?${params}`, { ttlMs: 5_000, force: true }),
      ];
      if (isAdmin) requests.push(cachedGet("/api/user/list?size=100", { ttlMs: 60_000 }));
      if (isStaff) requests.push(cachedGet("/api/work-schedules/current", { ttlMs: 3_000, force: true }));
      const results = await Promise.all(requests) as Array<{ data?: unknown }>;
      setTemplates(unwrapWorkScheduleApiData<WorkShiftTemplate[]>(results[0]));
      setSchedules(unwrapWorkScheduleApiData<WorkSchedule[]>(results[1]));
      let cursor = 2;
      if (isAdmin) {
        const usersPayload = unwrapWorkScheduleApiData<{ users?: WorkScheduleEmployee[] }>(results[cursor++]);
        setEmployees((usersPayload?.users || []).filter((user) => user.type === "STAFF" && user.status === "ACTIVE"));
      }
      if (isStaff) setCurrentSchedule(unwrapWorkScheduleApiData<WorkSchedule | null>(results[cursor]));
    } catch (error) {
      setToast({ type: "error", message: getApiErrorMessage(error, "Không thể tải lịch làm việc") });
    } finally {
      setLoading(false);
    }
  }, [employeeFilter, from, isAdmin, isStaff, role, statusFilter, to]);

  useEffect(() => { void loadData(true); }, [loadData]);

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
  }, [loadData, role]);

  const groupedSchedules = useMemo(() => groupWorkSchedulesByDate(schedules), [schedules]);
  const sortedDates = useMemo(() => Object.keys(groupedSchedules).sort(), [groupedSchedules]);
  const activeTemplates = useMemo(() => templates.filter((template) => template.active), [templates]);
  const upcomingStaffSchedule = useMemo(() => schedules
    .filter((schedule) => schedule.status === "SCHEDULED"
      && !schedule.sessionId
      && new Date(schedule.scheduledEndUtc).getTime() > now.getTime())
    .sort((left, right) => new Date(left.scheduledStartUtc).getTime() - new Date(right.scheduledStartUtc).getTime())[0] || null, [now, schedules]);
  const attendanceHero = currentSchedule || upcomingStaffSchedule;
  const summary = useMemo(() => ({ scheduled: schedules.filter((item) => item.status === "SCHEDULED").length, active: schedules.filter((item) => item.sessionStatus === "ACTIVE").length, late: schedules.filter((item) => item.late).length, absent: schedules.filter((item) => item.status === "ABSENT").length }), [schedules]);

  const openCreateSchedule = () => {
    setScheduleEditing(null);
    setScheduleForm({ ...emptyScheduleForm(), employeeId: employees[0]?.id || 0, shiftTemplateId: activeTemplates[0]?.id || 0 });
    setScheduleError("");
    setScheduleModalOpen(true);
  };

  const openEditSchedule = (schedule: WorkSchedule) => {
    setScheduleEditing(schedule);
    setScheduleForm({ employeeId: schedule.employeeId, shiftTemplateId: schedule.shiftTemplateId, workDate: schedule.workDate, note: schedule.note || "" });
    setScheduleError("");
    setScheduleModalOpen(true);
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
      setToast({ type: "success", message: scheduleEditing ? "Đã cập nhật lịch làm việc" : "Đã phân ca làm việc" });
      await loadData();
    } catch (error) { setScheduleError(getApiErrorMessage(error, "Không thể lưu lịch làm việc")); }
    finally { setSubmitting(false); }
  };

  const cancelSchedule = async () => {
    if (!cancelTarget || !cancelReason.trim()) return setAttendanceError("Lý do hủy lịch là bắt buộc.");
    setSubmitting(true);
    setAttendanceError("");
    const scope = `work-schedule:cancel:${cancelTarget.id}:${cancelReason.trim()}`;
    try {
      await apiClient.post(`/api/work-schedules/assignments/${cancelTarget.id}/cancel`, { reason: cancelReason.trim() }, { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } });
      clearIdempotencyKey(scope);
      setCancelTarget(null);
      setCancelReason("");
      setToast({ type: "success", message: "Đã hủy lịch làm việc" });
      await loadData();
    } catch (error) { setAttendanceError(getApiErrorMessage(error, "Không thể hủy lịch làm việc")); }
    finally { setSubmitting(false); }
  };

  const openTemplateEditor = (template?: WorkShiftTemplate) => {
    setTemplateEditing(template || null);
    setTemplateForm(template ? { code: template.code, name: template.name, startTime: formatShiftTime(template.startTime), endTime: formatShiftTime(template.endTime), checkInEarlyMinutes: template.checkInEarlyMinutes, lateToleranceMinutes: template.lateToleranceMinutes, color: template.color, sortOrder: template.sortOrder, active: template.active } : emptyTemplateForm());
    setTemplateError("");
  };

  const saveTemplate = async (event: FormEvent) => {
    event.preventDefault();
    if (!templateForm.code.trim() || !templateForm.name.trim()) return setTemplateError("Mã ca và tên ca là bắt buộc.");
    if (templateForm.startTime === templateForm.endTime) return setTemplateError("Giờ bắt đầu và giờ kết thúc phải khác nhau.");
    setSubmitting(true);
    setTemplateError("");
    const scope = `work-shift-template:${templateEditing ? `update:${templateEditing.id}` : "create"}:${JSON.stringify(templateForm)}`;
    try {
      const config = { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } };
      if (templateEditing) await apiClient.put(`/api/work-schedules/templates/${templateEditing.id}`, templateForm, config);
      else await apiClient.post("/api/work-schedules/templates", templateForm, config);
      clearIdempotencyKey(scope);
      setToast({ type: "success", message: templateEditing ? "Đã cập nhật mẫu ca" : "Đã tạo mẫu ca" });
      openTemplateEditor();
      await loadData();
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
      await loadData();
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
      await loadData();
    } catch (error) { setAttendanceError(getApiErrorMessage(error, "Không thể checkout ca làm việc")); }
    finally { setSubmitting(false); }
  };

  if (!role || loading) return <div className="ops-page mx-auto w-full max-w-[1600px] space-y-4 p-5 md:p-8"><div className="h-32 animate-pulse rounded-xl bg-[#0F2A43]/8" /><div className="h-96 animate-pulse rounded-xl bg-[#0F2A43]/5" /></div>;

  return <div className="ops-page mx-auto w-full max-w-[1600px] space-y-6 p-5 md:p-8">
    <header className="ops-panel-strong overflow-hidden rounded-xl border"><div className="grid gap-6 px-5 py-6 md:grid-cols-[1fr_auto] md:items-end md:px-7"><div><p className="text-[10px] font-bold uppercase tracking-[0.22em] text-[#80632F]">{isAdmin ? "Quản lý nhân sự" : "Ca làm việc của tôi"}</p><h1 className="mt-2 font-serif text-3xl font-bold text-[#0F2A43] md:text-4xl">Lịch làm việc & điểm danh</h1><p className="mt-2 max-w-3xl text-sm leading-6 text-[#66727C]">{isAdmin ? "Phân ca, theo dõi đi muộn/vắng mặt và lịch sử làm việc. Dữ liệu điểm danh và ca thu ngân luôn được liên kết." : "Check-in để bắt đầu ca làm việc và mở ca thu ngân tự động. Check-out sẽ kết thúc cả hai trong cùng một thao tác."}</p></div>{isAdmin && <div className="flex flex-wrap gap-2"><button type="button" onClick={() => { setTemplatesModalOpen(true); openTemplateEditor(); }} className="min-h-11 rounded-lg border border-[#0F2A43]/18 bg-white px-4 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F8F4EA]">Mẫu ca</button><button type="button" onClick={openCreateSchedule} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#173D5F]">+ Phân ca</button></div>}</div></header>

    <nav className="flex w-fit rounded-xl border border-[#0F2A43]/10 bg-white p-1 shadow-sm" aria-label="Kiểu hiển thị lịch">
      <button type="button" onClick={() => setViewMode("calendar")} aria-pressed={viewMode === "calendar"} className={`min-h-11 rounded-lg px-4 text-sm font-bold transition ${viewMode === "calendar" ? "bg-[#0F2A43] text-white shadow-sm" : "text-[#66727C] hover:bg-[#F4EFE5] hover:text-[#0F2A43]"}`}>Lịch tháng</button>
      <button type="button" onClick={() => setViewMode("list")} aria-pressed={viewMode === "list"} className={`min-h-11 rounded-lg px-4 text-sm font-bold transition ${viewMode === "list" ? "bg-[#0F2A43] text-white shadow-sm" : "text-[#66727C] hover:bg-[#F4EFE5] hover:text-[#0F2A43]"}`}>Danh sách</button>
    </nav>

    {viewMode === "list" && isAdmin && <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4" aria-label="Tóm tắt lịch làm việc">{[["Đã phân công", summary.scheduled, "text-amber-800 bg-amber-50"], ["Đang làm việc", summary.active, "text-emerald-800 bg-emerald-50"], ["Đi muộn", summary.late, "text-orange-800 bg-orange-50"], ["Vắng mặt", summary.absent, "text-rose-800 bg-rose-50"]].map(([label, value, tone]) => <article key={String(label)} className={`rounded-xl border border-[#0F2A43]/8 p-4 ${tone}`}><p className="text-[10px] font-bold uppercase tracking-[0.14em]">{label}</p><p className="mt-2 text-2xl font-bold tabular-nums">{value}</p></article>)}</section>}

    {isStaff && <section className="ops-panel overflow-hidden rounded-xl border"><div className="ops-section-header px-5 py-4"><p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#D8C398]">Trạng thái hiện tại</p><h2 className="mt-1 text-lg font-bold text-white">{currentSchedule ? "Bạn đang trong ca làm việc" : attendanceHero ? "Ca làm việc tiếp theo" : "Chưa có ca được phân công"}</h2></div>{attendanceHero ? <div className="grid gap-5 p-5 lg:grid-cols-[1fr_auto] lg:items-center"><div className="flex min-w-0 gap-4"><span className="mt-1 h-12 w-2 shrink-0 rounded-full" style={{ backgroundColor: attendanceHero.shiftColor }} /><div><div className="flex flex-wrap items-center gap-2"><h3 className="font-serif text-2xl font-bold text-[#0F2A43]">{attendanceHero.shiftName}</h3><ScheduleStatusBadge schedule={attendanceHero} />{attendanceHero.late && <span className="rounded-full bg-orange-100 px-2.5 py-1 text-[10px] font-bold text-orange-800">Muộn {attendanceHero.lateMinutes} phút</span>}</div><p className="mt-2 text-sm font-semibold text-[#27445F]">{formatWorkDateTime(attendanceHero.scheduledStartUtc)} → {formatWorkDateTime(attendanceHero.scheduledEndUtc)}</p><p className="mt-2 text-xs leading-5 text-[#66727C]">{currentSchedule ? `Check-in lúc ${formatWorkDateTime(currentSchedule.actualCheckInUtc)} · Ca thu ngân #${currentSchedule.cashierShiftId || "đang đồng bộ"}` : `Có thể check-in sớm ${attendanceHero.checkInEarlyMinutes} phút; sau ngưỡng ${attendanceHero.lateToleranceMinutes} phút sẽ ghi nhận đi muộn.`}</p></div></div><div className="flex flex-wrap gap-2 lg:justify-end">{currentSchedule ? <button type="button" disabled={submitting} onClick={() => { setCheckoutTarget(currentSchedule); setAttendanceNote(""); setAttendanceError(""); }} className="min-h-11 rounded-lg bg-[#B8944F] px-5 text-sm font-bold text-[#0F2A43] transition hover:bg-[#C7A865] disabled:opacity-60">{submitting ? "Đang xử lý..." : "Check-out cuối ca"}</button> : <button type="button" disabled={submitting || !isCheckInAvailable(attendanceHero, now)} onClick={() => void checkIn(attendanceHero)} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#173D5F] disabled:cursor-not-allowed disabled:opacity-45">{submitting ? "Đang xử lý..." : isCheckInAvailable(attendanceHero, now) ? "Check-in bắt đầu ca" : "Chưa đến giờ check-in"}</button>}</div></div> : <EmptySchedule admin={false} />}</section>}

    {viewMode === "calendar" ? (
      <WorkforceMonthCalendar
        isAdmin={isAdmin}
        isStaff={isStaff}
        employees={employees}
        onScheduleChanged={() => loadData()}
      />
    ) : (
      <section className="ops-panel overflow-hidden rounded-xl border"><div className="ops-section-header flex flex-col gap-4 px-5 py-4 xl:flex-row xl:items-end xl:justify-between"><div><h2 className="text-lg font-bold text-white">{isAdmin ? "Lịch phân công" : "Lịch sử và ca sắp tới"}</h2><p className="mt-1 text-xs text-white/65">Mỗi lịch chỉ có một phiên làm việc thực tế; dữ liệu cũ được giữ nguyên khi sửa mẫu ca.</p></div><div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-4"><label className="text-[10px] font-bold uppercase tracking-wide text-white/65">Từ ngày<input type="date" value={from} onChange={(event) => setFrom(event.target.value)} className="mt-1 min-h-10 w-full rounded-lg border border-white/15 bg-white/10 px-3 text-xs text-white outline-none focus:border-[#B8944F]" /></label><label className="text-[10px] font-bold uppercase tracking-wide text-white/65">Đến ngày<input type="date" value={to} min={from} onChange={(event) => setTo(event.target.value)} className="mt-1 min-h-10 w-full rounded-lg border border-white/15 bg-white/10 px-3 text-xs text-white outline-none focus:border-[#B8944F]" /></label>{isAdmin && <label className="text-[10px] font-bold uppercase tracking-wide text-white/65">Nhân viên<select value={employeeFilter} onChange={(event) => setEmployeeFilter(Number(event.target.value))} className="mt-1 min-h-10 w-full rounded-lg border border-white/15 bg-[#173D5F] px-3 text-xs text-white outline-none focus:border-[#B8944F]"><option value={0}>Tất cả</option>{employees.map((employee) => <option key={employee.id} value={employee.id}>{employee.fullName}</option>)}</select></label>}<label className="text-[10px] font-bold uppercase tracking-wide text-white/65">Trạng thái<select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value as "ALL" | WorkScheduleStatus)} className="mt-1 min-h-10 w-full rounded-lg border border-white/15 bg-[#173D5F] px-3 text-xs text-white outline-none focus:border-[#B8944F]"><option value="ALL">Tất cả</option><option value="SCHEDULED">Đã phân công</option><option value="FULFILLED">Đã hoàn thành</option><option value="ABSENT">Vắng mặt</option><option value="CANCELLED">Đã hủy</option></select></label></div></div><div className="space-y-5 p-4 sm:p-5">{sortedDates.length === 0 ? <EmptySchedule admin={isAdmin} /> : sortedDates.map((day) => <section key={day} aria-labelledby={`schedule-${day}`}><div className="mb-2 flex items-center justify-between rounded-lg bg-[#EEE8DC] px-4 py-2"><h3 id={`schedule-${day}`} className="text-xs font-bold capitalize tracking-wide text-[#0F2A43]">{formatWorkDate(day)}</h3><span className="text-[10px] font-bold text-[#66727C]">{groupedSchedules[day].length} ca</span></div><div className="grid gap-2">{groupedSchedules[day].map((schedule) => <article key={schedule.id} className="grid gap-4 rounded-xl border border-[#0F2A43]/10 bg-white p-4 transition hover:-translate-y-0.5 hover:shadow-md lg:grid-cols-[1.1fr_1fr_1fr_auto] lg:items-center"><div className="flex min-w-0 gap-3"><span className="h-11 w-1.5 shrink-0 rounded-full" style={{ backgroundColor: schedule.shiftColor }} /><div className="min-w-0"><p className="truncate text-sm font-bold text-[#0F2A43]">{schedule.shiftName} <span className="text-xs font-semibold text-[#80632F]">· {schedule.shiftCode}</span></p><p className="mt-1 truncate text-xs text-[#66727C]">{schedule.employeeName}</p></div></div><div><p className="text-xs font-bold text-[#27445F]">{formatWorkDateTime(schedule.scheduledStartUtc)} →</p><p className="mt-1 text-xs font-bold text-[#27445F]">{formatWorkDateTime(schedule.scheduledEndUtc)}</p></div><div className="flex flex-wrap items-center gap-2"><ScheduleStatusBadge schedule={schedule} />{schedule.late && <span className="text-[10px] font-bold text-orange-700">Muộn {schedule.lateMinutes} phút</span>}<p className="w-full text-[11px] text-[#66727C]">{schedule.actualCheckInUtc ? `Vào ${formatWorkDateTime(schedule.actualCheckInUtc)}` : "Chưa check-in"}{schedule.actualCheckOutUtc ? ` · Ra ${formatWorkDateTime(schedule.actualCheckOutUtc)}` : ""}</p></div><div className="flex flex-wrap gap-2 lg:justify-end">{isAdmin && schedule.status === "SCHEDULED" && !schedule.sessionId && <><button type="button" onClick={() => openEditSchedule(schedule)} className="min-h-10 rounded-lg border border-[#0F2A43]/15 px-3 text-xs font-bold text-[#0F2A43] transition hover:bg-[#F4EFE5]">Sửa</button><button type="button" onClick={() => { setCancelTarget(schedule); setCancelReason(""); setAttendanceError(""); }} className="min-h-10 rounded-lg border border-rose-200 px-3 text-xs font-bold text-rose-700 transition hover:bg-rose-50">Hủy</button></>}{isStaff && schedule.sessionStatus === "ACTIVE" && <button type="button" onClick={() => { setCheckoutTarget(schedule); setAttendanceNote(""); setAttendanceError(""); }} className="min-h-10 rounded-lg bg-[#B8944F] px-3 text-xs font-bold text-[#0F2A43]">Check-out</button>}</div></article>)}</div></section>)}</div></section>
    )}

    <ViewportModal open={scheduleModalOpen} onClose={() => setScheduleModalOpen(false)} labelledBy="schedule-form-title" busy={submitting} panelClassName="max-w-2xl"><form onSubmit={saveSchedule} className="flex min-h-0 flex-1 flex-col"><header className="border-b px-5 py-4"><p className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#80632F]">Lịch làm việc</p><h2 id="schedule-form-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">{scheduleEditing ? "Điều chỉnh lịch" : "Phân ca cho nhân viên"}</h2></header><div className="lux-scrollbar grid min-h-0 flex-1 gap-4 overflow-y-auto p-5 sm:grid-cols-2"><label><span className={labelClass}>Nhân viên *</span><select data-modal-autofocus value={scheduleForm.employeeId} onChange={(event) => setScheduleForm((current) => ({ ...current, employeeId: Number(event.target.value) }))} className={inputClass}><option value={0}>Chọn nhân viên</option>{employees.map((employee) => <option key={employee.id} value={employee.id}>{employee.fullName} · {employee.username}</option>)}</select></label><label><span className={labelClass}>Mẫu ca *</span><select value={scheduleForm.shiftTemplateId} onChange={(event) => setScheduleForm((current) => ({ ...current, shiftTemplateId: Number(event.target.value) }))} className={inputClass}><option value={0}>Chọn mẫu ca</option>{activeTemplates.map((template) => <option key={template.id} value={template.id}>{template.name} · {formatShiftTime(template.startTime)}–{formatShiftTime(template.endTime)}</option>)}</select></label><label><span className={labelClass}>Ngày làm việc *</span><input type="date" value={scheduleForm.workDate} onChange={(event) => setScheduleForm((current) => ({ ...current, workDate: event.target.value }))} className={inputClass} /></label><label className="sm:col-span-2"><span className={labelClass}>Ghi chú</span><textarea value={scheduleForm.note} maxLength={1000} onChange={(event) => setScheduleForm((current) => ({ ...current, note: event.target.value }))} rows={3} className={`${inputClass} resize-y`} /></label>{scheduleError && <p role="alert" className="sm:col-span-2 rounded-lg border border-rose-200 bg-rose-50 p-3 text-xs font-semibold text-rose-700">{scheduleError}</p>}</div><footer className="flex justify-end gap-2 border-t px-5 py-4"><button type="button" disabled={submitting} onClick={() => setScheduleModalOpen(false)} className="min-h-11 rounded-lg border px-4 text-sm font-bold">Đóng</button><button type="submit" disabled={submitting} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white disabled:opacity-60">{submitting ? "Đang lưu..." : "Lưu lịch"}</button></footer></form></ViewportModal>

    <ViewportModal open={templatesModalOpen} onClose={() => setTemplatesModalOpen(false)} labelledBy="template-manager-title" busy={submitting} panelClassName="max-w-5xl"><div className="flex min-h-0 flex-1 flex-col"><header className="flex items-center justify-between gap-3 border-b px-5 py-4"><div><p className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#80632F]">Cấu hình dùng chung</p><h2 id="template-manager-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">Mẫu ca làm việc</h2></div><button type="button" onClick={() => openTemplateEditor()} className="min-h-10 rounded-lg border px-3 text-xs font-bold text-[#0F2A43]">+ Mẫu mới</button></header><div className="lux-scrollbar grid min-h-0 flex-1 gap-5 overflow-y-auto p-5 lg:grid-cols-[0.85fr_1.15fr]"><div className="space-y-2">{templates.map((template) => <button type="button" key={template.id} onClick={() => openTemplateEditor(template)} className={`flex min-h-16 w-full items-center gap-3 rounded-xl border p-3 text-left transition hover:border-[#B8944F] ${templateEditing?.id === template.id ? "border-[#B8944F] bg-[#F8F4EA]" : "border-[#0F2A43]/10 bg-white"}`}><span className="h-9 w-2 rounded-full" style={{ backgroundColor: template.color }} /><span className="min-w-0 flex-1"><strong className="block truncate text-sm text-[#0F2A43]">{template.name}</strong><span className="mt-1 block text-xs text-[#66727C]">{formatShiftTime(template.startTime)}–{formatShiftTime(template.endTime)}{template.crossesMidnight ? " · qua ngày" : ""}</span></span><span className={`rounded-full px-2 py-1 text-[9px] font-bold ${template.active ? "bg-emerald-50 text-emerald-700" : "bg-slate-100 text-slate-600"}`}>{template.active ? "Đang dùng" : "Đã dừng"}</span></button>)}</div><form onSubmit={saveTemplate} className="grid content-start gap-4 rounded-xl border border-[#0F2A43]/10 bg-[#FBFAF6] p-4 sm:grid-cols-2"><label><span className={labelClass}>Mã ca *</span><input data-modal-autofocus value={templateForm.code} maxLength={32} onChange={(event) => setTemplateForm((current) => ({ ...current, code: event.target.value.toUpperCase().replace(/[^A-Z0-9_-]/g, "") }))} className={inputClass} /></label><label><span className={labelClass}>Tên ca *</span><input value={templateForm.name} maxLength={100} onChange={(event) => setTemplateForm((current) => ({ ...current, name: event.target.value }))} className={inputClass} /></label><label><span className={labelClass}>Bắt đầu *</span><input type="time" value={templateForm.startTime} onChange={(event) => setTemplateForm((current) => ({ ...current, startTime: event.target.value }))} className={inputClass} /></label><label><span className={labelClass}>Kết thúc *</span><input type="time" value={templateForm.endTime} onChange={(event) => setTemplateForm((current) => ({ ...current, endTime: event.target.value }))} className={inputClass} /></label><label><span className={labelClass}>Cho check-in sớm (phút)</span><input type="number" min={0} max={240} value={templateForm.checkInEarlyMinutes} onChange={(event) => setTemplateForm((current) => ({ ...current, checkInEarlyMinutes: Number(event.target.value) }))} className={inputClass} /></label><label><span className={labelClass}>Ngưỡng đi muộn (phút)</span><input type="number" min={0} max={240} value={templateForm.lateToleranceMinutes} onChange={(event) => setTemplateForm((current) => ({ ...current, lateToleranceMinutes: Number(event.target.value) }))} className={inputClass} /></label><label><span className={labelClass}>Màu nhận diện</span><input type="color" value={templateForm.color} onChange={(event) => setTemplateForm((current) => ({ ...current, color: event.target.value }))} className={`${inputClass} cursor-pointer p-1`} /></label><label><span className={labelClass}>Thứ tự</span><input type="number" min={0} value={templateForm.sortOrder} onChange={(event) => setTemplateForm((current) => ({ ...current, sortOrder: Number(event.target.value) }))} className={inputClass} /></label><label className="flex min-h-11 items-center gap-3 sm:col-span-2"><input type="checkbox" checked={templateForm.active} onChange={(event) => setTemplateForm((current) => ({ ...current, active: event.target.checked }))} className="h-5 w-5 accent-[#0F2A43]" /><span className="text-sm font-bold text-[#0F2A43]">Cho phép dùng mẫu ca này khi phân lịch mới</span></label>{templateError && <p role="alert" className="sm:col-span-2 rounded-lg border border-rose-200 bg-rose-50 p-3 text-xs font-semibold text-rose-700">{templateError}</p>}<div className="flex justify-end sm:col-span-2"><button type="submit" disabled={submitting} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white disabled:opacity-60">{submitting ? "Đang lưu..." : templateEditing ? "Cập nhật mẫu" : "Tạo mẫu ca"}</button></div></form></div><footer className="flex justify-end border-t px-5 py-4"><button type="button" onClick={() => setTemplatesModalOpen(false)} className="min-h-11 rounded-lg border px-4 text-sm font-bold">Đóng</button></footer></div></ViewportModal>

    <ViewportModal open={Boolean(cancelTarget)} onClose={() => setCancelTarget(null)} labelledBy="cancel-schedule-title" busy={submitting} panelClassName="max-w-lg"><div className="flex min-h-0 flex-1 flex-col"><header className="border-b px-5 py-4"><p className="text-[10px] font-bold uppercase tracking-[0.16em] text-rose-700">Thay đổi lịch</p><h2 id="cancel-schedule-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">Hủy lịch làm việc</h2></header><div className="p-5"><p className="text-sm leading-6 text-[#66727C]">{cancelTarget && `Hủy ${cancelTarget.shiftName} của ${cancelTarget.employeeName} ngày ${formatWorkDate(cancelTarget.workDate)}.`}</p><label className="mt-4 block"><span className={labelClass}>Lý do hủy *</span><textarea data-modal-autofocus value={cancelReason} maxLength={500} onChange={(event) => setCancelReason(event.target.value)} rows={4} className={`${inputClass} resize-y`} /></label>{attendanceError && <p role="alert" className="mt-3 text-xs font-semibold text-rose-700">{attendanceError}</p>}</div><footer className="flex justify-end gap-2 border-t px-5 py-4"><button type="button" onClick={() => setCancelTarget(null)} className="min-h-11 rounded-lg border px-4 text-sm font-bold">Quay lại</button><button type="button" disabled={submitting} onClick={() => void cancelSchedule()} className="min-h-11 rounded-lg bg-rose-700 px-5 text-sm font-bold text-white disabled:opacity-60">{submitting ? "Đang hủy..." : "Xác nhận hủy"}</button></footer></div></ViewportModal>

    <ViewportModal open={Boolean(checkoutTarget)} onClose={() => setCheckoutTarget(null)} labelledBy="work-checkout-title" busy={submitting} panelClassName="max-w-lg"><div className="flex min-h-0 flex-1 flex-col"><header className="border-b px-5 py-4"><p className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#80632F]">Kết thúc phiên làm việc</p><h2 id="work-checkout-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">Check-out cuối ca</h2></header><div className="p-5"><p className="rounded-lg bg-[#0F2A43]/5 p-3 text-sm leading-6 text-[#27445F]">Ca thu ngân liên kết sẽ được đóng tự động trong cùng giao dịch. Nếu thao tác lỗi, cả hai trạng thái đều được giữ nguyên.</p><label className="mt-4 block"><span className={labelClass}>{checkoutTarget && now.getTime() < new Date(checkoutTarget.scheduledEndUtc).getTime() ? "Lý do checkout sớm *" : "Ghi chú bàn giao"}</span><textarea data-modal-autofocus value={attendanceNote} maxLength={1000} onChange={(event) => setAttendanceNote(event.target.value)} rows={4} className={`${inputClass} resize-y`} /></label>{attendanceError && <p role="alert" className="mt-3 text-xs font-semibold text-rose-700">{attendanceError}</p>}</div><footer className="flex justify-end gap-2 border-t px-5 py-4"><button type="button" onClick={() => setCheckoutTarget(null)} className="min-h-11 rounded-lg border px-4 text-sm font-bold">Chưa kết thúc</button><button type="button" disabled={submitting} onClick={() => void checkOut()} className="min-h-11 rounded-lg bg-[#B8944F] px-5 text-sm font-bold text-[#0F2A43] disabled:opacity-60">{submitting ? "Đang xử lý..." : "Xác nhận check-out"}</button></footer></div></ViewportModal>

    {toast && <Toast {...toast} onClose={() => setToast(null)} />}
  </div>;
}
