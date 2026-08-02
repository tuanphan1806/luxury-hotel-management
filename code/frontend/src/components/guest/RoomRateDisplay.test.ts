import { describe, expect, it } from "vitest";
import {
  compactStayRate,
  comparablePublicRoomPrice,
  publicRateAmount,
} from "./RoomRateDisplay";

describe("public room-rate normalization", () => {
  it("does not turn absent API rates into a zero-price offer", () => {
    expect(publicRateAmount(null)).toBeUndefined();
    expect(publicRateAmount(undefined)).toBeUndefined();
    expect(publicRateAmount("")).toBeUndefined();
  });

  it("keeps valid numeric rates for display and sorting", () => {
    expect(publicRateAmount("170000.00")).toBe(170000);
    expect(comparablePublicRoomPrice({ overnightPrice: 170000 })).toBe(170000);
  });

  it("uses the authoritative selected-stay estimate only on reservation cards", () => {
    const rate = {
      overnightPrice: 170000,
      estimatedPricePerRoom: 70000,
      estimatedPackage: "HOURLY" as const,
      totalHours: 2,
    };

    expect(compactStayRate(rate, "published")).toEqual({
      amount: 170000,
      packageCode: "OVERNIGHT",
      totalHours: undefined,
    });
    expect(compactStayRate(rate, "stay-estimate")).toEqual({
      amount: 70000,
      packageCode: "HOURLY",
      totalHours: 2,
    });
  });
});
