import { describe, expect, it } from "vitest";
import {
  formatShiftTime,
  groupWorkSchedulesByDate,
  isCheckInAvailable,
  shiftWorkDate,
  type WorkSchedule,
  unwrapWorkScheduleApiData,
  workScheduleDisplayStatus,
} from "./work-schedules";

const schedule: WorkSchedule = {
  id: 1,
  employeeId: 7,
  employeeName: "Nhân viên",
  shiftTemplateId: 2,
  shiftCode: "SANG",
  shiftName: "Ca sáng",
  shiftColor: "#B8944F",
  workDate: "2026-08-01",
  scheduledStartUtc: "2026-07-31T23:00:00Z",
  scheduledEndUtc: "2026-08-01T07:00:00Z",
  checkInEarlyMinutes: 30,
  lateToleranceMinutes: 15,
  status: "SCHEDULED",
  autoCheckOut: false,
  late: false,
  lateMinutes: 0,
};

describe("work schedule helpers", () => {
  it("keeps shift template wall-clock times timezone-free", () => {
    expect(formatShiftTime("06:00:00")).toBe("06:00");
    expect(formatShiftTime("22:00:00")).toBe("22:00");
  });

  it("keeps a nullable current-session response null when Jackson omits data", () => {
    expect(unwrapWorkScheduleApiData<WorkSchedule | null>({
      data: { success: true, timestamp: "2026-07-31 06:55:00" },
    })).toBeNull();
    expect(unwrapWorkScheduleApiData<WorkSchedule>({
      data: { success: true, data: schedule, timestamp: "2026-07-31 06:55:00" },
    })).toEqual(schedule);
  });

  it("prioritizes the actual attendance session in the display status", () => {
    expect(workScheduleDisplayStatus({ ...schedule, sessionStatus: "ACTIVE", sessionId: 91 }))
      .toBe("Đang làm việc");
  });

  it("allows check-in only in the server-compatible window", () => {
    expect(isCheckInAvailable(schedule, new Date("2026-07-31T22:29:59Z"))).toBe(false);
    expect(isCheckInAvailable(schedule, new Date("2026-07-31T22:30:00Z"))).toBe(true);
    expect(isCheckInAvailable(schedule, new Date("2026-08-01T07:00:00Z"))).toBe(false);
    expect(isCheckInAvailable({ ...schedule, sessionId: 91 }, new Date("2026-07-31T23:00:00Z")))
      .toBe(false);
  });

  it("groups rows by assigned local work date", () => {
    const groups = groupWorkSchedulesByDate([
      schedule,
      { ...schedule, id: 2 },
      { ...schedule, id: 3, workDate: "2026-08-02" },
    ]);
    expect(groups["2026-08-01"]).toHaveLength(2);
    expect(groups["2026-08-02"]).toHaveLength(1);
  });

  it("shifts the hotel work date across month and year boundaries", () => {
    expect(shiftWorkDate("2026-07-31", 1)).toBe("2026-08-01");
    expect(shiftWorkDate("2026-01-01", -1)).toBe("2025-12-31");
  });
});
