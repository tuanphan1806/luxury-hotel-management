"use client";

import { useEffect, useMemo, useState, type FormEvent } from "react";
import ViewportModal from "@/components/UI/ViewportModal";
import { TimePickerInput } from "@/components/forms/DateTimeField";
import { apiClient, getApiErrorMessage } from "@/lib/api";
import {
  clearIdempotencyKey,
  getOrCreateIdempotencyKey,
} from "@/lib/idempotency";
import {
  formatShiftTime,
  unwrapWorkScheduleApiData,
  workDateRangeForMonth,
  workWeekRangesForMonth,
  workShiftColorForStartTime,
  workShiftPeriodFromStartTime,
  type WorkDailyShiftBulkCreateResult,
  type WorkDailyShiftBulkPreview,
  type WorkDailyShiftBulkRequest,
  type WorkDailyShiftForm,
  type WorkShiftMonthCalendar,
  type WorkShiftCalendarSlot,
  type WorkShiftTemplate,
} from "@/lib/work-schedules";

export type WorkDailyShiftAction =
  | { kind: "create"; date: string; usedTemplateIds?: number[] }
  | { kind: "edit"; date: string; slot: WorkShiftCalendarSlot }
  | { kind: "cancel"; date: string; slot: WorkShiftCalendarSlot }
  | { kind: "restore"; date: string; slot: WorkShiftCalendarSlot }
  | { kind: "delete"; date: string; slot: WorkShiftCalendarSlot }
  | { kind: "bulk" }
  | null;

interface WorkDailyShiftModalsProps {
  action: WorkDailyShiftAction;
  templates: WorkShiftTemplate[];
  onClose: () => void;
  onChanged: (message: string) => Promise<void> | void;
}

type Weekday = WorkDailyShiftBulkRequest["weekdays"][number];
type BulkPreset = "DAY" | "WEEK" | "MONTH";
type AssignmentMode =
  | WorkDailyShiftForm["assignmentPolicy"]
  | "REGISTRATION_CLOSED";

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

const futureRangeStart = (value: string) =>
  value < dateKey() ? dateKey() : value;

const formatWorkDate = (value: string) => {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return "Chưa chọn";
  return new Intl.DateTimeFormat("vi-VN", {
    timeZone: HOTEL_TIME_ZONE,
    weekday: "short",
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
  }).format(new Date(`${value}T12:00:00+07:00`));
};

const weekRangeForDate = (date: string) => {
  const ranges = workWeekRangesForMonth(date.slice(0, 7));
  return ranges.find((range) => range.from <= date && range.to >= date) || ranges[0];
};

const assignmentOptions: Array<{
  value: AssignmentMode;
  title: string;
  description: string;
  badge: string;
}> = [
  {
    value: "MANUAL_APPROVAL",
    title: "Đăng ký cần duyệt",
    description: "STAFF gửi yêu cầu; ADMIN kiểm tra rồi duyệt phân công.",
    badge: "Phổ biến",
  },
  {
    value: "AUTO_ASSIGN",
    title: "Tự nhận khi còn chỗ",
    description: "STAFF được xếp ca ngay nếu vẫn còn đủ vị trí.",
    badge: "Tự động",
  },
  {
    value: "ADMIN_ONLY",
    title: "ADMIN phân công",
    description: "Không mở đăng ký; chỉ ADMIN chọn nhân viên cho ca.",
    badge: "Kiểm soát",
  },
  {
    value: "REGISTRATION_CLOSED",
    title: "Tạm đóng đăng ký",
    description: "Giữ ca trên lịch nhưng tạm thời không nhận yêu cầu mới.",
    badge: "Tạm dừng",
  },
];

function assignmentModeFor(
  policy: WorkDailyShiftForm["assignmentPolicy"],
  registrationOpen: boolean,
): AssignmentMode {
  if (!registrationOpen && policy !== "ADMIN_ONLY") {
    return "REGISTRATION_CLOSED";
  }
  return policy;
}

