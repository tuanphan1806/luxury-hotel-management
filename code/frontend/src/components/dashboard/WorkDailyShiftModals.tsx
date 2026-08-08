"use client";

import { useEffect, useMemo, useState, type FormEvent } from "react";
import ViewportModal from "@/components/UI/ViewportModal";
import { apiClient, getApiErrorMessage } from "@/lib/api";
import {
  clearIdempotencyKey,
  getOrCreateIdempotencyKey,
} from "@/lib/idempotency";
import {
  formatShiftTime,
  shiftWorkDate,
  unwrapWorkScheduleApiData,
  type WorkDailyShiftBulkCreateResult,
  type WorkDailyShiftBulkPreview,
  type WorkDailyShiftBulkRequest,
  type WorkDailyShiftForm,
  type WorkShiftCalendarSlot,
  type WorkShiftTemplate,
} from "@/lib/work-schedules";

export type WorkDailyShiftAction =
  | { kind: "create"; date: string }
  | { kind: "edit"; date: string; slot: WorkShiftCalendarSlot }
  | { kind: "cancel"; date: string; slot: WorkShiftCalendarSlot }
  | { kind: "bulk" }
  | null;

interface WorkDailyShiftModalsProps {
  action: WorkDailyShiftAction;
  templates: WorkShiftTemplate[];
  onClose: () => void;
  onChanged: (message: string) => Promise<void> | void;
}

type Weekday = WorkDailyShiftBulkRequest["weekdays"][number];
type BulkPreset = "DAY" | "WEEK" | "MONTH" | "CUSTOM";

const HOTEL_TIME_ZONE = "Asia/Ho_Chi_Minh";
const inputClass =
  "ops-control min-h-11 w-full rounded-lg border px-3 py-2.5 text-sm font-semibold text-[#0F2A43] outline-none transition hover:border-[#0F2A43]/30 focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20 disabled:cursor-not-allowed disabled:opacity-60";
const labelClass =
  "mb-2 block text-[11px] font-bold uppercase tracking-[0.08em] text-[#66727C]";
const weekdays: Array<{ value: Weekday; label: string }> = [
  { value: "MONDAY", label: "T2" },
  { value: "TUESDAY", label: "T3" },
  { value: "WEDNESDAY", label: "T4" },
  { value: "THURSDAY", label: "T5" },
  { value: "FRIDAY", label: "T6" },
  { value: "SATURDAY", label: "T7" },
  { value: "SUNDAY", label: "CN" },
];

