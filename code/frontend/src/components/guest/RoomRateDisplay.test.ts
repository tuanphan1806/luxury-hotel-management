import { describe, expect, it } from "vitest";
import {
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
});
