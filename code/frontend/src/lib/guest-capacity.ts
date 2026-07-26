export const DEFAULT_GUESTS_PER_ROOM = 2;

export interface GuestCapacitySelection {
  quantity: number;
  maxGuestsPerRoom?: number | null;
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
