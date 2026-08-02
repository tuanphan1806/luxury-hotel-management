import { describe, expect, it } from "vitest";
import { getStaySearchValidationIssue } from "./reservation-search-validation";

const now = new Date("2026-08-02T08:00:00+07:00");

describe("getStaySearchValidationIssue", () => {
  it("rejects checkout before check-in without replacing either value", () => {
    expect(getStaySearchValidationIssue(
      "2026-08-03T14:00",
      "2026-08-03T12:00",
      2,
      now,
    )).toBe("CHECK_OUT_NOT_AFTER_CHECK_IN");
  });

  it("rejects a past check-in", () => {
    expect(getStaySearchValidationIssue(
      "2026-08-01T14:00",
      "2026-08-03T12:00",
      2,
      now,
    )).toBe("CHECK_IN_NOT_FUTURE");
  });

  it("accepts a valid future stay", () => {
    expect(getStaySearchValidationIssue(
      "2026-08-03T14:00",
      "2026-08-04T12:00",
      2,
      now,
    )).toBeNull();
  });
});
