export type WorkScheduleStatus = "SCHEDULED" | "FULFILLED" | "CANCELLED" | "ABSENT";
export type WorkShiftSessionStatus = "ACTIVE" | "CLOSED" | "AUTO_CLOSED";
export type WorkShiftRegistrationStatus = "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED";

export interface WorkShiftTemplate {
  id: number;
  code: string;
  name: string;
  startTime: string;
  endTime: string;
  crossesMidnight: boolean;
  checkInEarlyMinutes: number;
  lateToleranceMinutes: number;
  color: string;
  sortOrder: number;
  active: boolean;
  createdAtUtc?: string | null;
  updatedAtUtc?: string | null;
}

export interface WorkSchedule {
  id: number;
  employeeId: number;
  employeeName: string;
  shiftTemplateId: number;
  shiftCode: string;
  shiftName: string;
  shiftColor: string;
  workDate: string;
  scheduledStartUtc: string;
  scheduledEndUtc: string;
  checkInEarlyMinutes: number;
  lateToleranceMinutes: number;
  status: WorkScheduleStatus;
  sessionId?: number | null;
  sessionStatus?: WorkShiftSessionStatus | null;
  actualCheckInUtc?: string | null;
  actualCheckOutUtc?: string | null;
  autoCheckOut: boolean;
  late: boolean;
  lateMinutes: number;
  cashierShiftId?: number | null;
  note?: string | null;
  cancellationReason?: string | null;
  createdAtUtc?: string | null;
  updatedAtUtc?: string | null;
}

export interface WorkScheduleEmployee {
  id: number;
  fullName: string;
  username: string;
  email: string;
  type: "STAFF" | "ADMIN" | "CUSTOMER";
  status: "ACTIVE" | "INACTIVE";
}

export interface WorkScheduleForm {
  employeeId: number;
  shiftTemplateId: number;
  workDate: string;
  note: string;
}

export interface WorkShiftTemplateForm {
  code: string;
  name: string;
  startTime: string;
  endTime: string;
  checkInEarlyMinutes: number;
  lateToleranceMinutes: number;
  color: string;
  sortOrder: number;
  active: boolean;
}

export interface WorkShiftRegistration {
  id: number;
  employeeId: number;
  employeeName: string;
  shiftTemplateId: number;
  shiftCode: string;
  shiftName: string;
  shiftColor: string;
  workDate: string;
  status: WorkShiftRegistrationStatus;
  staffNote?: string | null;
  adminReason?: string | null;
  reviewedById?: number | null;
  reviewedByName?: string | null;
  reviewedAtUtc?: string | null;
  assignmentId?: number | null;
  createdAtUtc?: string | null;
  updatedAtUtc?: string | null;
}

export interface WorkShiftCalendarAssignment {
  id: number;
  employeeId: number;
  employeeName: string;
  status: WorkScheduleStatus;
  sessionStatus?: WorkShiftSessionStatus | null;
  late: boolean;
  lateMinutes: number;
}

export interface WorkShiftCalendarSlot {
  shiftTemplateId: number;
  shiftCode: string;
  shiftName: string;
  shiftColor: string;
  startTime: string;
  endTime: string;
  crossesMidnight: boolean;
  requiredStaff: number;
  assignedCount: number;
  pendingRequestCount: number;
  availableSlots: number;
  registrationOpen?: boolean;
  requirementNote?: string | null;
  currentUserAssignment?: WorkShiftCalendarAssignment | null;
  currentUserRequest?: WorkShiftRegistration | null;
  assignments: WorkShiftCalendarAssignment[];
  requests: WorkShiftRegistration[];
}

export interface WorkShiftCalendarDay {
  date: string;
  past: boolean;
  today: boolean;
  slots: WorkShiftCalendarSlot[];
}

export interface WorkShiftMonthCalendar {
  month: string;
  from: string;
  to: string;
  days: WorkShiftCalendarDay[];
}

export type WorkShiftPeriod = "MORNING" | "AFTERNOON" | "NIGHT";

export type WorkShiftCalendarStatusTone =
  | "active"
  | "available"
  | "danger"
  | "muted"
  | "pending"
  | "success"
  | "warning";

export interface WorkShiftCalendarStatus {
  label: string;
  compactLabel: string;
  tone: WorkShiftCalendarStatusTone;
}

/**
 * Opens the backend ApiResponse envelope without turning an omitted nullable
 * `data` field into a truthy object. Jackson omits null properties, so
 * ApiResponse.success(null) arrives as { success, timestamp }.
 */
export function unwrapWorkScheduleApiData<T>(response: unknown): T {
  const payload = (response as { data?: unknown } | null | undefined)?.data;
  if (payload && typeof payload === "object" && ("success" in payload || "data" in payload)) {
    return ("data" in payload
      ? (payload as { data: T }).data
      : null) as T;
  }
  return payload as T;
}

