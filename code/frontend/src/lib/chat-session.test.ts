import { describe, expect, it } from "vitest";
import {
  buildChatHistory,
  CHAT_SESSION_STORAGE_KEY,
  clearChatSession,
  loadChatSession,
  MAX_CHAT_HISTORY_TURNS,
  saveChatSession,
  type StoredChatMessage,
} from "@/lib/chat-session";

function message(index: number): StoredChatMessage {
  return {
    id: `message-${index}`,
    role: index % 2 ? "user" : "bot",
    content: `message ${index}`,
    timestamp: null,
  };
}

describe("chat session", () => {
  it("sends only the latest bounded conversation turns", () => {
    const history = buildChatHistory([
      { ...message(-1), id: "welcome" },
      ...Array.from({ length: 20 }, (_, index) => message(index)),
    ]);

    expect(history).toHaveLength(MAX_CHAT_HISTORY_TURNS);
    expect(history[0]?.content).toBe("message 8");
    expect(history.at(-1)?.content).toBe("message 19");
  });

  it("persists structured booking state without concatenating questions", () => {
    const values = new Map<string, string>();
    const storage = {
      get length() { return values.size; },
      clear: () => values.clear(),
      getItem: (key: string) => values.get(key) ?? null,
      key: (index: number) => [...values.keys()][index] ?? null,
      setItem: (key: string, value: string) => { values.set(key, value); },
      removeItem: (key: string) => { values.delete(key); },
    } as Storage;

    saveChatSession(storage, {
      conversationId: "conversation-1",
      messages: [message(1)],
      pendingBookingState: {
        context: "Đặt 1 phòng Deluxe",
        adults: 2,
        children: 1,
        pendingRoomTypeIds: [4, 4, -1],
      },
    });

    expect(loadChatSession(storage)).toMatchObject({
      conversationId: "conversation-1",
      pendingBookingState: {
        context: "Đặt 1 phòng Deluxe",
        adults: 2,
        children: 1,
        pendingRoomTypeIds: [4],
      },
    });

    storage.setItem(CHAT_SESSION_STORAGE_KEY, JSON.stringify({
      conversationId: "expired",
      messages: [message(2)],
      updatedAt: 1,
    }));
    expect(loadChatSession(storage)).toBeNull();
  });

  it("keeps chat usable when browser storage rejects writes", () => {
    const storage = {
      setItem: () => { throw new DOMException("quota", "QuotaExceededError"); },
    } as unknown as Storage;

    expect(() => saveChatSession(storage, {
      conversationId: "conversation-2",
      messages: [message(1)],
      pendingBookingState: null,
    })).not.toThrow();
  });

  it("redacts sensitive content before persistence and can clear the conversation", () => {
    const values = new Map<string, string>();
    const storage = {
      get length() { return values.size; },
      clear: () => values.clear(),
      getItem: (key: string) => values.get(key) ?? null,
      key: (index: number) => [...values.keys()][index] ?? null,
      setItem: (key: string, value: string) => { values.set(key, value); },
      removeItem: (key: string) => { values.delete(key); },
    } as Storage;

    saveChatSession(storage, {
      conversationId: "conversation-private",
      messages: [{
        ...message(1),
        content: "Email guest@example.com, phone 0901234567, CCCD: 012345678901",
      }],
      pendingBookingState: {
        context: "Liên hệ guest@example.com",
        adults: 2,
      },
    });

    const persisted = values.get(CHAT_SESSION_STORAGE_KEY) ?? "";
    expect(persisted).not.toContain("guest@example.com");
    expect(persisted).not.toContain("0901234567");
    expect(persisted).not.toContain("012345678901");
    expect(loadChatSession(storage)?.messages[0]?.content).toContain("[email]");

    clearChatSession(storage);
    expect(loadChatSession(storage)).toBeNull();
  });

  it("keeps only the allow-listed internal link action across a reload", () => {
    const values = new Map<string, string>();
    const storage = {
      get length() { return values.size; },
      clear: () => values.clear(),
      getItem: (key: string) => values.get(key) ?? null,
      key: (index: number) => [...values.keys()][index] ?? null,
      setItem: (key: string, value: string) => { values.set(key, value); },
      removeItem: (key: string) => { values.delete(key); },
    } as Storage;

    saveChatSession(storage, {
      conversationId: "conversation-link",
      messages: [{
        ...message(2),
        action: "OPEN_MY_BOOKINGS",
        payload: { href: "/my-bookings" },
      }],
    });
    expect(loadChatSession(storage)?.messages[0]?.payload).toEqual({ href: "/my-bookings" });

    saveChatSession(storage, {
      conversationId: "conversation-external-link",
      messages: [{
        ...message(2),
        action: "OPEN_MY_BOOKINGS",
        payload: { href: "https://malicious.example" },
      }],
    });
    expect(loadChatSession(storage)?.messages[0]?.payload).toBeUndefined();
  });
});
