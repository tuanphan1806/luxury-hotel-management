import type { ChatBookingPayload, ChatBookingState } from "@/lib/chat-booking";

export const CHAT_SESSION_STORAGE_KEY = "luxury-hotel:chat-session:v2";
export const MAX_STORED_CHAT_MESSAGES = 30;
export const MAX_CHAT_HISTORY_TURNS = 12;
export const CHAT_SESSION_TTL_MS = 30 * 60 * 1000;
export const MAX_CHAT_INPUT_LENGTH = 500;

const EMAIL_PATTERN = /\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi;
const PHONE_PATTERN = /(?<!\d)(?:\+?84|0)[1-9](?:[ .-]?\d){8,10}(?!\d)/g;
const BOOKING_CODE_PATTERN = /\b(?:RES|BOOKING|REFUND)-[A-Z0-9-]{4,}\b/gi;
const IDENTITY_PATTERN = /\b(?:CCCD|CMND|CMT|passport|hộ chiếu|ho chieu|identity card)\s*[:#-]?\s*[A-Z0-9]{5,20}\b/giu;
const PAYMENT_NUMBER_PATTERN = /(?<!\d)(?:\d[ .-]?){12,19}(?!\d)/g;
const LABELED_NAME_PATTERN = /\b(?:họ tên|ho ten|full name|tên tài khoản|ten tai khoan)\s*[:=-]\s*[\p{L}][\p{L} .'-]{1,80}(?=;|,|\n|$)/giu;
const LABELED_ADDRESS_PATTERN = /(?:địa chỉ|dia chi|address)\s*[:=-]\s*[^;\r\n]{3,160}/giu;
const LONG_NUMBER_PATTERN = /(?<!\d)\d{9,19}(?!\d)/g;

export type ChatRole = "user" | "bot";
export type ChatAction =
  | "CONTINUE_RESERVATION"
  | "CREATE_RESERVATION_CONFIRM"
  | "OPEN_MY_BOOKINGS";

export interface ChatActionPayload extends ChatBookingState {
  href?: string;
}

export interface StoredChatMessage {
  id: string;
  role: ChatRole;
  content: string;
  timestamp: string | null;
  action?: ChatAction;
  payload?: ChatActionPayload;
}

export interface StoredChatSession {
  conversationId: string;
  messages: StoredChatMessage[];
  updatedAt?: number;
  pendingBookingState?: ChatBookingState | null;
  /** Compatibility with sessions written before structured booking state. */
  pendingBookingContext?: string | null;
  /** Compatibility with sessions written before structured booking state. */
  pendingReservationPayload?: ChatBookingPayload | null;
}

export function createConversationId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return `chat_${Date.now()}_${Math.random().toString(36).slice(2)}`;
}

export function buildChatHistory(messages: StoredChatMessage[]) {
  return messages
    .filter((message) => message.id !== "welcome" && message.content.trim())
    .slice(-MAX_CHAT_HISTORY_TURNS)
    .map((message) => ({
      role: message.role === "bot" ? "assistant" as const : "user" as const,
      content: message.content.trim().slice(0, 500),
    }));
}

export function loadChatSession(storage: Storage | undefined): StoredChatSession | null {
  if (!storage) return null;
  try {
    const raw = storage.getItem(CHAT_SESSION_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<StoredChatSession>;
    if (!parsed.conversationId || !Array.isArray(parsed.messages)) return null;
    if (typeof parsed.updatedAt === "number"
      && Date.now() - parsed.updatedAt > CHAT_SESSION_TTL_MS) {
      storage.removeItem(CHAT_SESSION_STORAGE_KEY);
      return null;
    }
    const messages = parsed.messages
      .filter((message): message is StoredChatMessage => Boolean(
        message
        && typeof message.id === "string"
        && (message.role === "user" || message.role === "bot")
        && typeof message.content === "string",
      ))
      .slice(-MAX_STORED_CHAT_MESSAGES)
      .map(sanitizeStoredMessage);
    const legacyContext = typeof parsed.pendingBookingContext === "string"
      ? parsed.pendingBookingContext.slice(0, 1500)
      : null;
    const pendingBookingState = sanitizeStoredBookingState(
      parsed.pendingBookingState
      ?? parsed.pendingReservationPayload
      ?? (legacyContext ? { context: legacyContext } : null),
    );
    return {
      conversationId: parsed.conversationId,
      messages,
      pendingBookingState,
    };
  } catch {
    storage.removeItem(CHAT_SESSION_STORAGE_KEY);
    return null;
  }
}

export function saveChatSession(storage: Storage | undefined, session: StoredChatSession) {
  if (!storage) return;
  try {
    storage.setItem(CHAT_SESSION_STORAGE_KEY, JSON.stringify({
      conversationId: session.conversationId,
      messages: session.messages
        .slice(-MAX_STORED_CHAT_MESSAGES)
        .map(sanitizeStoredMessage),
      pendingBookingState: sanitizeStoredBookingState(session.pendingBookingState),
      updatedAt: Date.now(),
    }));
  } catch {
    // Chat remains usable when sessionStorage is disabled or has reached quota.
  }
}

export function clearChatSession(storage: Storage | undefined) {
  if (!storage) return;
  try {
    storage.removeItem(CHAT_SESSION_STORAGE_KEY);
  } catch {
    // Clearing history is best-effort when browser storage is unavailable.
  }
}

export function redactChatContentForStorage(value: string): string {
  return value
    .slice(0, 4000)
    .replace(EMAIL_PATTERN, "[email]")
    .replace(BOOKING_CODE_PATTERN, "[booking-code]")
    .replace(IDENTITY_PATTERN, "[identity-document]")
    .replace(PAYMENT_NUMBER_PATTERN, "[payment-number]")
    .replace(PHONE_PATTERN, "[phone]")
    .replace(LABELED_NAME_PATTERN, "[name]")
    .replace(LABELED_ADDRESS_PATTERN, "[address]")
    .replace(LONG_NUMBER_PATTERN, "[sensitive-number]");
}

function sanitizeStoredMessage(message: StoredChatMessage): StoredChatMessage {
  const allowedAction: ChatAction | undefined = [
    "CONTINUE_RESERVATION",
    "CREATE_RESERVATION_CONFIRM",
    "OPEN_MY_BOOKINGS",
  ].includes(message.action as ChatAction)
    ? message.action
    : undefined;
  return {
    id: message.id.slice(0, 120),
    role: message.role,
    content: redactChatContentForStorage(message.content),
    timestamp: typeof message.timestamp === "string" ? message.timestamp.slice(0, 40) : null,
    action: allowedAction,
    payload: sanitizeStoredActionPayload(allowedAction, message.payload),
  };
}

function sanitizeStoredActionPayload(
  action: ChatAction | undefined,
  value: ChatActionPayload | undefined,
): ChatActionPayload | undefined {
  if (!action || !value) return undefined;
  if (action === "OPEN_MY_BOOKINGS") {
    return value.href === "/my-bookings" ? { href: value.href } : undefined;
  }
  return sanitizeStoredBookingState(value) ?? undefined;
}

function sanitizeStoredBookingState(value: unknown): ChatBookingState | null {
  if (!value || typeof value !== "object") return null;
  const candidate = value as ChatBookingState;
  const roomTypes = Array.isArray(candidate.roomTypes)
    ? candidate.roomTypes
      .map((line) => ({
        roomTypeId: Number(line?.roomTypeId),
        quantity: Number(line?.quantity),
      }))
      .filter((line) => (
        Number.isInteger(line.roomTypeId)
        && line.roomTypeId > 0
        && Number.isInteger(line.quantity)
        && line.quantity > 0
      ))
      .slice(0, 12)
    : undefined;
  const pendingRoomTypeIds = Array.isArray(candidate.pendingRoomTypeIds)
    ? [...new Set(candidate.pendingRoomTypeIds
      .map(Number)
      .filter((id) => Number.isInteger(id) && id > 0))]
      .slice(0, 12)
    : undefined;

  const state: ChatBookingState = {
    checkIn: typeof candidate.checkIn === "string" ? candidate.checkIn : undefined,
    checkOut: typeof candidate.checkOut === "string" ? candidate.checkOut : undefined,
    guestCount: validInteger(candidate.guestCount, 1),
    adults: validInteger(candidate.adults, 1),
    children: validInteger(candidate.children, 0),
    note: typeof candidate.note === "string"
      ? redactChatContentForStorage(candidate.note.slice(0, 500))
      : undefined,
    context: typeof candidate.context === "string"
      ? redactChatContentForStorage(candidate.context.slice(0, 1500))
      : undefined,
    roomTypes,
    pendingRoomTypeIds,
  };
  return Object.values(state).some((entry) => entry !== undefined) ? state : null;
}

function validInteger(value: unknown, minimum: number): number | undefined {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed >= minimum ? parsed : undefined;
}