const dateKey = () =>
  new Intl.DateTimeFormat("en-CA", {
    timeZone: HOTEL_TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());

const weekdayForDate = (date: string): Weekday => {
  const index = new Date(`${date}T12:00:00+07:00`).getUTCDay();
  return (
    [
      "SUNDAY",
      "MONDAY",
      "TUESDAY",
      "WEDNESDAY",
      "THURSDAY",
      "FRIDAY",
      "SATURDAY",
    ] as Weekday[]
  )[index];
};

const endOfMonth = (date: string) => {
  const [year, month] = date.split("-").map(Number);
  return new Date(Date.UTC(year, month, 0)).toISOString().slice(0, 10);
};

const endOfWeek = (date: string) => {
  const day = new Date(`${date}T12:00:00+07:00`).getUTCDay();
  const mondayOffset = (day + 6) % 7;
  return shiftWorkDate(date, 6 - mondayOffset);
};

function formFromTemplate(
  template: WorkShiftTemplate,
  workDate: string,
): WorkDailyShiftForm {
  return {
    shiftTemplateId: template.id,
    workDate,
    shiftName: template.name,
    startTime: formatShiftTime(template.startTime),
    endTime: formatShiftTime(template.endTime),
    requiredStaff: 1,
    registrationOpen: true,
    assignmentPolicy: "MANUAL_APPROVAL",
    checkInEarlyMinutes: template.checkInEarlyMinutes,
    lateToleranceMinutes: template.lateToleranceMinutes,
    color: template.color,
    note: "",
  };
}

export default function WorkDailyShiftModals({
  action,
  templates,
  onClose,
  onChanged,
}: WorkDailyShiftModalsProps) {
  const activeTemplates = useMemo(
    () => templates.filter((item) => item.active),
    [templates],
  );
  const [form, setForm] = useState<WorkDailyShiftForm | null>(null);
  const [cancelReason, setCancelReason] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [bulkPreset, setBulkPreset] = useState<BulkPreset>("WEEK");
  const [bulkFrom, setBulkFrom] = useState(dateKey);
  const [bulkTo, setBulkTo] = useState(() => endOfWeek(dateKey()));
  const [bulkWeekdays, setBulkWeekdays] = useState<Weekday[]>(
    weekdays.map((item) => item.value),
  );
  const [bulkTemplateIds, setBulkTemplateIds] = useState<number[]>([]);
  const [bulkStaffCounts, setBulkStaffCounts] = useState<
    Record<number, number>
  >({});
  const [bulkRegistrationOpen, setBulkRegistrationOpen] = useState(true);
  const [bulkAssignmentPolicy, setBulkAssignmentPolicy] =
    useState<WorkDailyShiftForm["assignmentPolicy"]>("MANUAL_APPROVAL");
  const [preview, setPreview] = useState<WorkDailyShiftBulkPreview | null>(
    null,
  );

  useEffect(() => {
    setError("");
    setCancelReason("");
    setPreview(null);
    if (!action) return;
    if (action.kind === "create") {
      const template = activeTemplates[0];
      setForm(template ? formFromTemplate(template, action.date) : null);
      return;
    }
    if (action.kind === "edit") {
      setForm({
        shiftTemplateId: action.slot.shiftTemplateId,
        workDate: action.date,
        shiftName: action.slot.shiftName,
        startTime: formatShiftTime(action.slot.startTime),
        endTime: formatShiftTime(action.slot.endTime),
        requiredStaff: action.slot.requiredStaff,
        registrationOpen: action.slot.registrationOpen,
        assignmentPolicy: action.slot.assignmentPolicy,
        checkInEarlyMinutes: action.slot.checkInEarlyMinutes,
        lateToleranceMinutes: action.slot.lateToleranceMinutes,
        color: action.slot.shiftColor,
        note: action.slot.requirementNote || "",
      });
      return;
    }
    if (action.kind === "bulk") {
      const today = dateKey();
      setBulkPreset("WEEK");
      setBulkFrom(today);
      setBulkTo(endOfWeek(today));
      setBulkWeekdays(weekdays.map((item) => item.value));
      setBulkTemplateIds(activeTemplates[0] ? [activeTemplates[0].id] : []);
      setBulkStaffCounts(
        Object.fromEntries(activeTemplates.map((item) => [item.id, 1])),
      );
      setBulkRegistrationOpen(true);
      setBulkAssignmentPolicy("MANUAL_APPROVAL");
    }
  }, [action, activeTemplates, templates]);

  const selectTemplate = (templateId: number) => {
    const template = activeTemplates.find((item) => item.id === templateId);
    if (!template || !form) return;
    setForm({
      ...formFromTemplate(template, form.workDate),
      requiredStaff: form.requiredStaff,
      registrationOpen: form.registrationOpen,
      assignmentPolicy: form.assignmentPolicy,
      note: form.note,
    });
  };

  const saveSingle = async (event: FormEvent) => {
    event.preventDefault();
    if (
      !action ||
      (action.kind !== "create" && action.kind !== "edit") ||
      !form
    )
      return;
    if (form.startTime === form.endTime) {
      setError("Giờ bắt đầu và kết thúc ca phải khác nhau.");
      return;
    }
    setSubmitting(true);
    setError("");
    const payload = { ...form, note: form.note.trim() || null };
    const scope = `work-daily-shift:${action.kind}:${action.kind === "edit" ? action.slot.dailyShiftId : "new"}:${JSON.stringify(payload)}`;
    try {
      const config = {
        headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) },
      };
      if (action.kind === "edit") {
        await apiClient.put(
          `/api/work-schedules/daily-shifts/${action.slot.dailyShiftId}`,
          payload,
          config,
        );
      } else {
        await apiClient.post(
          "/api/work-schedules/daily-shifts",
          payload,
          config,
        );
      }
      clearIdempotencyKey(scope);
      await onChanged(
        action.kind === "edit"
          ? "Đã cập nhật ca trong ngày"
          : "Đã mở ca trong ngày",
      );
      onClose();
    } catch (cause) {
      setError(getApiErrorMessage(cause, "Không thể lưu ca làm việc"));
    } finally {
      setSubmitting(false);
    }
  };

  const cancelShift = async () => {
    if (!action || action.kind !== "cancel") return;
    if (!cancelReason.trim()) {
      setError("Vui lòng nhập lý do hủy ca.");
      return;
    }
    setSubmitting(true);
    setError("");
    const scope = `work-daily-shift:cancel:${action.slot.dailyShiftId}:${cancelReason.trim()}`;
    try {
      await apiClient.post(
        `/api/work-schedules/daily-shifts/${action.slot.dailyShiftId}/cancel`,
        { reason: cancelReason.trim() },
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } },
      );
      clearIdempotencyKey(scope);
      await onChanged("Đã hủy ca trong ngày");
      onClose();
    } catch (cause) {
      setError(getApiErrorMessage(cause, "Không thể hủy ca làm việc"));
    } finally {
      setSubmitting(false);
    }
  };

  const applyBulkPreset = (preset: Exclude<BulkPreset, "CUSTOM">) => {
    const today = dateKey();
    setBulkPreset(preset);
    setBulkFrom(today);
    if (preset === "DAY") {
      setBulkTo(today);
      setBulkWeekdays([weekdayForDate(today)]);
    } else if (preset === "WEEK") {
      setBulkTo(endOfWeek(today));
      setBulkWeekdays(weekdays.map((item) => item.value));
    } else {
      setBulkTo(endOfMonth(today));
      setBulkWeekdays(weekdays.map((item) => item.value));
    }
    setPreview(null);
  };

  const bulkRequest = (): WorkDailyShiftBulkRequest => ({
    from: bulkFrom,
    to: bulkTo,
    weekdays: bulkWeekdays,
    shifts: bulkTemplateIds.map((id) => {
      const template = activeTemplates.find((item) => item.id === id)!;
      return {
        shiftTemplateId: template.id,
        shiftName: template.name,
        startTime: formatShiftTime(template.startTime),
        endTime: formatShiftTime(template.endTime),
        requiredStaff: bulkStaffCounts[id] || 1,
        registrationOpen:
          bulkAssignmentPolicy !== "ADMIN_ONLY" && bulkRegistrationOpen,
        assignmentPolicy: bulkAssignmentPolicy,
        checkInEarlyMinutes: template.checkInEarlyMinutes,
        lateToleranceMinutes: template.lateToleranceMinutes,
        color: template.color,
        note: null,
      };
    }),
  });

  const previewBulk = async () => {
    if (
      bulkFrom > bulkTo ||
      bulkWeekdays.length === 0 ||
      bulkTemplateIds.length === 0
    ) {
      setError("Chọn khoảng ngày, ít nhất một thứ và ít nhất một mẫu ca.");
      return;
    }
    setSubmitting(true);
    setError("");
    try {
      const response = await apiClient.post(
        "/api/work-schedules/daily-shifts/bulk/preview",
        bulkRequest(),
      );
      setPreview(
        unwrapWorkScheduleApiData<WorkDailyShiftBulkPreview>(response),
      );
    } catch (cause) {
      setError(getApiErrorMessage(cause, "Không thể xem trước danh sách ca"));
    } finally {
      setSubmitting(false);
    }
  };

  const createBulk = async () => {
    if (!preview) return;
    setSubmitting(true);
    setError("");
    try {
      const payload = bulkRequest();
      const scope = `work-daily-shift:bulk:${JSON.stringify(payload)}`;
      const response = await apiClient.post(
        "/api/work-schedules/daily-shifts/bulk",
        payload,
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } },
      );
      const result =
        unwrapWorkScheduleApiData<WorkDailyShiftBulkCreateResult>(response);
      clearIdempotencyKey(scope);
      await onChanged(
        `Đã tạo ${result.createdCount} ca; giữ nguyên ${result.skippedExistingCount} ca đã có`,
      );
      onClose();
    } catch (cause) {
      setError(getApiErrorMessage(cause, "Không thể tạo nhanh ca làm việc"));
    } finally {
      setSubmitting(false);
    }
  };

  const singleOpen = Boolean(
    action && (action.kind === "create" || action.kind === "edit"),
  );
  const cancelOpen = Boolean(action?.kind === "cancel");
  const bulkOpen = Boolean(action?.kind === "bulk");

  return (
    <>
      <ViewportModal
        open={singleOpen}
        onClose={onClose}
        labelledBy="daily-shift-form-title"
        busy={submitting}
        panelClassName="max-w-3xl"
        zIndexClassName="z-[125]"
      >
        {form &&
        action &&
        (action.kind === "create" || action.kind === "edit") ? (
          <form onSubmit={saveSingle} className="flex min-h-0 flex-1 flex-col">
            <header className="border-b bg-[#0F2A43] px-5 py-4 text-white">
              <p className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#D8C398]">
                Kế hoạch nhân sự theo ngày
              </p>
              <h2
                id="daily-shift-form-title"
                className="mt-1 font-serif text-2xl font-bold"
              >
                {action.kind === "edit"
                  ? "Điều chỉnh ca đã mở"
                  : "Mở ca làm việc"}
              </h2>
              <p className="mt-1 text-xs text-white/65">
                Chỉ ca được mở ở đây mới xuất hiện cho ADMIN và STAFF.
              </p>
            </header>
            <div className="lux-scrollbar grid min-h-0 flex-1 gap-4 overflow-y-auto p-5 sm:grid-cols-2">
              <label>
                <span className={labelClass}>Mẫu ca *</span>
                <select
                  data-modal-autofocus
                  value={form.shiftTemplateId}
                  disabled={action.kind === "edit"}
                  onChange={(event) =>
                    selectTemplate(Number(event.target.value))
                  }
                  className={inputClass}
                >
                  {activeTemplates.map((template) => (
                    <option key={template.id} value={template.id}>
                      {template.name} · {formatShiftTime(template.startTime)}–
                      {formatShiftTime(template.endTime)}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                <span className={labelClass}>Ngày làm việc *</span>
                <input
                  type="date"
                  min={dateKey()}
                  value={form.workDate}
                  disabled={action.kind === "edit"}
                  onChange={(event) =>
                    setForm({ ...form, workDate: event.target.value })
                  }
                  className={inputClass}
                />
              </label>
              <label className="sm:col-span-2">
                <span className={labelClass}>Tên hiển thị của ca *</span>
                <input
                  value={form.shiftName}
                  maxLength={100}
                  onChange={(event) =>
                    setForm({ ...form, shiftName: event.target.value })
                  }
                  className={inputClass}
                />
              </label>
              <label>
                <span className={labelClass}>Bắt đầu *</span>
                <input
                  type="time"
                  value={form.startTime}
                  onChange={(event) =>
                    setForm({ ...form, startTime: event.target.value })
                  }
                  className={inputClass}
                />
              </label>
              <label>
                <span className={labelClass}>Kết thúc *</span>
                <input
                  type="time"
                  value={form.endTime}
                  onChange={(event) =>
                    setForm({ ...form, endTime: event.target.value })
                  }
                  className={inputClass}
                />
              </label>
              <label>
                <span className={labelClass}>Số nhân viên cần *</span>
                <input
                  type="number"
                  min={1}
                  max={100}
                  value={form.requiredStaff}
                  onChange={(event) =>
                    setForm({
                      ...form,
                      requiredStaff: Number(event.target.value),
                    })
                  }
                  className={inputClass}
                />
              </label>
              <label>
                <span className={labelClass}>Màu nhận diện</span>
                <input
                  type="color"
                  value={form.color}
                  onChange={(event) =>
                    setForm({ ...form, color: event.target.value })
                  }
                  className={`${inputClass} p-1`}
                />
              </label>
              <label>
                <span className={labelClass}>Cách nhận ca *</span>
                <select
                  value={form.assignmentPolicy}
                  onChange={(event) => {
                    const assignmentPolicy = event.target
                      .value as WorkDailyShiftForm["assignmentPolicy"];
                    setForm({
                      ...form,
                      assignmentPolicy,
                      registrationOpen:
                        assignmentPolicy === "ADMIN_ONLY"
                          ? false
                          : form.registrationOpen,
                    });
                  }}
                  className={inputClass}
                >
                  <option value="MANUAL_APPROVAL">
                    STAFF đăng ký, ADMIN duyệt
                  </option>
                  <option value="AUTO_ASSIGN">
                    STAFF đăng ký và nhận ca ngay
                  </option>
                  <option value="ADMIN_ONLY">Chỉ ADMIN phân công</option>
                </select>
              </label>
              <label>
                <span className={labelClass}>Cho check-in sớm (phút)</span>
                <input
                  type="number"
                  min={0}
                  max={240}
                  value={form.checkInEarlyMinutes}
                  onChange={(event) =>
                    setForm({
                      ...form,
                      checkInEarlyMinutes: Number(event.target.value),
                    })
                  }
                  className={inputClass}
                />
              </label>
              <label>
                <span className={labelClass}>Dung sai đi muộn (phút)</span>
                <input
                  type="number"
                  min={0}
                  max={240}
                  value={form.lateToleranceMinutes}
                  onChange={(event) =>
                    setForm({
                      ...form,
                      lateToleranceMinutes: Number(event.target.value),
                    })
                  }
                  className={inputClass}
                />
              </label>
              <label className="sm:col-span-2 flex min-h-11 items-center gap-3 rounded-xl border bg-[#F8F5EE] px-4 py-3 text-sm font-bold text-[#0F2A43]">
                <input
                  type="checkbox"
                  checked={form.registrationOpen}
                  disabled={form.assignmentPolicy === "ADMIN_ONLY"}
                  onChange={(event) =>
                    setForm({ ...form, registrationOpen: event.target.checked })
                  }
                  className="h-4 w-4 accent-[#0F2A43] disabled:opacity-40"
                />
                <span>
                  <span className="block">Mở đăng ký ca cho STAFF</span>
                  <span className="mt-1 block text-[11px] font-medium text-[#66727C]">
                    Ca chỉ do ADMIN phân công luôn đóng đăng ký.
                  </span>
                </span>
              </label>
              <label className="sm:col-span-2">
                <span className={labelClass}>Ghi chú</span>
                <textarea
                  rows={3}
                  maxLength={500}
                  value={form.note}
                  onChange={(event) =>
                    setForm({ ...form, note: event.target.value })
                  }
                  className={`${inputClass} resize-y`}
                />
              </label>
              {error ? (
                <p
                  role="alert"
                  className="sm:col-span-2 rounded-lg border border-rose-200 bg-rose-50 p-3 text-xs font-semibold text-rose-700"
                >
                  {error}
                </p>
              ) : null}
            </div>
            <footer className="flex justify-end gap-2 border-t px-5 py-4">
              <button
                type="button"
                disabled={submitting}
                onClick={onClose}
                className="min-h-11 rounded-lg border px-4 text-sm font-bold"
              >
                Đóng
              </button>
              <button
                type="submit"
                disabled={submitting || !form.shiftName.trim()}
                className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white disabled:opacity-50"
              >
                {submitting
                  ? "Đang lưu..."
                  : action.kind === "edit"
                    ? "Lưu thay đổi"
                    : "Mở ca"}
              </button>
            </footer>
          </form>
        ) : null}
      </ViewportModal>

      <ViewportModal
        open={cancelOpen}
        onClose={onClose}
        labelledBy="daily-shift-cancel-title"
        busy={submitting}
        panelClassName="max-w-lg"
        zIndexClassName="z-[125]"
      >
        {action?.kind === "cancel" ? (
          <div className="flex min-h-0 flex-1 flex-col">
            <header className="border-b px-5 py-4">
              <p className="text-[10px] font-bold uppercase tracking-[0.16em] text-rose-700">
                Thay đổi kế hoạch
              </p>
              <h2
                id="daily-shift-cancel-title"
                className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]"
              >
                Hủy {action.slot.shiftName}
              </h2>
            </header>
            <div className="p-5">
              <p className="text-sm leading-6 text-[#66727C]">
                Có thể hủy trước giờ bắt đầu. Phân công và yêu cầu đang chờ sẽ
                được hủy cùng ca; ca đã có người check-in không thể hủy.
              </p>
              <label className="mt-4 block">
                <span className={labelClass}>Lý do hủy *</span>
                <textarea
                  data-modal-autofocus
                  rows={4}
                  maxLength={500}
                  value={cancelReason}
                  onChange={(event) => setCancelReason(event.target.value)}
                  className={`${inputClass} resize-y`}
                />
              </label>
              {error ? (
                <p
                  role="alert"
                  className="mt-3 text-xs font-semibold text-rose-700"
                >
                  {error}
                </p>
              ) : null}
            </div>
            <footer className="flex justify-end gap-2 border-t px-5 py-4">
              <button
                type="button"
                onClick={onClose}
                className="min-h-11 rounded-lg border px-4 text-sm font-bold"
              >
                Quay lại
              </button>
              <button
                type="button"
                disabled={submitting || !cancelReason.trim()}
                onClick={() => void cancelShift()}
                className="min-h-11 rounded-lg bg-rose-700 px-5 text-sm font-bold text-white disabled:opacity-50"
              >
                {submitting ? "Đang hủy..." : "Xác nhận hủy ca"}
              </button>
            </footer>
          </div>
        ) : null}
      </ViewportModal>

      <ViewportModal
        open={bulkOpen}
        onClose={onClose}
        labelledBy="daily-shift-bulk-title"
        busy={submitting}
        panelClassName="max-w-5xl"
        zIndexClassName="z-[125]"
      >
        {action?.kind === "bulk" ? (
          <div className="flex min-h-0 flex-1 flex-col">
            <header className="border-b bg-[#0F2A43] px-5 py-4 text-white">
              <p className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#D8C398]">
                Lập lịch nhanh
              </p>
              <h2
                id="daily-shift-bulk-title"
                className="mt-1 font-serif text-2xl font-bold"
              >
                Tạo ca hàng loạt
              </h2>
              <p className="mt-1 text-xs text-white/65">
                Chọn phạm vi, ngày áp dụng và mẫu ca. Ca đã tồn tại sẽ được giữ
                nguyên.
              </p>
            </header>
            <div className="lux-scrollbar min-h-0 flex-1 overflow-y-auto p-5">
              <div className="flex flex-wrap gap-2">
                {(
                  [
                    ["DAY", "Một ngày"],
                    ["WEEK", "Một tuần"],
                    ["MONTH", "Một tháng"],
                  ] as const
                ).map(([value, label]) => (
                  <button
                    key={value}
                    type="button"
                    onClick={() => applyBulkPreset(value)}
                    className={`min-h-10 rounded-lg border px-4 text-xs font-bold ${bulkPreset === value ? "border-[#0F2A43] bg-[#0F2A43] text-white" : "bg-white text-[#0F2A43]"}`}
                  >
                    {label}
                  </button>
                ))}
              </div>
              <div className="mt-4 grid gap-4 sm:grid-cols-2">
                <label>
                  <span className={labelClass}>Từ ngày</span>
                  <input
                    type="date"
                    min={dateKey()}
                    value={bulkFrom}
                    onChange={(event) => {
                      setBulkFrom(event.target.value);
                      setBulkPreset("CUSTOM");
                      setPreview(null);
                    }}
                    className={inputClass}
                  />
                </label>
                <label>
                  <span className={labelClass}>Đến ngày</span>
                  <input
                    type="date"
                    min={bulkFrom}
                    value={bulkTo}
                    onChange={(event) => {
                      setBulkTo(event.target.value);
                      setBulkPreset("CUSTOM");
                      setPreview(null);
                    }}
                    className={inputClass}
                  />
                </label>
              </div>
              <div className="mt-4">
                <span className={labelClass}>Áp dụng vào các thứ</span>
                <div className="grid grid-cols-4 gap-2 sm:grid-cols-7">
                  {weekdays.map((item) => {
                    const checked = bulkWeekdays.includes(item.value);
                    return (
                      <button
                        key={item.value}
                        type="button"
                        aria-pressed={checked}
                        onClick={() => {
                          setBulkWeekdays((current) =>
                            checked
                              ? current.filter((value) => value !== item.value)
                              : [...current, item.value],
                          );
                          setPreview(null);
                        }}
                        className={`min-h-11 rounded-lg border text-xs font-bold ${checked ? "border-[#B8944F] bg-[#FFF4D6] text-[#0F2A43]" : "bg-white text-[#7A858D]"}`}
                      >
                        {item.label}
                      </button>
                    );
                  })}
                </div>
              </div>
              <section className="mt-5 rounded-2xl border">
                <header className="border-b bg-[#F8F5EE] px-4 py-3">
                  <h3 className="font-bold text-[#0F2A43]">Mẫu ca cần tạo</h3>
                  <p className="mt-1 text-xs text-[#66727C]">
                    Có thể chọn nhiều ca; số nhân viên được cấu hình riêng cho
                    từng ca.
                  </p>
                </header>
                <div className="divide-y">
                  {activeTemplates.map((template) => {
                    const checked = bulkTemplateIds.includes(template.id);
                    return (
                      <div
                        key={template.id}
                        className="grid gap-3 p-4 sm:grid-cols-[auto_1fr_150px] sm:items-center"
                      >
                        <input
                          type="checkbox"
                          aria-label={`Chọn ${template.name}`}
                          checked={checked}
                          onChange={() => {
                            setBulkTemplateIds((current) =>
                              checked
                                ? current.filter((id) => id !== template.id)
                                : [...current, template.id],
                            );
                            setPreview(null);
                          }}
                          className="h-5 w-5 accent-[#0F2A43]"
                        />
                        <div>
                          <p className="font-bold text-[#0F2A43]">
                            {template.name}
                          </p>
                          <p className="mt-1 text-xs tabular-nums text-[#66727C]">
                            {formatShiftTime(template.startTime)}–
                            {formatShiftTime(template.endTime)}
                          </p>
                        </div>
                        <label>
                          <span className={labelClass}>Nhân viên cần</span>
                          <input
                            type="number"
                            min={1}
                            max={100}
                            disabled={!checked}
                            value={bulkStaffCounts[template.id] || 1}
                            onChange={(event) => {
                              setBulkStaffCounts((current) => ({
                                ...current,
                                [template.id]: Number(event.target.value),
                              }));
                              setPreview(null);
                            }}
                            className={inputClass}
                          />
                        </label>
                      </div>
                    );
                  })}
                </div>
              </section>
              <section className="mt-4 grid gap-4 rounded-2xl border bg-[#F8F5EE] p-4 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)] sm:items-end">
                <label>
                  <span className={labelClass}>Cách nhận ca *</span>
                  <select
                    value={bulkAssignmentPolicy}
                    onChange={(event) => {
                      const assignmentPolicy = event.target
                        .value as WorkDailyShiftForm["assignmentPolicy"];
                      setBulkAssignmentPolicy(assignmentPolicy);
                      if (assignmentPolicy === "ADMIN_ONLY") {
                        setBulkRegistrationOpen(false);
                      }
                      setPreview(null);
                    }}
                    className={inputClass}
                  >
                    <option value="MANUAL_APPROVAL">
                      STAFF đăng ký, ADMIN duyệt
                    </option>
                    <option value="AUTO_ASSIGN">
                      STAFF đăng ký và nhận ca ngay
                    </option>
                    <option value="ADMIN_ONLY">Chỉ ADMIN phân công</option>
                  </select>
                </label>
                <label className="flex min-h-11 items-center gap-3 rounded-xl border bg-white px-4 py-3 text-sm font-bold text-[#0F2A43]">
                  <input
                    type="checkbox"
                    checked={bulkRegistrationOpen}
                    disabled={bulkAssignmentPolicy === "ADMIN_ONLY"}
                    onChange={(event) => {
                      setBulkRegistrationOpen(event.target.checked);
                      setPreview(null);
                    }}
                    className="h-4 w-4 accent-[#0F2A43] disabled:opacity-40"
                  />
                  <span>
                    <span className="block">Mở đăng ký ca cho STAFF</span>
                    <span className="mt-1 block text-[11px] font-medium text-[#66727C]">
                      Ca chỉ do ADMIN phân công luôn đóng đăng ký.
                    </span>
                  </span>
                </label>
              </section>
              {preview ? (
                <section className="mt-5 rounded-2xl border border-emerald-200 bg-emerald-50/50 p-4">
                  <div className="grid grid-cols-3 gap-3 text-center">
                    <div>
                      <p className="text-[9px] font-bold uppercase text-[#66727C]">
                        Tổng dự kiến
                      </p>
                      <p className="mt-1 text-xl font-black text-[#0F2A43]">
                        {preview.candidateCount}
                      </p>
                    </div>
                    <div>
                      <p className="text-[9px] font-bold uppercase text-[#66727C]">
                        Sẽ tạo
                      </p>
                      <p className="mt-1 text-xl font-black text-emerald-700">
                        {preview.creatableCount}
                      </p>
                    </div>
                    <div>
                      <p className="text-[9px] font-bold uppercase text-[#66727C]">
                        Giữ nguyên
                      </p>
                      <p className="mt-1 text-xl font-black text-amber-700">
                        {preview.skippedExistingCount}
                      </p>
                    </div>
                  </div>
                  {preview.skippedExistingCount > 0 ? (
                    <p className="mt-3 text-center text-xs font-semibold text-amber-800">
                      Ca trùng không bị ghi đè; lịch và phân công hiện tại được
                      bảo toàn.
                    </p>
                  ) : null}
                </section>
              ) : null}
              {error ? (
                <p
                  role="alert"
                  className="mt-4 rounded-lg border border-rose-200 bg-rose-50 p-3 text-xs font-semibold text-rose-700"
                >
                  {error}
                </p>
              ) : null}
            </div>
            <footer className="flex flex-wrap justify-end gap-2 border-t px-5 py-4">
              <button
                type="button"
                onClick={onClose}
                className="min-h-11 rounded-lg border px-4 text-sm font-bold"
              >
                Đóng
              </button>
              <button
                type="button"
                disabled={submitting}
                onClick={() => void previewBulk()}
                className="min-h-11 rounded-lg border border-[#0F2A43] px-4 text-sm font-bold text-[#0F2A43]"
              >
                {submitting ? "Đang kiểm tra..." : "Xem trước"}
              </button>
              <button
                type="button"
                disabled={
                  submitting || !preview || preview.creatableCount === 0
                }
                onClick={() => void createBulk()}
                className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white disabled:opacity-45"
              >
                Tạo {preview?.creatableCount || 0} ca
              </button>
            </footer>
          </div>
        ) : null}
      </ViewportModal>
    </>
  );
}
