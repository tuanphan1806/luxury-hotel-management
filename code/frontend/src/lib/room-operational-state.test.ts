import { describe, expect, it } from "vitest";
import {
  getRoomOperationalState,
  isRoomReady,
  summarizeRoomOperations,
  type RoomOperationalSource,
} from "./room-operational-state";

describe("room operational state", () => {
  it("only presents an available and clean room as ready", () => {
    expect(getRoomOperationalState({ status: "AVAILABLE", cleaningStatus: "CLEAN" })).toBe("READY");
    expect(getRoomOperationalState({ status: "AVAILABLE", cleaningStatus: "DIRTY" })).toBe("NEEDS_CLEANING");
    expect(getRoomOperationalState({ status: "AVAILABLE", cleaningStatus: "IN_PROGRESS" })).toBe("CLEANING");
    expect(getRoomOperationalState({ status: "AVAILABLE", cleaningStatus: null })).toBe("NEEDS_CLEANING");
    expect(isRoomReady({ status: "AVAILABLE", cleaningStatus: "DIRTY" })).toBe(false);
    expect(isRoomReady({ status: "AVAILABLE" })).toBe(false);
  });

  it("keeps reservation, stay and maintenance states above housekeeping presentation", () => {
    expect(getRoomOperationalState({ status: "BOOKED", cleaningStatus: "DIRTY" })).toBe("RESERVED");
    expect(getRoomOperationalState({ status: "CHECKED_IN", cleaningStatus: "DIRTY" })).toBe("OCCUPIED");
    expect(getRoomOperationalState({ status: "MAINTENANCE", cleaningStatus: "CLEAN" })).toBe("MAINTENANCE");
  });

  it("summarizes the room board without counting dirty rooms as ready", () => {
    const rooms: RoomOperationalSource[] = [
      { status: "AVAILABLE", cleaningStatus: "CLEAN" },
      { status: "AVAILABLE", cleaningStatus: "DIRTY" },
      { status: "AVAILABLE", cleaningStatus: "IN_PROGRESS" },
      { status: "BOOKED", cleaningStatus: "CLEAN" },
      { status: "CHECKED_IN", cleaningStatus: "CLEAN" },
      { status: "MAINTENANCE", cleaningStatus: "DIRTY" },
    ];

    expect(summarizeRoomOperations(rooms)).toEqual({
      total: 6,
      ready: 1,
      needsCleaning: 1,
      cleaning: 1,
      reserved: 1,
      occupied: 1,
      maintenance: 1,
      attention: 3,
    });
  });
});