export function workScheduleDisplayStatus(schedule: WorkSchedule) {
  if (schedule.sessionStatus === "ACTIVE") return "Đang làm việc";
  if (schedule.sessionStatus === "AUTO_CLOSED") return "Hệ thống đã kết thúc";
  if (schedule.sessionStatus === "CLOSED" || schedule.status === "FULFILLED") return "Đã hoàn thành";
  return {
    SCHEDULED: "Đã phân công",
    FULFILLED: "Đã hoàn thành",
    CANCELLED: "Đã hủy",
    ABSENT: "Vắng mặt",
  }[schedule.status];
}

export function workScheduleTone(schedule: WorkSchedule) {
  if (schedule.sessionStatus === "ACTIVE") return "active" as const;
  if (schedule.status === "ABSENT") return "danger" as const;
  if (schedule.status === "CANCELLED") return "muted" as const;
  if (schedule.status === "FULFILLED") return "success" as const;
  return "scheduled" as const;
}

export function isCheckInAvailable(schedule: WorkSchedule, now = new Date()) {
  if (schedule.status !== "SCHEDULED" || schedule.sessionId) return false;
  const earliest = new Date(schedule.scheduledStartUtc).getTime()
    - schedule.checkInEarlyMinutes * 60 * 1000;
  return now.getTime() >= earliest && now.getTime() < new Date(schedule.scheduledEndUtc).getTime();
}

export function groupWorkSchedulesByDate(schedules: WorkSchedule[]) {
  return schedules.reduce<Record<string, WorkSchedule[]>>((groups, schedule) => {
    (groups[schedule.workDate] ||= []).push(schedule);
    return groups;
  }, {});
}

export function formatWorkDateTime(value?: string | null) {
  if (!value) return "—";
  return new Intl.DateTimeFormat("vi-VN", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
    timeZone: "Asia/Ho_Chi_Minh",
  }).format(new Date(value));
}

export function formatShiftTime(value: string) {
  return value.slice(0, 5);
}

/**
 * Maps editable shift templates into the three operational periods used by
 * the compact month calendar. Codes/names are preferred; time is a safe
 * fallback so renaming a template does not make the calendar unreadable.
 */
export function workShiftPeriod(
  slot: Pick<WorkShiftCalendarSlot, "shiftCode" | "shiftName" | "startTime" | "crossesMidnight">,
): WorkShiftPeriod {
  const searchable = `${slot.shiftCode} ${slot.shiftName}`
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toUpperCase();

  if (/\b(SANG|MORNING|AM)\b/.test(searchable)) return "MORNING";
  if (/\b(CHIEU|AFTERNOON|PM)\b/.test(searchable)) return "AFTERNOON";
  if (/\b(TOI|DEM|NIGHT)\b/.test(searchable) || slot.crossesMidnight) return "NIGHT";

  const startHour = Number(slot.startTime.slice(0, 2));
  if (startHour < 12) return "MORNING";
  if (startHour < 18) return "AFTERNOON";
  return "NIGHT";
}

export function compactWorkShiftLabel(period: WorkShiftPeriod) {
  return {
    MORNING: "S",
    AFTERNOON: "C",
    NIGHT: "T",
  }[period];
}

/** Three visual segments keep every month cell the same width. */
export function workShiftStaffingSegments(
  assignedCount: number,
  requiredStaff: number,
  segmentCount = 3,
) {
  const safeSegments = Math.max(1, segmentCount);
  const ratio = requiredStaff > 0
    ? Math.min(Math.max(assignedCount / requiredStaff, 0), 1)
    : 0;
  const rawFilled = Math.round(ratio * safeSegments);
  const filled = assignedCount > 0 ? Math.max(1, rawFilled) : 0;
  return Array.from({ length: safeSegments }, (_, index) => index < filled);
}

/** Adds calendar days without depending on the browser/device timezone. */
export function shiftWorkDate(value: string, days: number) {
  const [year, month, day] = value.split("-").map(Number);
  const shifted = new Date(Date.UTC(year, month - 1, day + days));
  return shifted.toISOString().slice(0, 10);
}

export function shiftCalendarMonth(value: string, months: number) {
  const [year, month] = value.split("-").map(Number);
  const shifted = new Date(Date.UTC(year, month - 1 + months, 1));
  return shifted.toISOString().slice(0, 7);
}

export function calendarMonthLeadingDays(value: string) {
  const [year, month] = value.split("-").map(Number);
  const sundayFirst = new Date(Date.UTC(year, month - 1, 1)).getUTCDay();
  return (sundayFirst + 6) % 7;
}

export function staffCalendarSlotLabel(slot: WorkShiftCalendarSlot, past = false) {
  if (slot.currentUserAssignment) return "Ca của bạn";
  if (slot.currentUserRequest?.status === "PENDING") return "Chờ duyệt";
  if (slot.currentUserRequest?.status === "APPROVED") return "Đã duyệt";
  if (slot.currentUserRequest?.status === "REJECTED") return "Đã từ chối";
  if (past || slot.registrationOpen === false) return "Đã qua";
  if (slot.availableSlots > 0) return `Còn ${slot.availableSlots} chỗ`;
  return "Đã đủ nhân sự";
}

