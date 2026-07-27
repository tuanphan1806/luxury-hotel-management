import { describe, expect, it } from "vitest";
import {
  allocateGuestsToRoomTypes,
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

  it("allocates at least one guest to every selected physical room", () => {
    expect(allocateGuestsToRoomTypes([
      { roomTypeId: 1, quantity: 2, maxGuestsPerRoom: 2 },
      { roomTypeId: 2, quantity: 1, maxGuestsPerRoom: 3 },
    ], 5)).toEqual({ 1: 4, 2: 1 });
  });

  it("rejects allocations that would leave a selected room without a guest", () => {
    expect(allocateGuestsToRoomTypes([
      { roomTypeId: 1, quantity: 2, maxGuestsPerRoom: 2 },
    ], 1)).toEqual({});
  });

  it("rejects allocations above the selected physical capacity", () => {
    expect(allocateGuestsToRoomTypes([
      { roomTypeId: 1, quantity: 1, maxGuestsPerRoom: 2 },
    ], 3)).toEqual({});
  });
});
