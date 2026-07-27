import { describe, expect, it } from "vitest";

import { chargeableNights } from "./add-on-services";

describe("chargeableNights", () => {
  it.each([
    ["2026-08-01T12:00", "2026-08-02T12:00", 1],
    ["2026-08-01T12:00", "2026-08-02T12:15", 1],
    ["2026-08-01T12:00", "2026-08-02T12:16", 2],
    ["2026-08-01T12:00", "2026-08-03T12:00", 2],
    ["2026-08-01T20:00", "2026-08-04T04:00", 3],
  ])(
    "matches Pricing V2 package cycles for %s to %s",
    (checkIn, checkOut, expected) => {
      expect(chargeableNights(checkIn, checkOut)).toBe(expected);
    },
  );

  it("uses wall-clock time instead of browser timezone conversion", () => {
    expect(
      chargeableNights(
        "2026-03-08T01:30:00",
        "2026-03-09T01:45:00",
      ),
    ).toBe(1);
  });

  it("falls back safely for invalid windows", () => {
    expect(chargeableNights("", "")).toBe(1);
    expect(
      chargeableNights("2026-08-02T12:00", "2026-08-01T12:00"),
    ).toBe(1);
  });
});
