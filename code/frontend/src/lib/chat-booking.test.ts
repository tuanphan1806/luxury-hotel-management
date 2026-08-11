import { describe, expect, it } from "vitest";

import { buildChatBookingUrl, isCompleteChatBookingState } from "./chat-booking";

describe("buildChatBookingUrl", () => {
  it("routes every chatbot selection through the canonical booking page", () => {
    const url = buildChatBookingUrl({
      checkIn: "2026-08-01T20:00:00",
      checkOut: "2026-08-02T08:00:00",
      guestCount: 4,
      roomTypes: [
        { roomTypeId: 2, quantity: 1 },
        { roomTypeId: 5, quantity: 2 },
      ],
    });

    const parsed = new URL(url, "https://luxury-hotel.example");
    expect(parsed.pathname).toBe("/booking");
    expect(parsed.searchParams.get("roomTypes")).toBe("2:1,5:2");
    expect(parsed.searchParams.get("checkIn")).toBe("2026-08-01T20:00:00");
    expect(parsed.searchParams.get("checkOut")).toBe("2026-08-02T08:00:00");
    expect(parsed.searchParams.get("adults")).toBe("4");
    expect(parsed.searchParams.get("children")).toBe("0");
    expect(parsed.searchParams.get("source")).toBe("chatbot");
  });

  it("defaults guest count to one guest per selected room", () => {
    const parsed = new URL(buildChatBookingUrl({
      checkIn: "2026-08-01T14:00:00",
      checkOut: "2026-08-01T18:00:00",
      roomTypes: [{ roomTypeId: 1, quantity: 3 }],
    }), "https://luxury-hotel.example");

    expect(parsed.searchParams.get("adults")).toBe("3");
  });

  it("preserves adults and children separately", () => {
    const parsed = new URL(buildChatBookingUrl({
      checkIn: "2026-08-01T14:00:00",
      checkOut: "2026-08-01T18:00:00",
      adults: 2,
      children: 1,
      guestCount: 3,
      roomTypes: [{ roomTypeId: 2, quantity: 1 }],
    }), "https://luxury-hotel.example");

    expect(parsed.searchParams.get("adults")).toBe("2");
    expect(parsed.searchParams.get("children")).toBe("1");
  });

  it("only treats a state with dates, selected rooms and guests as ready", () => {
    expect(isCompleteChatBookingState({ context: "Đặt Deluxe" })).toBe(false);
    expect(isCompleteChatBookingState({
      checkIn: "2026-08-01T14:00:00",
      checkOut: "2026-08-01T18:00:00",
      roomTypes: [{ roomTypeId: 2, quantity: 1 }],
    })).toBe(false);
    expect(isCompleteChatBookingState({
      checkIn: "2026-08-01T14:00:00",
      checkOut: "2026-08-01T18:00:00",
      adults: 2,
      roomTypes: [{ roomTypeId: 2, quantity: 1 }],
    })).toBe(true);
  });

  it("rejects duplicate room types and impossible guest totals", () => {
    expect(() => buildChatBookingUrl({
      checkIn: "2026-08-01T14:00:00",
      checkOut: "2026-08-01T18:00:00",
      guestCount: 1,
      roomTypes: [{ roomTypeId: 1, quantity: 2 }],
    })).toThrow("Số khách phải ít nhất bằng số phòng");

    expect(() => buildChatBookingUrl({
      checkIn: "2026-08-01T14:00:00",
      checkOut: "2026-08-01T18:00:00",
      guestCount: 2,
      roomTypes: [
        { roomTypeId: 1, quantity: 1 },
        { roomTypeId: 1, quantity: 1 },
      ],
    })).toThrow("Hạng phòng hoặc số lượng");
  });

  it("rejects an invalid stay window", () => {
    expect(() => buildChatBookingUrl({
      checkIn: "2026-08-02T08:00:00",
      checkOut: "2026-08-01T20:00:00",
      roomTypes: [{ roomTypeId: 1, quantity: 1 }],
    })).toThrow("Thời gian lưu trú");
  });
});