/**
 * Produces an attendance-aware label for a compact calendar slot.
 * Past assignments must keep their operational meaning (late, absent,
 * completed or unrecorded) instead of collapsing into the generic "Đã qua".
 */
export function workShiftCalendarStatus(
  slot: WorkShiftCalendarSlot,
  isAdmin: boolean,
  past = false,
): WorkShiftCalendarStatus {
  if (!isAdmin) {
    const assignment = slot.currentUserAssignment;
    if (assignment) {
      if (assignment.status === "ABSENT") {
        return { label: "Vắng mặt", compactLabel: "Vắng", tone: "danger" };
      }
      if (assignment.late) {
        return {
          label: assignment.lateMinutes > 0
            ? `Muộn ${assignment.lateMinutes} phút`
            : "Đi muộn",
          compactLabel: "Muộn",
          tone: "warning",
        };
      }
      if (assignment.sessionStatus === "ACTIVE") {
        return { label: "Đang làm việc", compactLabel: "Đang ca", tone: "active" };
      }
      if (
        assignment.status === "FULFILLED"
        || assignment.sessionStatus === "CLOSED"
        || assignment.sessionStatus === "AUTO_CLOSED"
      ) {
        return { label: "Đã hoàn thành", compactLabel: "Hoàn tất", tone: "success" };
      }
      if (past) {
        return { label: "Chưa chấm công", compactLabel: "Chưa chấm", tone: "danger" };
      }
      return { label: "Ca của bạn", compactLabel: "Ca của bạn", tone: "active" };
    }

    if (slot.currentUserRequest?.status === "PENDING") {
      return { label: "Yêu cầu chờ duyệt", compactLabel: "Chờ duyệt", tone: "pending" };
    }
    if (slot.currentUserRequest?.status === "APPROVED") {
      return { label: "Yêu cầu đã duyệt", compactLabel: "Đã duyệt", tone: "success" };
    }
    if (slot.currentUserRequest?.status === "REJECTED") {
      return { label: "Yêu cầu bị từ chối", compactLabel: "Từ chối", tone: "danger" };
    }
    if (past || slot.registrationOpen === false) {
      return { label: "Đã qua", compactLabel: "Đã qua", tone: "muted" };
    }
    if (slot.availableSlots > 0) {
      return {
        label: `Còn ${slot.availableSlots} chỗ`,
        compactLabel: `${slot.availableSlots} trống`,
        tone: "available",
      };
    }
    return { label: "Đã đủ nhân sự", compactLabel: "Đủ", tone: "success" };
  }

  const absentCount = slot.assignments.filter((assignment) => assignment.status === "ABSENT").length;
  const lateCount = slot.assignments.filter((assignment) => (
    assignment.status !== "ABSENT" && assignment.late
  )).length;
  const activeCount = slot.assignments.filter((assignment) => assignment.sessionStatus === "ACTIVE").length;
  const completedCount = slot.assignments.filter((assignment) => (
    assignment.status === "FULFILLED"
    || assignment.sessionStatus === "CLOSED"
    || assignment.sessionStatus === "AUTO_CLOSED"
  )).length;
  const unrecordedCount = past
    ? slot.assignments.filter((assignment) => (
      assignment.status === "SCHEDULED" && !assignment.sessionStatus
    )).length
    : 0;

  const attendanceParts: string[] = [];
  if (absentCount > 0) attendanceParts.push(`${absentCount} vắng`);
  if (lateCount > 0) attendanceParts.push(`${lateCount} muộn`);
  if (activeCount > 0) attendanceParts.push(`${activeCount} đang ca`);
  if (unrecordedCount > 0) attendanceParts.push(`${unrecordedCount} chưa chấm`);

  if (attendanceParts.length > 0) {
    return {
      label: attendanceParts.join(" · "),
      compactLabel: attendanceParts[0],
      tone: absentCount > 0 || unrecordedCount > 0
        ? "danger"
        : lateCount > 0
          ? "warning"
          : "active",
    };
  }
  if (past && completedCount > 0) {
    return {
      label: `${completedCount} hoàn thành`,
      compactLabel: `${completedCount} xong`,
      tone: "success",
    };
  }
  if (past && slot.assignedCount === 0) {
    return { label: "Không phân ca", compactLabel: "Không phân", tone: "muted" };
  }
  if (slot.pendingRequestCount > 0) {
    return {
      label: `${slot.pendingRequestCount} yêu cầu chờ duyệt`,
      compactLabel: `${slot.pendingRequestCount} chờ`,
      tone: "pending",
    };
  }
  if (slot.availableSlots > 0) {
    return {
      label: `Còn ${slot.availableSlots} vị trí trống`,
      compactLabel: `${slot.availableSlots} trống`,
      tone: "available",
    };
  }
  return { label: "Đủ nhân sự", compactLabel: "Đủ", tone: "success" };
}
