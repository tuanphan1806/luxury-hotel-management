export type WorkScheduleStatus = "SCHEDULED" | "FULFILLED" | "CANCELLED" | "ABSENT";
export type WorkShiftSessionStatus = "ACTIVE" | "CLOSED" | "AUTO_CLOSED";

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

/**
 * Opens the backend ApiResponse envelope without turning an omitted nullable
 * `data` field into a truthy object. Jackson omits null properties, so
 * ApiResponse.success(null) arrives as { success, timestamp }.
 */
export function unwrapWorkScheduleApiData<T>(response: unknown): T {
  const payload = (response as { data?: unknown } | null | undefined)?.data;
  if (payload && typeof payload === "object" && "success" in payload) {
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

/** Adds calendar days without depending on the browser/device timezone. */
export function shiftWorkDate(value: string, days: number) {
  const [year, month, day] = value.split("-").map(Number);
  const shifted = new Date(Date.UTC(year, month - 1, day + days));
  return shifted.toISOString().slice(0, 10);
}