function AssignmentModePicker({
  value,
  onChange,
}: {
  value: AssignmentMode;
  onChange: (
    policy: WorkDailyShiftForm["assignmentPolicy"],
    registrationOpen: boolean,
  ) => void;
}) {
  return (
    <div role="radiogroup" aria-label="Cách nhận ca" className="grid gap-2 sm:grid-cols-2">
      {assignmentOptions.map((option) => {
        const selected = value === option.value;
        return (
          <button
            key={option.value}
            type="button"
            role="radio"
            aria-checked={selected}
            onClick={() => {
              if (option.value === "REGISTRATION_CLOSED") {
                onChange("MANUAL_APPROVAL", false);
              } else {
                onChange(option.value, option.value !== "ADMIN_ONLY");
              }
            }}
            className={`min-h-[92px] cursor-pointer rounded-xl border p-3 text-left transition duration-200 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#B8944F] ${selected ? "border-[#B8944F] bg-[#FFF8E8] shadow-[0_8px_24px_rgba(15,42,67,0.08)]" : "border-[#D8E0E8] bg-white hover:-translate-y-0.5 hover:border-[#0F2A43]/35 hover:shadow-sm"}`}
          >
            <span className="flex items-start justify-between gap-3">
              <span className="text-sm font-black text-[#0F2A43]">{option.title}</span>
              <span className={`shrink-0 rounded-full px-2 py-1 text-[9px] font-black uppercase tracking-[0.08em] ${selected ? "bg-[#0F2A43] text-white" : "bg-[#EEF2F3] text-[#66727C]"}`}>
                {option.badge}
              </span>
            </span>
            <span className="mt-2 block text-xs leading-5 text-[#66727C]">
              {option.description}
            </span>
          </button>
        );
      })}
    </div>
  );
}

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
    color: workShiftColorForStartTime(formatShiftTime(template.startTime)),
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
  const [createUsedTemplateIds, setCreateUsedTemplateIds] = useState<number[]>([]);
  const [checkingCreateDate, setCheckingCreateDate] = useState(false);
  const availableCreateTemplates = useMemo(() => {
    if (action?.kind !== "create") return activeTemplates;
    const used = new Set(createUsedTemplateIds);
    return activeTemplates.filter((template) => !used.has(template.id));
  }, [action, activeTemplates, createUsedTemplateIds]);
  const [form, setForm] = useState<WorkDailyShiftForm | null>(null);
  const [cancelReason, setCancelReason] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [bulkPreset, setBulkPreset] = useState<BulkPreset>("WEEK");
  const [bulkFrom, setBulkFrom] = useState(() => {
    const range = weekRangeForDate(dateKey());
    return range?.from || dateKey();
  });
  const [bulkTo, setBulkTo] = useState(() => {
    const range = weekRangeForDate(dateKey());
    return range?.to || dateKey();
  });
  const [bulkWeekMonthValue, setBulkWeekMonthValue] = useState(() =>
    dateKey().slice(0, 7),
  );
  const [bulkWeekValue, setBulkWeekValue] = useState(
    () => weekRangeForDate(dateKey())?.value || "",
  );
  const [bulkMonthValue, setBulkMonthValue] = useState(() =>
    dateKey().slice(0, 7),
  );
  const [bulkTemplateIds, setBulkTemplateIds] = useState<number[]>([]);
  const [bulkWeekdays, setBulkWeekdays] = useState<Weekday[]>(() =>
    weekdays.map((item) => item.value),
  );
  const [bulkStaffCounts, setBulkStaffCounts] = useState<
    Record<number, number>
  >({});
  const [bulkRegistrationOpen, setBulkRegistrationOpen] = useState(true);
  const [bulkAssignmentPolicy, setBulkAssignmentPolicy] =
    useState<WorkDailyShiftForm["assignmentPolicy"]>("MANUAL_APPROVAL");
  const [preview, setPreview] = useState<WorkDailyShiftBulkPreview | null>(
    null,
  );
  const bulkWeekOptions = useMemo(
    () => workWeekRangesForMonth(bulkWeekMonthValue),
    [bulkWeekMonthValue],
  );

  useEffect(() => {
    setError("");
    setCancelReason("");
    setPreview(null);
    setCheckingCreateDate(false);
    if (!action) return;
    if (action.kind === "create") {
      const used = action.usedTemplateIds || [];
      setCreateUsedTemplateIds(used);
      const template = activeTemplates.find((item) => !used.includes(item.id));
      const fallback = activeTemplates[0];
      setForm(
        template
          ? formFromTemplate(template, action.date)
          : fallback
            ? { ...formFromTemplate(fallback, action.date), shiftTemplateId: 0 }
            : null,
      );
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
      const weekRange = weekRangeForDate(today);
      setBulkPreset("WEEK");
      setBulkWeekMonthValue(today.slice(0, 7));
      setBulkWeekValue(weekRange?.value || "");
      setBulkMonthValue(today.slice(0, 7));
      setBulkWeekdays(weekdays.map((item) => item.value));
      setBulkFrom(futureRangeStart(weekRange?.from || today));
      setBulkTo(weekRange?.to || today);
      const commonTemplateIds = activeTemplates
        .filter((template) => ["SANG", "CHIEU"].includes(template.code))
        .map((template) => template.id);
      setBulkTemplateIds(
        commonTemplateIds.length > 0
          ? commonTemplateIds
          : activeTemplates.slice(0, 2).map((template) => template.id),
      );
      setBulkStaffCounts(
        Object.fromEntries(activeTemplates.map((item) => [item.id, 1])),
      );
      setBulkRegistrationOpen(true);
      setBulkAssignmentPolicy("MANUAL_APPROVAL");
    }
  }, [action, activeTemplates, templates]);

  useEffect(() => {
    if (action?.kind !== "create" || !form?.workDate) return;
    const selectedDate = form.workDate;
    let cancelled = false;
    setCheckingCreateDate(true);
    void apiClient
      .get(`/api/work-schedules/calendar?month=${selectedDate.slice(0, 7)}`)
      .then((response) => {
        if (cancelled) return;
        const calendar = unwrapWorkScheduleApiData<WorkShiftMonthCalendar>(response);
        const used = calendar.days
          .find((day) => day.date === selectedDate)
          ?.slots.map((slot) => slot.shiftTemplateId) || [];
        setCreateUsedTemplateIds(used);
        setForm((current) => {
          if (!current || current.workDate !== selectedDate) return current;
          if (current.shiftTemplateId && !used.includes(current.shiftTemplateId)) {
            return current;
          }
          const nextTemplate = activeTemplates.find(
            (template) => !used.includes(template.id),
          );
          if (!nextTemplate) return { ...current, shiftTemplateId: 0 };
          return {
            ...formFromTemplate(nextTemplate, selectedDate),
            requiredStaff: current.requiredStaff,
            registrationOpen: current.registrationOpen,
            assignmentPolicy: current.assignmentPolicy,
            note: current.note,
          };
        });
      })
      .catch((cause) => {
        if (!cancelled) {
          setError(
            getApiErrorMessage(
              cause,
              "Không thể kiểm tra các mẫu ca đã dùng trong ngày",
            ),
          );
        }
      })
      .finally(() => {
        if (!cancelled) setCheckingCreateDate(false);
      });
    return () => {
      cancelled = true;
    };
  }, [action?.kind, activeTemplates, form?.workDate]);

  const selectTemplate = (templateId: number) => {
    const template = availableCreateTemplates.find((item) => item.id === templateId);
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
    const payload = {
      ...form,
      color: workShiftColorForStartTime(form.startTime),
      note: form.note.trim() || null,
    };
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

  const restoreShift = async () => {
    if (!action || action.kind !== "restore") return;
    setSubmitting(true);
    setError("");
    const scope = `work-daily-shift:restore:${action.slot.dailyShiftId}`;
    try {
      await apiClient.post(
        `/api/work-schedules/daily-shifts/${action.slot.dailyShiftId}/restore`,
        {},
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } },
      );
      clearIdempotencyKey(scope);
      await onChanged("Đã khôi phục ca; hãy phân công lại nhân viên nếu cần");
      onClose();
    } catch (cause) {
      setError(getApiErrorMessage(cause, "Không thể khôi phục ca làm việc"));
    } finally {
      setSubmitting(false);
    }
  };

  const deleteShift = async () => {
    if (!action || action.kind !== "delete") return;
    setSubmitting(true);
    setError("");
    const scope = `work-daily-shift:delete:${action.slot.dailyShiftId}`;
    try {
      await apiClient.delete(
        `/api/work-schedules/daily-shifts/${action.slot.dailyShiftId}`,
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } },
      );
      clearIdempotencyKey(scope);
      await onChanged("Đã xóa ca trống khỏi lịch");
      onClose();
    } catch (cause) {
      setError(getApiErrorMessage(cause, "Không thể xóa ca làm việc"));
    } finally {
      setSubmitting(false);
    }
  };

  const applyBulkPreset = (preset: BulkPreset) => {
    const today = dateKey();
    setBulkPreset(preset);
    if (preset === "DAY") {
      setBulkFrom(today);
      setBulkTo(today);
    } else if (preset === "WEEK") {
      const range = weekRangeForDate(today);
      setBulkWeekMonthValue(today.slice(0, 7));
      setBulkWeekValue(range?.value || "");
      setBulkFrom(futureRangeStart(range?.from || today));
      setBulkTo(range?.to || today);
    } else {
      const monthValue = today.slice(0, 7);
      const range = workDateRangeForMonth(monthValue);
      setBulkMonthValue(monthValue);
      setBulkFrom(futureRangeStart(range?.from || today));
      setBulkTo(range?.to || today);
    }
    setPreview(null);
  };

  const bulkRequest = (): WorkDailyShiftBulkRequest => ({
    from: bulkFrom,
    to: bulkTo,
    weekdays:
      bulkPreset === "DAY"
        ? weekdays.map((item) => item.value)
        : bulkWeekdays,
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
        color: workShiftColorForStartTime(formatShiftTime(template.startTime)),
        note: null,
      };
    }),
  });

  const previewBulk = async () => {
    if (
      !bulkFrom ||
      !bulkTo ||
      bulkFrom > bulkTo ||
      (bulkPreset === "WEEK" &&
        !bulkWeekOptions.some((option) => option.value === bulkWeekValue)) ||
      (bulkPreset === "MONTH" &&
        !workDateRangeForMonth(bulkMonthValue)) ||
      (bulkPreset !== "DAY" && bulkWeekdays.length === 0) ||
      bulkTemplateIds.length === 0
    ) {
      setError("Chọn khoảng ngày, ngày áp dụng và ít nhất một mẫu ca hợp lệ.");
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
  const restoreOpen = Boolean(action?.kind === "restore");
  const deleteOpen = Boolean(action?.kind === "delete");
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
                Chọn mẫu để lấy giờ và quy tắc mặc định; thay đổi tại đây chỉ áp dụng cho ngày đã chọn.
              </p>
            </header>
            <div className="lux-scrollbar grid min-h-0 flex-1 gap-4 overflow-y-auto p-5 sm:grid-cols-2">
              <label>
                <span className={labelClass}>Mẫu ca *</span>
                <select
                  data-modal-autofocus
                  value={form.shiftTemplateId}
                  disabled={action.kind === "edit" || checkingCreateDate}
                  onChange={(event) =>
                    selectTemplate(Number(event.target.value))
                  }
                  className={inputClass}
                >
                  {action.kind === "create" && availableCreateTemplates.length === 0 ? (
                    <option value={0}>Ngày này đã dùng tất cả mẫu ca</option>
                  ) : null}
                  {availableCreateTemplates.map((template) => (
                    <option key={template.id} value={template.id}>
                      {template.name} · {formatShiftTime(template.startTime)}–
                      {formatShiftTime(template.endTime)}
                    </option>
                  ))}
                </select>
                {action.kind === "create" ? (
                  <span className="mt-1.5 block text-[11px] leading-4 text-[#66727C]">
                    {checkingCreateDate
                      ? "Đang kiểm tra các ca đã có trong ngày..."
                      : availableCreateTemplates.length === 0
                        ? "Chọn ngày khác, chỉnh ca đã có hoặc tạo thêm mẫu ca mới."
                        : `${availableCreateTemplates.length} mẫu ca còn có thể dùng trong ngày này.`}
                  </span>
                ) : null}
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
              <div>
                <span className={labelClass}>Bắt đầu *</span>
                <TimePickerInput
                  label="Giờ bắt đầu ca"
                  value={form.startTime}
                  onValueChange={(value) =>
                    setForm({
                      ...form,
                      startTime: value,
                      color: workShiftColorForStartTime(value),
                    })
                  }
                  className={inputClass}
                />
              </div>
              <div>
                <span className={labelClass}>Kết thúc *</span>
                <TimePickerInput
                  label="Giờ kết thúc ca"
                  value={form.endTime}
                  onValueChange={(value) =>
                    setForm({ ...form, endTime: value })
                  }
                  className={inputClass}
                />
              </div>
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
              <div className="rounded-xl border border-[#0F2A43]/10 bg-[#F8F5EE] px-4 py-3">
                <span className={labelClass}>Màu nhận diện tự động</span>
                <span className="flex items-center gap-2 text-sm font-bold text-[#0F2A43]">
                  <i className="h-4 w-4 rounded-full" style={{ backgroundColor: workShiftColorForStartTime(form.startTime) }} />
                  {{ MORNING: "Ca sáng", AFTERNOON: "Ca chiều", NIGHT: "Ca tối" }[workShiftPeriodFromStartTime(form.startTime)]}
                </span>
              </div>
              <div className="sm:col-span-2">
                <span className={labelClass}>Cách nhận ca *</span>
                <AssignmentModePicker
                  value={assignmentModeFor(
                    form.assignmentPolicy,
                    form.registrationOpen,
                  )}
                  onChange={(assignmentPolicy, registrationOpen) =>
                    setForm({
                      ...form,
                      assignmentPolicy,
                      registrationOpen,
                    })
                  }
                />
              </div>
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
                disabled={
                  submitting ||
                  checkingCreateDate ||
                  !form.shiftTemplateId ||
                  !form.shiftName.trim()
                }
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
        ) : action?.kind === "create" ? (
          <div className="flex min-h-72 flex-col items-center justify-center p-8 text-center">
            <h2 id="daily-shift-form-title" className="font-serif text-2xl font-bold text-[#0F2A43]">Không còn mẫu ca khả dụng</h2>
            <p className="mt-2 max-w-md text-sm leading-6 text-[#66727C]">Ngày này đã dùng tất cả mẫu ca đang hoạt động. Hãy chỉnh ca đã có hoặc tạo thêm một mẫu ca phù hợp.</p>
            <button type="button" onClick={onClose} className="mt-5 min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white">Đóng</button>
          </div>
        ) : null}
      </ViewportModal>

      <ViewportModal
        open={restoreOpen}
        onClose={onClose}
        labelledBy="daily-shift-restore-title"
        busy={submitting}
        panelClassName="max-w-lg"
        zIndexClassName="z-[125]"
      >
        {action?.kind === "restore" ? (
          <div className="flex min-h-0 flex-1 flex-col">
            <header className="border-b px-5 py-4">
              <p className="text-[10px] font-bold uppercase tracking-[0.16em] text-emerald-700">Khôi phục kế hoạch</p>
              <h2 id="daily-shift-restore-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">Khôi phục {action.slot.shiftName}</h2>
            </header>
            <div className="p-5">
              <p className="text-sm leading-6 text-[#66727C]">Ca sẽ mở lại với giờ và chính sách trước khi hủy. Phân công và yêu cầu đăng ký đã hủy không tự khôi phục; ADMIN cần phân công lại hoặc STAFF đăng ký lại.</p>
              {error ? <p role="alert" className="mt-3 text-xs font-semibold text-rose-700">{error}</p> : null}
            </div>
            <footer className="flex justify-end gap-2 border-t px-5 py-4">
              <button type="button" disabled={submitting} onClick={onClose} className="min-h-11 rounded-lg border px-4 text-sm font-bold">Đóng</button>
              <button type="button" disabled={submitting} onClick={() => void restoreShift()} className="min-h-11 rounded-lg bg-emerald-700 px-5 text-sm font-bold text-white disabled:opacity-50">{submitting ? "Đang khôi phục..." : "Khôi phục ca"}</button>
            </footer>
          </div>
        ) : null}
      </ViewportModal>

      <ViewportModal
        open={deleteOpen}
        onClose={onClose}
        labelledBy="daily-shift-delete-title"
        busy={submitting}
        panelClassName="max-w-lg"
        zIndexClassName="z-[125]"
      >
        {action?.kind === "delete" ? (
          <div className="flex min-h-0 flex-1 flex-col">
            <header className="border-b px-5 py-4">
              <p className="text-[10px] font-bold uppercase tracking-[0.16em] text-rose-700">Dọn lịch chưa sử dụng</p>
              <h2 id="daily-shift-delete-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">Xóa {action.slot.shiftName}</h2>
            </header>
            <div className="p-5">
              <p className="text-sm leading-6 text-[#66727C]">
                Chỉ ca tương lai chưa từng có phân công, đăng ký hoặc check-in mới được xóa hẳn. Ca đã phát sinh lịch sử phải dùng Hủy ca để giữ dấu vận hành.
              </p>
              {error ? <p role="alert" className="mt-3 text-xs font-semibold text-rose-700">{error}</p> : null}
            </div>
            <footer className="flex justify-end gap-2 border-t px-5 py-4">
              <button type="button" disabled={submitting} onClick={onClose} className="min-h-11 rounded-lg border px-4 text-sm font-bold">Giữ lại</button>
              <button type="button" disabled={submitting} onClick={() => void deleteShift()} className="min-h-11 rounded-lg bg-rose-700 px-5 text-sm font-bold text-white disabled:opacity-50">{submitting ? "Đang xóa..." : "Xóa ca trống"}</button>
            </footer>
          </div>
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
                    ["DAY", "Ngày"],
                    ["WEEK", "Tuần"],
                    ["MONTH", "Tháng"],
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
              {bulkPreset === "DAY" ? (
                <div className="mt-4 grid gap-4 sm:grid-cols-2">
                  <label>
                    <span className={labelClass}>Ngày đầu *</span>
                    <input
                      type="date"
                      min={dateKey()}
                      value={bulkFrom}
                      onChange={(event) => {
                        const nextFrom = event.target.value;
                        setBulkFrom(nextFrom);
                        if (!bulkTo || bulkTo < nextFrom) setBulkTo(nextFrom);
                        setPreview(null);
                      }}
                      className={inputClass}
                    />
                  </label>
                  <label>
                    <span className={labelClass}>Ngày cuối *</span>
                    <input
                      type="date"
                      min={bulkFrom || dateKey()}
                      value={bulkTo}
                      onChange={(event) => {
                        setBulkTo(event.target.value);
                        setPreview(null);
                      }}
                      className={inputClass}
                    />
                    <span className="mt-1.5 block text-[11px] leading-4 text-[#66727C]">
                      Chọn cùng ngày nếu chỉ muốn tạo ca cho một ngày.
                    </span>
                  </label>
                </div>
              ) : bulkPreset === "WEEK" ? (
                <div className="mt-4 grid gap-4 sm:grid-cols-2">
                  <label>
                    <span className={labelClass}>Tháng *</span>
                    <input
                      type="month"
                      min={dateKey().slice(0, 7)}
                      value={bulkWeekMonthValue}
                      onChange={(event) => {
                        const monthValue = event.target.value;
                        const options = workWeekRangesForMonth(monthValue);
                        const range =
                          options.find((option) => option.to >= dateKey()) ||
                          options[0];
                        setBulkWeekMonthValue(monthValue);
                        setBulkWeekValue(range?.value || "");
                        if (range) {
                          setBulkFrom(futureRangeStart(range.from));
                          setBulkTo(range.to);
                        }
                        setPreview(null);
                      }}
                      className={inputClass}
                    />
                  </label>
                  <label>
                    <span className={labelClass}>Tuần trong tháng *</span>
                    <select
                      value={bulkWeekValue}
                      onChange={(event) => {
                        const value = event.target.value;
                        const range = bulkWeekOptions.find(
                          (option) => option.value === value,
                        );
                        setBulkWeekValue(value);
                        if (range) {
                          setBulkFrom(futureRangeStart(range.from));
                          setBulkTo(range.to);
                        }
                        setPreview(null);
                      }}
                      className={inputClass}
                    >
                      {bulkWeekOptions.map((option) => (
                        <option
                          key={option.value}
                          value={option.value}
                          disabled={option.to < dateKey()}
                        >
                          Tuần {option.position} · {option.from.slice(8, 10)}/
                          {option.from.slice(5, 7)}–{option.to.slice(8, 10)}/
                          {option.to.slice(5, 7)}
                          {option.to < dateKey() ? " · đã qua" : ""}
                        </option>
                      ))}
                    </select>
                    <span className="mt-1.5 block text-[11px] leading-4 text-[#66727C]">
                      Tuần đầu và cuối được giới hạn trong đúng tháng đã chọn.
                    </span>
                  </label>
                </div>
              ) : (
                <label className="mt-4 block max-w-md">
                  <span className={labelClass}>Tháng áp dụng *</span>
                  <input
                    type="month"
                    min={dateKey().slice(0, 7)}
                    value={bulkMonthValue}
                    onChange={(event) => {
                      const value = event.target.value;
                      const range = workDateRangeForMonth(value);
                      setBulkMonthValue(value);
                      if (range) {
                        setBulkFrom(futureRangeStart(range.from));
                        setBulkTo(range.to);
                      }
                      setPreview(null);
                    }}
                    className={inputClass}
                  />
                  <span className="mt-1.5 block text-[11px] leading-4 text-[#66727C]">
                    Hệ thống áp dụng cho toàn bộ ngày trong tháng đã chọn.
                  </span>
                </label>
              )}
              <div className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-[#D8E0E8] bg-[#F7F9FC] px-4 py-3">
                <span>
                  <span className="block text-[10px] font-black uppercase tracking-[0.1em] text-[#80632F]">
                    Phạm vi sẽ tạo
                  </span>
                  <span className="mt-1 block text-sm font-bold text-[#0F2A43]">
                    {formatWorkDate(bulkFrom)} → {formatWorkDate(bulkTo)}
                  </span>
                </span>
                <span className="rounded-full bg-white px-3 py-1.5 text-[10px] font-bold text-[#66727C] shadow-sm">
                  {bulkPreset === "DAY"
                    ? "Áp dụng mọi ngày trong khoảng"
                    : `${bulkWeekdays.length}/7 ngày trong tuần`}
                </span>
              </div>
              {bulkPreset !== "DAY" ? (
                <section className="mt-4 rounded-xl border border-[#D8E0E8] bg-white p-4">
                  <div className="flex flex-wrap items-end justify-between gap-3">
                    <span>
                      <span className={labelClass}>Ngày áp dụng trong tuần *</span>
                      <span className="block text-xs text-[#66727C]">
                        Bỏ chọn ngày nghỉ hoặc ngày không muốn mở ca.
                      </span>
                    </span>
                    <button
                      type="button"
                      onClick={() => {
                        setBulkWeekdays(weekdays.map((item) => item.value));
                        setPreview(null);
                      }}
                      className="min-h-9 rounded-lg border px-3 text-[11px] font-bold text-[#0F2A43] transition hover:bg-[#F8F5EE]"
                    >
                      Chọn cả tuần
                    </button>
                  </div>
                  <div className="mt-3 grid grid-cols-4 gap-2 sm:grid-cols-7">
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
                          className={`min-h-10 rounded-lg border text-xs font-black transition ${checked ? "border-[#0F2A43] bg-[#0F2A43] text-white" : "border-[#D8E0E8] bg-[#F7F9FC] text-[#66727C] hover:border-[#B8944F]"}`}
                        >
                          {item.label}
                        </button>
                      );
                    })}
                  </div>
                </section>
              ) : null}
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
              <section className="mt-4 rounded-2xl border bg-[#F8F5EE] p-4">
                <span className={labelClass}>Cách nhận ca *</span>
                <AssignmentModePicker
                  value={assignmentModeFor(
                    bulkAssignmentPolicy,
                    bulkRegistrationOpen,
                  )}
                  onChange={(assignmentPolicy, registrationOpen) => {
                    setBulkAssignmentPolicy(assignmentPolicy);
                    setBulkRegistrationOpen(registrationOpen);
                    setPreview(null);
                  }}
                />
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
                    <details className="mt-3 rounded-xl border border-amber-200 bg-white/70 px-3 py-2 text-xs text-amber-900">
                      <summary className="cursor-pointer font-bold">
                        Xem {preview.skippedExistingCount} ca được giữ nguyên
                      </summary>
                      <div className="mt-2 grid gap-1.5 sm:grid-cols-2">
                        {preview.items
                          .filter((item) => item.action === "SKIP_EXISTING")
                          .slice(0, 12)
                          .map((item) => (
                            <p key={`${item.workDate}-${item.shiftTemplateId}`} className="rounded-lg bg-amber-50 px-2.5 py-2">
                              <strong>{formatWorkDate(item.workDate)}</strong> · {item.shiftName}
                              <span className="block text-[11px] font-medium text-amber-800/80">
                                {item.reason || "Ca đã tồn tại và không bị ghi đè"}
                              </span>
                            </p>
                          ))}
                      </div>
                      {preview.skippedExistingCount > 12 ? (
                        <p className="mt-2 text-[11px] font-semibold">
                          Và {preview.skippedExistingCount - 12} ca khác.
                        </p>
                      ) : null}
                    </details>
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
