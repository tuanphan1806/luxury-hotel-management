export type RoomPhysicalStatus = "AVAILABLE" | "BOOKED" | "CHECKED_IN" | "MAINTENANCE";
export type RoomCleaningStatus = "CLEAN" | "DIRTY" | "IN_PROGRESS";

export type RoomOperationalState =
  | "READY"
  | "NEEDS_CLEANING"
  | "CLEANING"
  | "RESERVED"
  | "OCCUPIED"
  | "MAINTENANCE";

export interface RoomOperationalSource {
  status: RoomPhysicalStatus;
  cleaningStatus?: RoomCleaningStatus | null;
}

export interface RoomOperationalSummary {
  total: number;
  ready: number;
  needsCleaning: number;
  cleaning: number;
  reserved: number;
  occupied: number;
  maintenance: number;
  attention: number;
}

/**
 * Combines the physical room state and housekeeping state into the single
 * operational state staff need when reading the room board.
 *
 * The backend remains the source of truth for both independent fields. This
 * helper only prevents AVAILABLE + DIRTY/IN_PROGRESS from being presented as
 * a room that is ready for assignment.
 */
export function getRoomOperationalState(room: RoomOperationalSource): RoomOperationalState {
  if (room.status === "MAINTENANCE") return "MAINTENANCE";
  if (room.status === "CHECKED_IN") return "OCCUPIED";
  if (room.status === "BOOKED") return "RESERVED";
  if (room.cleaningStatus === "IN_PROGRESS") return "CLEANING";
  if (room.cleaningStatus === "CLEAN") return "READY";

  // Legacy rows can still have a null cleaning status because the original
  // PostgreSQL baseline allowed it. Assignment already requires CLEAN, so an
  // unknown value must never be presented as ready on the room board.
  return "NEEDS_CLEANING";
}

export function isRoomReady(room: RoomOperationalSource): boolean {
  return getRoomOperationalState(room) === "READY";
}

export function summarizeRoomOperations(rooms: RoomOperationalSource[]): RoomOperationalSummary {
  const summary: RoomOperationalSummary = {
    total: rooms.length,
    ready: 0,
    needsCleaning: 0,
    cleaning: 0,
    reserved: 0,
    occupied: 0,
    maintenance: 0,
    attention: 0,
  };

  for (const room of rooms) {
    const state = getRoomOperationalState(room);
    switch (state) {
      case "READY":
        summary.ready += 1;
        break;
      case "NEEDS_CLEANING":
        summary.needsCleaning += 1;
        summary.attention += 1;
        break;
      case "CLEANING":
        summary.cleaning += 1;
        summary.attention += 1;
        break;
      case "RESERVED":
        summary.reserved += 1;
        break;
      case "OCCUPIED":
        summary.occupied += 1;
        break;
      case "MAINTENANCE":
        summary.maintenance += 1;
        summary.attention += 1;
        break;
    }
  }

  return summary;
}
