import { describe, expect, it } from "vitest";
import { isStayWithinMaximum, MAX_STAY_DAYS } from "./stay-window";

describe("isStayWithinMaximum", () => {
  const checkIn = "2026-07-28T14:00:00";

  it("accepts the exact configured maximum", () => {
    const checkOut = new Date(checkIn);
    checkOut.setUTCDate(checkOut.getUTCDate() + MAX_STAY_DAYS);

    expect(isStayWithinMaximum(checkIn, checkOut)).toBe(true);
  });

  it("rejects a stay beyond the configured maximum", () => {
    const checkOut = new Date(checkIn);
    checkOut.setUTCDate(checkOut.getUTCDate() + MAX_STAY_DAYS);
    checkOut.setUTCMinutes(checkOut.getUTCMinutes() + 1);

    expect(isStayWithinMaximum(checkIn, checkOut)).toBe(false);
  });

  it("rejects invalid and reversed windows", () => {
    expect(isStayWithinMaximum("invalid", checkIn)).toBe(false);
    expect(isStayWithinMaximum(checkIn, checkIn)).toBe(false);
  });
});
