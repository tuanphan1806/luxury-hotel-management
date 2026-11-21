import { describe, expect, it } from "vitest";
import { projectRoomPreviews } from "./room-preview";

describe("public room descriptions", () => {
  it("retains the public card fields and excludes inactive rooms and commercial data", () => {
    const rooms = projectRoomPreviews({ success: true, data: [
      { id: 1, typeName: "Suite", description: "Garden", imageUrls: ["/suite.webp"], maxGuests: 4,
        overnightPrice: 700000, availableRooms: 9, averageRating: 4.5, facilities: [{ facilityName: "Spa", cost: 100 }] },
      { id: 2, typeName: "Hidden", active: false },
    ] });
    expect(rooms).toHaveLength(1);
    expect(rooms[0]).toMatchObject({ id: 1, description: "Garden", imageUrl: "/suite.webp", maxGuests: 4 });
    expect(rooms[0].facilities).toEqual([{ facilityName: "Spa", facilityNameEn: "" }]);
    for (const key of ["overnightPrice", "availableRooms", "averageRating"]) expect(rooms[0]).not.toHaveProperty(key);
  });

  it("accepts an empty active catalog and rejects failed, malformed or duplicate results", () => {
    expect(projectRoomPreviews({ data: [] })).toEqual([]);
    expect(projectRoomPreviews({ data: [{ id: 2, active: false }] })).toEqual([]);
    for (const bad of [null, { success: false, data: [] }, { data: [null] }, { data: [{ id: 1 }] },
      { data: [{ id: 1, typeName: "A" }, { id: 1, typeName: "B" }] }]) {
      expect(() => projectRoomPreviews(bad)).toThrow();
    }
  });
});
