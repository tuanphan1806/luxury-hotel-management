export const DEFAULT_GUESTS_PER_ROOM = 2;

export interface GuestCapacitySelection {
  quantity: number;
  maxGuestsPerRoom?: number | null;
}

export interface GuestAllocationSelection extends GuestCapacitySelection {
  roomTypeId: number;
  includedGuestsPerRoom?: number | null;
  extraGuestPrice?: number | null;
}

export function normalizeGuestCapacity(
  value: unknown,
  fallback = DEFAULT_GUESTS_PER_ROOM,
): number {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 1) {
    return Math.max(1, Math.trunc(fallback) || DEFAULT_GUESTS_PER_ROOM);
  }
  return Math.max(1, Math.trunc(parsed));
}

export function calculateSelectedGuestCapacity(
  selections: GuestCapacitySelection[],
): number {
  return selections.reduce((total, selection) => {
    const quantity = Math.max(0, Math.trunc(Number(selection.quantity) || 0));
    return total + quantity * normalizeGuestCapacity(selection.maxGuestsPerRoom);
  }, 0);
}

export function minimumRoomsForGuests(
  guestCount: number,
  maxGuestsPerRoom: number,
): number {
  const guests = Math.max(0, Math.trunc(Number(guestCount) || 0));
  if (guests === 0) return 0;
  return Math.ceil(guests / normalizeGuestCapacity(maxGuestsPerRoom));
}

export function allocateGuestsToRoomTypes(
  rooms: GuestAllocationSelection[],
  totalGuests: number,
): Record<number, number> {
  const minimumGuests = rooms.reduce(
    (total, room) => total + Math.max(0, Math.trunc(room.quantity)),
    0,
  );
  if (rooms.length === 0 || totalGuests < minimumGuests) {
    return {};
  }

  const allocation: Record<number, number> = Object.fromEntries(
    rooms.map((room) => [
      room.roomTypeId,
      Math.max(0, Math.trunc(room.quantity)),
    ]),
  );
  let remaining = Math.trunc(totalGuests) - minimumGuests;

  // Fill every room type's included occupancy before consuming any paid
  // overflow slot. This keeps the suggested allocation financially neutral:
  // a later room type must not be left at one guest while an earlier type is
  // already charging an extra guest.
  for (const room of rooms) {
    if (remaining <= 0) break;
    const quantity = Math.max(0, Math.trunc(room.quantity));
    const maxPerRoom = normalizeGuestCapacity(room.maxGuestsPerRoom);
    const includedPerRoom = Math.min(
      maxPerRoom,
      normalizeGuestCapacity(room.includedGuestsPerRoom, maxPerRoom),
    );
    const includedCapacity = quantity * includedPerRoom;
    const extra = Math.min(
      remaining,
      Math.max(0, includedCapacity - allocation[room.roomTypeId]),
    );
    allocation[room.roomTypeId] += extra;
    remaining -= extra;
  }

  // Only guests left after the first pass use the physical overflow capacity
  // and may therefore incur the configured extra-guest charge. Prefer the
  // least expensive valid surcharge while keeping the user's selection order
  // as a stable tie-breaker.
  const overflowRooms = rooms
    .map((room, selectionIndex) => ({ room, selectionIndex }))
    .sort((left, right) => {
      const leftPrice = Number(left.room.extraGuestPrice);
      const rightPrice = Number(right.room.extraGuestPrice);
      const safeLeftPrice = Number.isFinite(leftPrice) && leftPrice >= 0
        ? leftPrice : Number.POSITIVE_INFINITY;
      const safeRightPrice = Number.isFinite(rightPrice) && rightPrice >= 0
        ? rightPrice : Number.POSITIVE_INFINITY;
      return safeLeftPrice - safeRightPrice
        || left.selectionIndex - right.selectionIndex;
    });
  for (const { room } of overflowRooms) {
    if (remaining <= 0) break;
    const quantity = Math.max(0, Math.trunc(room.quantity));
    const capacity = quantity * normalizeGuestCapacity(room.maxGuestsPerRoom);
    const extra = Math.min(
      remaining,
      Math.max(0, capacity - allocation[room.roomTypeId]),
    );
    allocation[room.roomTypeId] += extra;
    remaining -= extra;
  }

  return remaining === 0 ? allocation : {};
}
