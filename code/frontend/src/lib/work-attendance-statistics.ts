import type { WorkSchedule } from "@/lib/work-schedules";

export interface AttendanceSummary {
  totalShifts: number;
  attendedShifts: number;
  completedShifts: number;
  activeShifts: number;
  onTimeShifts: number;
  lateShifts: number;
  absentShifts: number;
  upcomingShifts: number;
  awaitingCheckInShifts: number;
  unrecordedShifts: number;
  cancelledShifts: number;
  workedMinutes: number;
  attendanceRate: number;
}

export interface EmployeeAttendanceSummary extends AttendanceSummary {
  employeeId: number;
  employeeName: string;
}

export interface DailyAttendanceSummary extends AttendanceSummary {
  workDate: string;
  schedules: WorkSchedule[];
}

type MutableAttendanceSummary = Omit<AttendanceSummary, "attendanceRate">;

const emptySummary = (): MutableAttendanceSummary => ({
  totalShifts: 0,
  attendedShifts: 0,
  completedShifts: 0,
  activeShifts: 0,
  onTimeShifts: 0,
  lateShifts: 0,
  absentShifts: 0,
  upcomingShifts: 0,
  awaitingCheckInShifts: 0,
  unrecordedShifts: 0,
  cancelledShifts: 0,
  workedMinutes: 0,
});

const hasAttended = (schedule: WorkSchedule) => Boolean(
  schedule.actualCheckInUtc
  || schedule.sessionId
  || schedule.status === "FULFILLED",
);

function workedMinutes(schedule: WorkSchedule, now: Date) {
  if (!schedule.actualCheckInUtc) return 0;
  const startedAt = new Date(schedule.actualCheckInUtc).getTime();
  const finishedAt = schedule.actualCheckOutUtc
    ? new Date(schedule.actualCheckOutUtc).getTime()
    : schedule.sessionStatus === "ACTIVE"
      ? now.getTime()
      : startedAt;
  if (!Number.isFinite(startedAt) || !Number.isFinite(finishedAt)) return 0;
  return Math.max(0, Math.round((finishedAt - startedAt) / 60_000));
}

function addSchedule(summary: MutableAttendanceSummary, schedule: WorkSchedule, now: Date) {
  if (schedule.status === "CANCELLED") {
    summary.cancelledShifts += 1;
    return;
  }

  summary.totalShifts += 1;
  const attended = hasAttended(schedule);
  const nowMs = now.getTime();
  const startMs = new Date(schedule.scheduledStartUtc).getTime();
  const endMs = new Date(schedule.scheduledEndUtc).getTime();

  if (attended) {
    summary.attendedShifts += 1;
    summary.workedMinutes += workedMinutes(schedule, now);
    if (schedule.late) summary.lateShifts += 1;
    else summary.onTimeShifts += 1;
  }

  if (schedule.status === "FULFILLED"
      || schedule.sessionStatus === "CLOSED"
      || schedule.sessionStatus === "AUTO_CLOSED") {
    summary.completedShifts += 1;
  }
  if (schedule.sessionStatus === "ACTIVE") summary.activeShifts += 1;
  if (schedule.status === "ABSENT") summary.absentShifts += 1;

  if (schedule.status === "SCHEDULED" && !attended) {
    if (startMs > nowMs) summary.upcomingShifts += 1;
    else if (endMs > nowMs) summary.awaitingCheckInShifts += 1;
    else summary.unrecordedShifts += 1;
  }
}

function finalizeSummary(summary: MutableAttendanceSummary): AttendanceSummary {
  const concludedShifts = summary.attendedShifts
    + summary.absentShifts
    + summary.unrecordedShifts;
  return {
    ...summary,
    attendanceRate: concludedShifts > 0
      ? Math.round((summary.attendedShifts / concludedShifts) * 100)
      : 0,
  };
}

export function summarizeAttendance(schedules: WorkSchedule[], now = new Date()) {
  const summary = emptySummary();
  schedules.forEach((schedule) => addSchedule(summary, schedule, now));
  return finalizeSummary(summary);
}

export function summarizeAttendanceByEmployee(
  schedules: WorkSchedule[],
  now = new Date(),
): EmployeeAttendanceSummary[] {
  const groups = new Map<number, { employeeName: string; summary: MutableAttendanceSummary }>();
  schedules.forEach((schedule) => {
    const group = groups.get(schedule.employeeId) || {
      employeeName: schedule.employeeName,
      summary: emptySummary(),
    };
    addSchedule(group.summary, schedule, now);
    groups.set(schedule.employeeId, group);
  });
  return Array.from(groups, ([employeeId, group]) => ({
    employeeId,
    employeeName: group.employeeName,
    ...finalizeSummary(group.summary),
  })).sort((left, right) => left.employeeName.localeCompare(right.employeeName, "vi"));
}

export function summarizeAttendanceByDay(
  schedules: WorkSchedule[],
  now = new Date(),
): DailyAttendanceSummary[] {
  const groups = new Map<string, WorkSchedule[]>();
  schedules.forEach((schedule) => {
    const group = groups.get(schedule.workDate) || [];
    group.push(schedule);
    groups.set(schedule.workDate, group);
  });
  return Array.from(groups, ([workDate, items]) => ({
    workDate,
    schedules: items.toSorted((left, right) =>
      left.scheduledStartUtc.localeCompare(right.scheduledStartUtc)),
    ...summarizeAttendance(items, now),
  })).sort((left, right) => right.workDate.localeCompare(left.workDate));
}

export function formatWorkedMinutes(minutes: number) {
  const safeMinutes = Math.max(0, Math.round(minutes));
  const hours = Math.floor(safeMinutes / 60);
  const remainder = safeMinutes % 60;
  if (hours === 0) return `${remainder} phút`;
  if (remainder === 0) return `${hours} giờ`;
  return `${hours} giờ ${remainder} phút`;
}
