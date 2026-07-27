export interface ChatBookingPayload {
  checkIn: string;
  checkOut: string;
  guestCount?: number;
  note?: string;
  roomTypes: Array<{
    roomTypeId: number;
    quantity: number;
  }>;
}

/**
 * Converts the chatbot's availability result into the canonical booking-page
 * contract. The booking page remains responsible for guest allocation,
 * obtaining a server-authoritative Pricing V2 quote and collecting customer
 * confirmation before a reservation is created.
 */
export function buildChatBookingUrl(payload: ChatBookingPayload): string {
  if (!payload || !Array.isArray(payload.roomTypes) || payload.roomTypes.length === 0) {
    throw new Error("Chatbot chưa có hạng phòng hợp lệ để tiếp tục đặt phòng");
  }

  const checkIn = String(payload.checkIn || "").trim();
  const checkOut = String(payload.checkOut || "").trim();
  const checkInInstant = new Date(checkIn).getTime();
  const checkOutInstant = new Date(checkOut).getTime();
  if (
    !checkIn
    || !checkOut
    || Number.isNaN(checkInInstant)
    || Number.isNaN(checkOutInstant)
    || checkOutInstant <= checkInInstant
  ) {
    throw new Error("Thời gian lưu trú từ chatbot không hợp lệ");
  }

  const seenRoomTypes = new Set<number>();
  const roomTypes = payload.roomTypes.map((line) => {
    const roomTypeId = Number(line?.roomTypeId);
    const quantity = Number(line?.quantity);
    if (
      !Number.isInteger(roomTypeId)
      || roomTypeId < 1
      || !Number.isInteger(quantity)
      || quantity < 1
      || seenRoomTypes.has(roomTypeId)
    ) {
      throw new Error("Hạng phòng hoặc số lượng từ chatbot không hợp lệ");
    }
    seenRoomTypes.add(roomTypeId);
    return `${roomTypeId}:${quantity}`;
  });

  const minimumGuests = payload.roomTypes.reduce(
    (sum, line) => sum + Number(line.quantity),
    0,
  );
  const guestCount = Number(payload.guestCount ?? minimumGuests);
  if (!Number.isInteger(guestCount) || guestCount < minimumGuests) {
    throw new Error(
      `Số khách phải ít nhất bằng số phòng đã chọn (${minimumGuests})`,
    );
  }

  const params = new URLSearchParams({
    roomTypes: roomTypes.join(","),
    checkIn,
    checkOut,
    adults: String(guestCount),
    children: "0",
    source: "chatbot",
  });
  return `/booking?${params.toString()}`;
}
