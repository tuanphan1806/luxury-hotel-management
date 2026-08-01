import { describe, expect, it } from "vitest";
import type { WorkSchedule } from "@/lib/work-schedules";
import {
  formatWorkedMinutes,
  summarizeAttendance,
  summarizeAttendanceByDay,
  summarizeAttendanceByEmployee,
} from "@/lib/work-attendance-statistics";

const baseSchedule = (overrides: Partial<WorkSchedule> = {}): WorkSchedule => ({
  id: 1,
  employeeId: 10,
  employeeName: "Nguyễn Văn A",
  shiftTemplateId: 1,
  shiftCode: "MORNING",
  shiftName: "Ca sáng",
  shiftColor: "#B8944F",
  workDate: "2026-08-01",
  scheduledStartUtc: "2026-08-01T00:00:00Z",
  scheduledEndUtc: "2026-08-01T08:00:00Z",
  checkInEarlyMinutes: 30,
  lateToleranceMinutes: 10,
  status: "SCHEDULED",
  autoCheckOut: false,
  late: false,
  lateMinutes: 0,
  ...overrides,
});

const now = new Date("2026-08-01T10:00:00Z");

describe("work attendance statistics", () => {
  it("separates attended, late, absent, upcoming and unrecorded shifts", () => {
    const summary = summarizeAttendance([
      baseSchedule({
        id: 1,
        status: "FULFILLED",
        sessionStatus: "CLOSED",
        actualCheckInUtc: "2026-08-01T00:00:00Z",
        actualCheckOutUtc: "2026-08-01T08:00:00Z",
      }),
      baseSchedule({ id: 2, status: "ABSENT" }),
      baseSchedule({ id: 3, late: true, sessionId: 3, sessionStatus: "ACTIVE", actualCheckInUtc: "2026-08-01T07:00:00Z" }),
      baseSchedule({ id: 4, scheduledStartUtc: "2026-08-02T00:00:00Z", scheduledEndUtc: "2026-08-02T08:00:00Z" }),
      baseSchedule({ id: 5, status: "CANCELLED" }),
    ], now);

    expect(summary).toMatchObject({
      totalShifts: 4,
      attendedShifts: 2,
      completedShifts: 1,
      activeShifts: 1,
      onTimeShifts: 1,
      lateShifts: 1,
      absentShifts: 1,
      upcomingShifts: 1,
      unrecordedShifts: 0,
      cancelledShifts: 1,
      workedMinutes: 660,
      attendanceRate: 67,
    });
  });

  it("counts a past scheduled shift without attendance as unrecorded", () => {
    const summary = summarizeAttendance([baseSchedule()], now);
    expect(summary.unrecordedShifts).toBe(1);
    expect(summary.attendanceRate).toBe(0);
  });

  it("groups employee and day summaries without mixing staff data", () => {
    const schedules = [
      baseSchedule({ id: 1, status: "ABSENT" }),
      baseSchedule({ id: 2, employeeId: 20, employeeName: "Trần Văn B", workDate: "2026-08-02", scheduledStartUtc: "2026-08-02T00:00:00Z", scheduledEndUtc: "2026-08-02T08:00:00Z" }),
    ];
    const employees = summarizeAttendanceByEmployee(schedules, now);
    const days = summarizeAttendanceByDay(schedules, now);

    expect(employees).toHaveLength(2);
    expect(employees[0].employeeName).toBe("Nguyễn Văn A");
    expect(employees[0].absentShifts).toBe(1);
    expect(employees[1].upcomingShifts).toBe(1);
    expect(days.map((day) => day.workDate)).toEqual(["2026-08-02", "2026-08-01"]);
  });

  it("formats worked time for compact reporting", () => {
    expect(formatWorkedMinutes(0)).toBe("0 phút");
    expect(formatWorkedMinutes(60)).toBe("1 giờ");
    expect(formatWorkedMinutes(135)).toBe("2 giờ 15 phút");
  });
});
