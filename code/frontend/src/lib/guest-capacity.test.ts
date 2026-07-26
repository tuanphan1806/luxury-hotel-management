import { describe, expect, it } from "vitest";
import {
  calculateSelectedGuestCapacity,
  minimumRoomsForGuests,
  normalizeGuestCapacity,
} from "./guest-capacity";

describe("guest capacity helpers", () => {
  it("normalizes missing or invalid room capacity to the backend fallback", () => {
    expect(normalizeGuestCapacity(undefined)).toBe(2);
    expect(normalizeGuestCapacity(0)).toBe(2);
    expect(normalizeGuestCapacity(3)).toBe(3);
  });

  it("adds capacity across multiple room types and quantities", () => {
    expect(calculateSelectedGuestCapacity([
      { quantity: 2, maxGuestsPerRoom: 2 },
      { quantity: 1, maxGuestsPerRoom: 3 },
    ])).toBe(7);
  });

  it("calculates the minimum number of rooms needed for a party", () => {
    expect(minimumRoomsForGuests(5, 2)).toBe(3);
    expect(minimumRoomsForGuests(0, 2)).toBe(0);
  });
});
