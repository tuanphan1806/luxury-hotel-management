import { expect, it } from "vitest";
import { suggestReadyRooms } from "./check-in-room-options";

it("keeps valid choices and guests, replacing only with distinct clean rooms of the booked type", () => {
  const rooms = [
    { id: 10, roomTypeId: 1, status: "CHECKED_IN", cleaningStatus: "CLEAN" },
    { id: 11, roomTypeId: 1, status: "AVAILABLE", cleaningStatus: "CLEAN" },
    { id: 12, roomTypeId: 1, status: "AVAILABLE", cleaningStatus: "CLEAN" },
    { id: 13, roomTypeId: 2, status: "AVAILABLE", cleaningStatus: "DIRTY" },
  ];
  const drafts = [
    { roomId: "10", roomTypeId: 1, guests: ["An"] }, { roomId: "11", roomTypeId: 1, guests: ["Bình"] },
    { roomId: "11", roomTypeId: 1, guests: ["Chi"] }, { roomId: "", roomTypeId: 2, guests: ["Dũng"] },
  ];
  const result = suggestReadyRooms(drafts, rooms);
  expect(result.map(slot => slot.roomId)).toEqual(["12", "11", "", ""]);
  expect(result.map(slot => slot.guests)).toEqual(drafts.map(slot => slot.guests));
});
