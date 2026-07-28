import { describe, expect, it } from "vitest";
import {
  chartPoints,
  monthToDatePreset,
  statisticsPreset,
  toDateInputValue,
} from "./business-statistics";

describe("business statistics helpers", () => {
  it("builds an inclusive rolling period", () => {
    expect(statisticsPreset(7, new Date(2026, 6, 28))).toEqual({
      from: "2026-07-22",
      to: "2026-07-28",
    });
  });

  it("builds month-to-date without UTC date drift", () => {
    expect(monthToDatePreset(new Date(2026, 6, 28, 23, 45))).toEqual({
      from: "2026-07-01",
      to: "2026-07-28",
    });
    expect(toDateInputValue(new Date(2026, 0, 5))).toBe("2026-01-05");
  });

  it("keeps zero and negative values inside the chart", () => {
    const points = chartPoints([-10, 0, 20], 300, 120, 10);
    expect(points).toHaveLength(3);
    expect(points[0].x).toBe(10);
    expect(points[2].x).toBe(290);
    expect(points.every((point) => point.y >= 10 && point.y <= 110)).toBe(true);
  });
});
