import { describe, expect, it } from "vitest";
import {
  formatShiftTime,
  groupWorkSchedulesByDate,
  isCheckInAvailable,
  calendarMonthLeadingDays,
  compactWorkShiftLabel,
  shiftCalendarMonth,
  shiftWorkDate,
  staffCalendarSlotLabel,
  type WorkSchedule,
  unwrapWorkScheduleApiData,
  workScheduleDisplayStatus,
  workShiftCalendarStatus,
  workShiftColorForStartTime,
  workShiftPeriod,
  workShiftPeriodFromStartTime,
  workShiftSortOrderForStartTime,
  workShiftStaffingSegments,
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

  it("unwraps the legacy paginated user response used by the staff selector", () => {
    const usersPage = {
      users: [{
        id: 7,
        fullName: "Nhân viên",
        username: "staff1",
        email: "staff1@luxstay.vn",
        type: "STAFF",
        status: "ACTIVE",
      }],
    };

    expect(unwrapWorkScheduleApiData<typeof usersPage>({
      data: {
        status: 200,
        message: "Get user by id successfully",
        data: usersPage,
      },
    })).toEqual(usersPage);
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

  it("shifts calendar months and keeps a Monday-first grid offset", () => {
    expect(shiftCalendarMonth("2026-12", 1)).toBe("2027-01");
    expect(shiftCalendarMonth("2026-01", -1)).toBe("2025-12");
    expect(calendarMonthLeadingDays("2026-08")).toBe(5);
  });

  it("summarizes a staff slot without exposing other employee details", () => {
    const slot = {
      dailyShiftId: 12,
      dailyShiftStatus: "OPEN" as const,
      assignmentPolicy: "MANUAL_APPROVAL" as const,
      shiftTemplateId: 2,
      shiftCode: "SANG",
      shiftName: "Ca sáng",
      shiftColor: "#B8944F",
      startTime: "06:00",
      endTime: "14:00",
      crossesMidnight: false,
      checkInEarlyMinutes: 30,
      lateToleranceMinutes: 10,
      registrationOpen: true,
      requiredStaff: 2,
      assignedCount: 1,
      pendingRequestCount: 0,
      availableSlots: 1,
      assignments: [],
      requests: [],
    };
    expect(staffCalendarSlotLabel(slot)).toBe("Còn 1 chỗ");
    expect(staffCalendarSlotLabel(slot, true)).toBe("Đã qua");
    expect(staffCalendarSlotLabel({
      ...slot,
      registrationOpen: false,
    })).toBe("Không mở đăng ký");
    expect(workShiftCalendarStatus({
      ...slot,
      registrationOpen: false,
    }, false, false)).toMatchObject({
      label: "Không mở đăng ký",
      compactLabel: "Đóng đăng ký",
    });
    expect(workShiftCalendarStatus({
      ...slot,
      registrationOpen: false,
      assignmentPolicy: "ADMIN_ONLY" as const,
    }, false, false)).toMatchObject({
      label: "Chỉ ADMIN phân công",
      compactLabel: "Đóng đăng ký",
    });
    expect(staffCalendarSlotLabel({ ...slot, availableSlots: 0 })).toBe("Đã đủ nhân sự");
    expect(staffCalendarSlotLabel({
      ...slot,
      currentUserRequest: {
        id: 9,
        employeeId: 7,
        employeeName: "Nhân viên",
        shiftTemplateId: 2,
        shiftCode: "SANG",
        shiftName: "Ca sáng",
        shiftColor: "#B8944F",
        workDate: "2026-08-01",
        status: "PENDING",
      },
    })).toBe("Chờ duyệt");
  });

  it("keeps real attendance outcomes visible on past calendar slots", () => {
    const slot = {
      dailyShiftId: 13,
      dailyShiftStatus: "COMPLETED" as const,
      assignmentPolicy: "MANUAL_APPROVAL" as const,
      shiftTemplateId: 2,
      shiftCode: "SANG",
      shiftName: "Ca sáng",
      shiftColor: "#B8944F",
      startTime: "06:00",
      endTime: "14:00",
      crossesMidnight: false,
      checkInEarlyMinutes: 30,
      lateToleranceMinutes: 10,
      registrationOpen: true,
      requiredStaff: 3,
      assignedCount: 3,
      pendingRequestCount: 0,
      availableSlots: 0,
      assignments: [
        { id: 1, employeeId: 7, employeeName: "A", status: "ABSENT" as const, late: false, lateMinutes: 0 },
        { id: 2, employeeId: 8, employeeName: "B", status: "FULFILLED" as const, late: true, lateMinutes: 18 },
        { id: 3, employeeId: 9, employeeName: "C", status: "FULFILLED" as const, sessionStatus: "CLOSED" as const, late: false, lateMinutes: 0 },
      ],
      requests: [],
    };

    expect(workShiftCalendarStatus(slot, true, true)).toMatchObject({
      label: "1 vắng · 1 muộn",
      tone: "danger",
    });
    expect(workShiftCalendarStatus({
      ...slot,
      assignments: slot.assignments.slice(2),
      assignedCount: 1,
    }, true, true)).toMatchObject({
      label: "1 hoàn thành",
      tone: "success",
    });
  });

  it("shows a staff member's own attendance result instead of a generic past label", () => {
    const slot = {
      dailyShiftId: 14,
      dailyShiftStatus: "COMPLETED" as const,
      assignmentPolicy: "MANUAL_APPROVAL" as const,
      shiftTemplateId: 2,
      shiftCode: "SANG",
      shiftName: "Ca sáng",
      shiftColor: "#B8944F",
      startTime: "06:00",
      endTime: "14:00",
      crossesMidnight: false,
      checkInEarlyMinutes: 30,
      lateToleranceMinutes: 10,
      registrationOpen: false,
      requiredStaff: 1,
      assignedCount: 1,
      pendingRequestCount: 0,
      availableSlots: 0,
      currentUserAssignment: {
        id: 2,
        employeeId: 8,
        employeeName: "B",
        status: "FULFILLED" as const,
        sessionStatus: "CLOSED" as const,
        late: true,
        lateMinutes: 18,
      },
      assignments: [],
      requests: [],
    };

    expect(workShiftCalendarStatus(slot, false, true)).toMatchObject({
      label: "Muộn 18 phút",
      compactLabel: "Muộn",
      tone: "warning",
    });

    expect(workShiftCalendarStatus({
      ...slot,
      currentUserAssignment: {
        ...slot.currentUserAssignment,
        sessionStatus: "AUTO_CLOSED" as const,
      },
    }, false, true)).toMatchObject({
      label: "Muộn 18 phút · hệ thống kết ca",
      compactLabel: "Tự kết ca",
      tone: "warning",
    });
  });

  it("distinguishes a due shift waiting for operational closure from a completed shift", () => {
    const slot = {
      dailyShiftId: 15,
      dailyShiftStatus: "OPEN" as const,
      assignmentPolicy: "MANUAL_APPROVAL" as const,
      shiftTemplateId: 2,
      shiftCode: "SANG",
      shiftName: "Ca sáng",
      shiftColor: "#B8944F",
      startTime: "06:00",
      endTime: "14:00",
      crossesMidnight: false,
      started: true,
      ended: true,
      checkInEarlyMinutes: 30,
      lateToleranceMinutes: 10,
      registrationOpen: false,
      requiredStaff: 1,
      assignedCount: 0,
      pendingRequestCount: 0,
      availableSlots: 0,
      assignments: [],
      requests: [],
    };

    expect(workShiftCalendarStatus(slot, true)).toMatchObject({
      label: "Đang chờ kết ca",
      tone: "warning",
    });
    expect(workShiftCalendarStatus({
      ...slot,
      dailyShiftStatus: "COMPLETED" as const,
    }, true)).toMatchObject({
      label: "Ca đã hoàn tất",
      tone: "success",
    });
  });

  it("maps editable templates into stable compact month labels", () => {
    expect(compactWorkShiftLabel(workShiftPeriod({
      shiftCode: "SANG",
      shiftName: "Ca sáng",
      startTime: "06:00",
      crossesMidnight: false,
    }))).toBe("S");
    expect(compactWorkShiftLabel(workShiftPeriod({
      shiftCode: "CUSTOM",
      shiftName: "Ca chiều linh hoạt",
      startTime: "14:00",
      crossesMidnight: false,
    }))).toBe("C");
    expect(compactWorkShiftLabel(workShiftPeriod({
      shiftCode: "CUSTOM",
      shiftName: "Ca trực khuya",
      startTime: "22:00",
      crossesMidnight: true,
    }))).toBe("T");
  });

  it("derives fixed period colours and ordering from the start time", () => {
    expect(workShiftPeriodFromStartTime("07:00")).toBe("MORNING");
    expect(workShiftColorForStartTime("07:00")).toBe("#B8944F");
    expect(workShiftPeriodFromStartTime("13:00")).toBe("AFTERNOON");
    expect(workShiftColorForStartTime("13:00")).toBe("#2F7D78");
    expect(workShiftPeriodFromStartTime("18:00")).toBe("NIGHT");
    expect(workShiftColorForStartTime("18:00")).toBe("#4E5D8C");
    expect(workShiftSortOrderForStartTime("22:00")).toBe(30);
  });

  it("normalizes staffing into three compact progress segments", () => {
    expect(workShiftStaffingSegments(0, 3)).toEqual([false, false, false]);
    expect(workShiftStaffingSegments(1, 3)).toEqual([true, false, false]);
    expect(workShiftStaffingSegments(2, 3)).toEqual([true, true, false]);
    expect(workShiftStaffingSegments(5, 5)).toEqual([true, true, true]);
  });
});
