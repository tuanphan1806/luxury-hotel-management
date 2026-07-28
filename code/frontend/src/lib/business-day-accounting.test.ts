import { describe, expect, it } from "vitest";
import { blockerLabel, defaultBusinessDate } from "./business-day-accounting";

describe("business day accounting helpers", () => {
  it("defaults to the previous hotel business date", () => {
    expect(defaultBusinessDate(new Date("2026-07-28T14:00:00Z"))).toBe("2026-07-27");
  });

  it("renders blocker details for operators", () => {
    expect(blockerLabel("OPEN_CASHIER_SHIFTS:2")).toContain("2");
    expect(blockerLabel("UNRECONCILED_FUNDS:100000")).toContain("100.000");
    expect(blockerLabel("ACCOUNTING_GO_LIVE_DATE_NOT_CONFIGURED")).toContain("ngày bắt đầu");
    expect(blockerLabel("BEFORE_ACCOUNTING_GO_LIVE_DATE:2026-07-29")).toContain("2026-07-29");
  });
});
