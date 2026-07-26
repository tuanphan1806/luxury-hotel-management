import { describe, expect, it } from "vitest";
import {
  formatDashboardTimeGroupLabel,
  groupByCalendarTime,
  matchesIntervalTimeScope,
  matchesPointTimeScope,
} from "./dashboard-time";

const reference = new Date(2026, 6, 26, 12, 0, 0);

describe("dashboard time helpers", () => {
  it("filters point events by local day, week and month", () => {
    expect(matchesPointTimeScope(new Date(2026, 6, 26, 23, 59), "TODAY", reference)).toBe(true);
    expect(matchesPointTimeScope(new Date(2026, 6, 20, 8), "WEEK", reference)).toBe(true);
    expect(matchesPointTimeScope(new Date(2026, 6, 1, 8), "MONTH", reference)).toBe(true);
    expect(matchesPointTimeScope(new Date(2026, 5, 30, 8), "MONTH", reference)).toBe(false);
  });

  it("keeps multi-day stays in every scope they actually overlap", () => {
    const checkIn = new Date(2026, 6, 25, 15);
    const checkOut = new Date(2026, 6, 27, 11);
    expect(matchesIntervalTimeScope(checkIn, checkOut, "TODAY", reference)).toBe(true);
    expect(matchesIntervalTimeScope(checkIn, checkOut, "WEEK", reference)).toBe(true);
    expect(matchesIntervalTimeScope(checkIn, checkOut, "MONTH", reference)).toBe(true);
  });

  it("does not count a stay that ended exactly at the start of today", () => {
    const checkIn = new Date(2026, 6, 25, 15);
    const checkOut = new Date(2026, 6, 26, 0);
    expect(matchesIntervalTimeScope(checkIn, checkOut, "TODAY", reference)).toBe(false);
  });

  it("groups records sharing the same calendar day", () => {
    const groups = groupByCalendarTime(
      [
        { id: 1, date: new Date(2026, 6, 26, 8) },
        { id: 2, date: new Date(2026, 6, 26, 18) },
        { id: 3, date: new Date(2026, 6, 25, 18) },
      ],
      (item) => item.date,
      "DAY",
      reference,
    );
    expect(groups.map((group) => group.items.map((item) => item.id))).toEqual([[1, 2], [3]]);
  });

  it("groups records by Monday-to-Sunday calendar week", () => {
    const groups = groupByCalendarTime(
      [
        { id: 1, date: new Date(2026, 6, 20, 8) },
        { id: 2, date: new Date(2026, 6, 26, 18) },
        { id: 3, date: new Date(2026, 6, 27, 8) },
      ],
      (item) => item.date,
      "WEEK",
      reference,
    );
    expect(groups.map((group) => group.items.map((item) => item.id))).toEqual([[1, 2], [3]]);
    expect(formatDashboardTimeGroupLabel(groups[0], "WEEK", "vi-VN", "Tuần", "Không rõ")).toBe(
      "Tuần 20/07 – 26/07/2026",
    );
  });

  it("groups records by calendar month and keeps invalid dates separate", () => {
    const groups = groupByCalendarTime(
      [
        { id: 1, date: new Date(2026, 6, 1) as Date | string },
        { id: 2, date: new Date(2026, 6, 31) as Date | string },
        { id: 3, date: new Date(2026, 7, 1) as Date | string },
        { id: 4, date: "not-a-date" as Date | string },
      ],
      (item) => item.date,
      "MONTH",
      reference,
    );
    expect(groups.map((group) => group.items.map((item) => item.id))).toEqual([[1, 2], [3], [4]]);
    expect(groups.at(-1)?.key).toBe("unknown-date");
  });

  it("orders current groups first, future ascending and past descending", () => {
    const groups = groupByCalendarTime(
      [
        { id: "far-future", date: new Date(2026, 7, 15) },
        { id: "old-past", date: new Date(2026, 6, 19) },
        { id: "today", date: new Date(2026, 6, 26, 8) },
        { id: "near-future", date: new Date(2026, 6, 27) },
        { id: "recent-past", date: new Date(2026, 6, 25) },
      ],
      (item) => item.date,
      "DAY",
      reference,
    );

    expect(groups.flatMap((group) => group.items.map((item) => item.id))).toEqual([
      "today",
      "near-future",
      "far-future",
      "recent-past",
      "old-past",
    ]);
  });
});
