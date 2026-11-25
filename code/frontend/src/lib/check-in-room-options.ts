interface RoomChoice { id: number; roomTypeId: number; status: string; cleaningStatus: string }
interface RoomSelection { roomTypeId: number; roomId: string }

export function suggestReadyRooms<T extends RoomSelection>(drafts: T[], rooms: RoomChoice[]): T[] {
  const ready = rooms.filter(room => room.status === "AVAILABLE" && room.cleaningStatus === "CLEAN");
  const used = new Set<number>();
  const selections = drafts.map(draft => {
    const selected = ready.find(room => room.id === Number(draft.roomId) && room.roomTypeId === draft.roomTypeId);
    if (!selected || used.has(selected.id)) return { ...draft, roomId: "" };
    used.add(selected.id);
    return draft;
  });
  return selections.map(draft => {
    if (draft.roomId) return draft;
    const candidate = ready.find(room => room.roomTypeId === draft.roomTypeId && !used.has(room.id));
    if (!candidate) return draft;
    used.add(candidate.id);
    return { ...draft, roomId: String(candidate.id) };
  });
}
