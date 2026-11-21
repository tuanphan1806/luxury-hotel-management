export interface RoomPreview {
  id: number;
  typeName: string;
  typeNameEn?: string;
  description: string;
  descriptionEn?: string;
  imageUrl: string;
  maxGuests?: number;
  includedGuests?: number;
  facilities: { facilityName: string; facilityNameEn?: string }[];
}

const text = (value: unknown) => typeof value === "string" ? value.trim() : "";
const capacity = (value: unknown) => typeof value === "number" && Number.isSafeInteger(value) && value > 0 ? value : undefined;

// Only public descriptions belong in prerendered pages. Availability and rates
// have a different lifetime and must come from their respective endpoints.
export function projectRoomPreviews(payload: unknown): RoomPreview[] {
  if (!payload || typeof payload !== "object" || ("success" in payload && payload.success === false)
    || !("data" in payload) || !Array.isArray(payload.data)) throw new Error("Invalid room catalog");
  const seen = new Set<number>();
  const rooms: RoomPreview[] = [];
  for (const room of payload.data) {
    if (room?.active === false) continue;
    if (!room || !Number.isSafeInteger(room.id) || room.id <= 0 || !text(room.typeName) || seen.has(room.id)) {
      throw new Error("Invalid room in public catalog");
    }
    seen.add(room.id);
    rooms.push({
      id: room.id,
      typeName: text(room.typeName),
      typeNameEn: text(room.typeNameEn),
      description: text(room.description),
      descriptionEn: text(room.descriptionEn),
      imageUrl: text(room.imageUrls?.[0]) || text(room.imageUrl),
      maxGuests: capacity(room.maxGuests),
      includedGuests: capacity(room.includedGuests),
      facilities: Array.isArray(room.facilities) ? room.facilities.flatMap((facility: Record<string, unknown> | null) =>
        text(facility?.facilityName) ? [{ facilityName: text(facility?.facilityName), facilityNameEn: text(facility?.facilityNameEn) }] : []) : [],
    });
  }
  return rooms;
}
