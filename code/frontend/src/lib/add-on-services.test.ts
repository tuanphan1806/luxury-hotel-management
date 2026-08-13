import { describe, expect, it } from "vitest";

import {
  calculateAddOnLineTotal,
  chargeableNights,
  isOperationalServiceQueueReservation,
  type AddOnServiceItem,
} from "./add-on-services";

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

describe("package-cycle add-on pricing", () => {
  const service = {
    id: 1,
    code: "EXTRA_ROLLAWAY_BED",
    name: "Giường phụ",
    category: "AMENITY",
    price: 200_000,
    pricingUnit: "PER_PACKAGE_CYCLE",
    bookingEnabled: true,
    inStayEnabled: true,
    active: true,
    sortOrder: 1,
  } satisfies AddOnServiceItem;

  it("uses the room-pricing package cycle count", () => {
    expect(
      calculateAddOnLineTotal(
        service,
        { quantity: 2, notes: "" },
        4,
        3,
      ),
    ).toBe(1_200_000);
  });

  it("keeps PER_NIGHT as a historical display/calculation alias", () => {
    expect(
      calculateAddOnLineTotal(
        { ...service, pricingUnit: "PER_NIGHT" },
        { quantity: 1, notes: "" },
        2,
        2,
      ),
    ).toBe(400_000);
  });
});

describe("operational service queue", () => {
  it.each(["CONFIRMED", "CHECKED_IN"])(
    "includes pending services for active reservation status %s",
    (reservationStatus) => {
      expect(isOperationalServiceQueueReservation(
        reservationStatus,
        [{ status: "CONFIRMED" }],
      )).toBe(true);
    },
  );

  it.each(["CANCELLED", "NO_SHOW", "CHECKED_OUT", "DRAFT"])(
    "excludes terminal or non-operational reservation status %s",
    (reservationStatus) => {
      expect(isOperationalServiceQueueReservation(
        reservationStatus,
        [{ status: "CONFIRMED" }],
      )).toBe(false);
    },
  );

  it.each(["FULFILLED", "CANCELLED"] as const)(
    "excludes completed service status %s",
    (serviceStatus) => {
      expect(isOperationalServiceQueueReservation(
        "CHECKED_IN",
        [{ status: serviceStatus }],
      )).toBe(false);
    },
  );
});
