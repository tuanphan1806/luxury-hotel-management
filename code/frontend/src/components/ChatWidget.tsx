"use client";

import React, { useState, useRef, useEffect, useCallback } from "react";
import axios from "axios";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { publicApiClient, type ApiErrorPayload } from "@/lib/api";
import {
  buildChatBookingUrl,
  isCompleteChatBookingState,
  type ChatBookingPayload,
  type ChatBookingState,
} from "@/lib/chat-booking";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import {
  buildChatHistory,
  clearChatSession,
  createConversationId,
  loadChatSession,
  MAX_CHAT_INPUT_LENGTH,
  MAX_STORED_CHAT_MESSAGES,
  saveChatSession,
  type ChatAction,
  type ChatActionPayload,
  type StoredChatMessage,
} from "@/lib/chat-session";

/**
 * ChatWidget - Floating chatbot widget tích hợp API /api/chat
 *
 * Hiển thị ở góc dưới bên phải màn hình dưới dạng bubble.
 * Khi click sẽ mở panel chat cho phép khách hỏi đáp về khách sạn.
 */

interface ChatApiResponse {
  answer?: string;
  action?: ChatAction;
  payload?: ChatActionPayload;
}

interface ChatWidgetProps {
  avoidMobileBookingBar?: boolean;
}

const INITIAL_BOT_MESSAGE: StoredChatMessage = {
  id: "welcome",
  role: "bot",
  content: "",
  // Giá trị ổn định cho SSR/hydration. Các tin nhắn phát sinh trên client vẫn
  // dùng thời gian thực tế; tin chào hiển thị nhãn "Bây giờ".
  timestamp: null,
};

export default function ChatWidget({ avoidMobileBookingBar = false }: ChatWidgetProps) {
  const router = useRouter();
  const { locale, localize } = useLanguage();
  const [isOpen, setIsOpen] = useState(false);
  const welcomeMessage = localize(
    "Xin chào! Tôi là trợ lý AI của Luxury Hotel. Tôi có thể hỗ trợ bạn về phòng, giá, tiện nghi và thông tin đặt phòng. Hãy hỏi tôi bất cứ điều gì! 🏨",
    "Hello! I am Luxury Hotel's virtual assistant. I can help with rooms, rates, facilities, and booking information. Ask me anything! 🏨",
  );
  const [messages, setMessages] = useState<StoredChatMessage[]>([
    { ...INITIAL_BOT_MESSAGE, content: welcomeMessage },
  ]);
  const [input, setInput] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [hasUnread, setHasUnread] = useState(false);
  const [pendingBookingState, setPendingBookingState] =
    useState<ChatBookingState | null>(null);
  const [isSessionHydrated, setIsSessionHydrated] = useState(false);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const bubbleRef = useRef<HTMLButtonElement>(null);
  const chatSessionIdRef = useRef<string | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);
  const requestGenerationRef = useRef(0);
  const isSendingRef = useRef(false);
  const isOpenRef = useRef(false);

  useEffect(() => {
    const stored = loadChatSession(window.sessionStorage);
    if (stored) {
      chatSessionIdRef.current = stored.conversationId;
      if (stored.messages.length) setMessages(stored.messages);
      setPendingBookingState(stored.pendingBookingState ?? null);
    } else {
      chatSessionIdRef.current = createConversationId();
    }
    setIsSessionHydrated(true);
  }, []);

  useEffect(() => {
    // Không ghi session ở render đầu tiên: state mặc định chỉ có lời chào và
    // sẽ xóa lịch sử cũ trước khi effect hydrate kịp cập nhật state.
    if (!isSessionHydrated || !chatSessionIdRef.current) return;
    saveChatSession(window.sessionStorage, {
      conversationId: chatSessionIdRef.current,
      messages,
      pendingBookingState,
    });
  }, [isSessionHydrated, messages, pendingBookingState]);

  useEffect(() => () => {
    requestGenerationRef.current += 1;
    isSendingRef.current = false;
    abortControllerRef.current?.abort();
  }, []);

  // Auto-scroll khi có tin nhắn mới
  const scrollToBottom = useCallback(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, []);

  useEffect(() => {
    scrollToBottom();
  }, [messages, scrollToBottom]);

  useEffect(() => {
    setMessages((current) => current.map((message) => (
      message.id === "welcome" ? { ...message, content: welcomeMessage } : message
    )));
  }, [welcomeMessage]);

  // Focus vào input khi mở chat
  useEffect(() => {
    isOpenRef.current = isOpen;
    if (isOpen) {
      setHasUnread(false);
      setTimeout(() => inputRef.current?.focus(), 100);
    }
  }, [isOpen]);

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setIsOpen(false);
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, []);

  useEffect(() => {
    if (!isOpen) return;
    const closeOnOutsideClick = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!panelRef.current?.contains(target) && !bubbleRef.current?.contains(target)) {
        setIsOpen(false);
      }
    };
    document.addEventListener("pointerdown", closeOnOutsideClick);
    return () => document.removeEventListener("pointerdown", closeOnOutsideClick);
  }, [isOpen]);

  const generateId = () => `msg_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;

  const appendBotMessage = (
    content: string,
    action?: ChatAction,
    payload?: ChatActionPayload,
  ) => {
    const botMsg: StoredChatMessage = {
      id: generateId(),
      role: "bot",
      content,
      timestamp: new Date().toISOString(),
      action,
      payload,
    };

    setMessages((prev) => [...prev, botMsg].slice(-MAX_STORED_CHAT_MESSAGES));

    if (!isOpenRef.current) {
      setHasUnread(true);
    }
  };

  const normalizeMessage = (value: string) =>
    value
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "")
      .toLowerCase()
      .trim();

  const isReservationConfirmation = (value: string) => {
    const normalized = normalizeMessage(value);
    return ["xac nhan", "dong y", "ok", "okay", "confirm", "yes"].includes(normalized);
  };

  const isReservationCancellation = (value: string) => {
    const normalized = normalizeMessage(value);
    return [
      "huy",
      "huy yeu cau",
      "khong dat nua",
      "cancel",
      "cancel booking",
      "cancel request",
    ].includes(normalized);
  };

  const clearPendingActions = (source: StoredChatMessage[]) => source.map((message) => (
    message.action === "CREATE_RESERVATION_CONFIRM"
      ? { ...message, action: undefined, payload: undefined }
      : message
  ));

  const navigateToCanonicalBooking = (payload: ChatBookingPayload) => {
    const bookingUrl = buildChatBookingUrl(payload);
    const nextMessages = clearPendingActions(messages);
    setPendingBookingState(null);
    setMessages(nextMessages);
    if (chatSessionIdRef.current) {
      saveChatSession(window.sessionStorage, {
        conversationId: chatSessionIdRef.current,
        messages: nextMessages,
        pendingBookingState: null,
      });
    }
    router.push(bookingUrl);
  };

  const handleClearConversation = () => {
    requestGenerationRef.current += 1;
    isSendingRef.current = false;
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    clearChatSession(window.sessionStorage);
    chatSessionIdRef.current = createConversationId();
    setMessages([{ ...INITIAL_BOT_MESSAGE, content: welcomeMessage }]);
    setPendingBookingState(null);
    setInput("");
    setIsLoading(false);
    setHasUnread(false);
    window.setTimeout(() => inputRef.current?.focus(), 0);
  };

  const isAmbiguousReservationDecline = (value: string) =>
    ["khong", "no"].includes(normalizeMessage(value));

  const getChatErrorMessage = (error: unknown) => {
    const status = axios.isAxiosError<ApiErrorPayload>(error) ? error.response?.status : undefined;
    const payload = axios.isAxiosError<ApiErrorPayload>(error) ? error.response?.data : undefined;
    const serverMessage = payload?.message || payload?.error || payload?.answer;

    if (status === 401) {
      return localize("Bạn cần đăng nhập để thực hiện thao tác này.", "Please sign in to perform this action.");
    }

    if (status === 403) {
      return localize("Tài khoản của bạn không có quyền thực hiện thao tác này.", "Your account is not allowed to perform this action.");
    }

    if (serverMessage) {
      return localize(`Không thể xử lý yêu cầu: ${serverMessage}`, `Unable to process the request: ${serverMessage}`);
    }

    if (axios.isAxiosError(error) && ["ECONNABORTED", "ETIMEDOUT"].includes(error.code || "")) {
      return localize(
        "Hệ thống tư vấn đang phản hồi chậm. Vui lòng thử lại hoặc liên hệ lễ tân.",
        "The assistant is taking too long to respond. Please retry or contact reception.",
      );
    }

    if (axios.isAxiosError(error) && error.code === "ERR_NETWORK") {
      return localize("Không thể kết nối tới hệ thống. Vui lòng thử lại sau.", "Unable to connect. Please try again later.");
    }

    if (error instanceof Error && error.message) {
      return localize(`Không thể xử lý yêu cầu: ${error.message}`, `Unable to process the request: ${error.message}`);
    }

    return localize(
      "Xin lỗi, đã có lỗi xảy ra khi kết nối. Vui lòng thử lại sau hoặc liên hệ lễ tân để được hỗ trợ.",
      "Sorry, a connection error occurred. Please retry or contact reception for help.",
    );
  };

  const sendMessage = async () => {
    const trimmed = input.trim();
    if (!trimmed || isLoading || isSendingRef.current) return;

    isSendingRef.current = true;
    const requestGeneration = requestGenerationRef.current + 1;
    requestGenerationRef.current = requestGeneration;
    let controller: AbortController | null = null;

    const userMsg: StoredChatMessage = {
      id: generateId(),
      role: "user",
      content: trimmed,
      timestamp: new Date().toISOString(),
    };

    setMessages((prev) => [...prev, userMsg].slice(-MAX_STORED_CHAT_MESSAGES));
    setInput("");
    setIsLoading(true);

    try {
      if (pendingBookingState && isReservationCancellation(trimmed)) {
        setPendingBookingState(null);
        setMessages((current) => current.map((message) => (
          message.action === "CREATE_RESERVATION_CONFIRM"
            ? { ...message, action: undefined, payload: undefined }
            : message
        )));
        appendBotMessage(localize("Mình đã hủy yêu cầu đặt phòng đang nhập. Chưa có đơn hoặc giao dịch nào được tạo.", "The booking inquiry was cancelled. No booking or transaction was created."));
        return;
      }

      if (pendingBookingState && isAmbiguousReservationDecline(trimmed)) {
        appendBotMessage(localize(
          "Bạn muốn hủy yêu cầu đặt phòng đang nhập, hay chỉ muốn sửa một thông tin? Hãy nhắn “hủy yêu cầu” hoặc nói rõ nội dung cần đổi.",
          "Do you want to cancel the pending booking inquiry, or only change a detail? Reply “cancel request” or tell me what to update.",
        ));
        return;
      }

      if (isCompleteChatBookingState(pendingBookingState) && isReservationConfirmation(trimmed)) {
        navigateToCanonicalBooking(pendingBookingState);
        return;
      }

      abortControllerRef.current?.abort();
      controller = new AbortController();
      abortControllerRef.current = controller;

      // Hỏi đáp chatbot là endpoint public. Không gắn access token hoặc kích hoạt
      // refresh/redirect đăng nhập khi khách chỉ đang cần tư vấn.
      const response = await publicApiClient.post("/api/chat", {
        question: trimmed,
        conversationId: chatSessionIdRef.current ||= createConversationId(),
        locale,
        history: buildChatHistory(messages),
        bookingContext: pendingBookingState?.context,
        bookingState: pendingBookingState,
      }, { timeout: 20_000, signal: controller.signal });
      if (requestGeneration !== requestGenerationRef.current) return;
      const chatResponse = response.data as ChatApiResponse;

      const answer = chatResponse?.answer || localize("Xin lỗi, tôi chưa thể trả lời câu hỏi này.", "Sorry, I cannot answer that question yet.");

      if (chatResponse?.action === "CONTINUE_RESERVATION") {
        setPendingBookingState({
          ...pendingBookingState,
          ...chatResponse.payload,
          context: chatResponse.payload?.context || pendingBookingState?.context || trimmed,
        });
      } else if (
        chatResponse?.action === "CREATE_RESERVATION_CONFIRM" &&
        chatResponse?.payload?.roomTypes
      ) {
        setPendingBookingState(chatResponse.payload);
      }

      appendBotMessage(answer, chatResponse?.action, chatResponse?.payload);
    } catch (error) {
      if (requestGeneration !== requestGenerationRef.current) return;
      if (axios.isCancel(error) || (error instanceof DOMException && error.name === "AbortError")) {
        return;
      }
      const errorMsg: StoredChatMessage = {
        id: generateId(),
        role: "bot",
        content: getChatErrorMessage(error),
        timestamp: new Date().toISOString(),
      };
      setMessages((prev) => [...prev, errorMsg].slice(-MAX_STORED_CHAT_MESSAGES));
    } finally {
      if (requestGeneration === requestGenerationRef.current) {
        if (abortControllerRef.current === controller) {
          abortControllerRef.current = null;
        }
        isSendingRef.current = false;
        setIsLoading(false);
      }
    }
  };

  const handleMessageAction = (message: StoredChatMessage, confirmed: boolean) => {
    if (message.action === "CREATE_RESERVATION_CONFIRM") {
      if (!confirmed) {
        setPendingBookingState(null);
        setMessages((current) => current.map((item) => (
          item.id === message.id ? { ...item, action: undefined, payload: undefined } : item
        )));
        appendBotMessage(localize(
          "Mình đã hủy yêu cầu đặt phòng đang chờ. Chưa có đơn hoặc giao dịch nào được tạo.",
          "The pending booking request was cancelled. No booking or transaction was created.",
        ));
        return;
      }
      const payload = message.payload;
      if (isCompleteChatBookingState(payload)) {
        try {
          navigateToCanonicalBooking(payload);
        } catch (error) {
          appendBotMessage(getChatErrorMessage(error));
        }
      }
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  };

  const formatTime = (date: string | null) => {
    if (!date) return localize("Bây giờ", "Now");
    const parsed = new Date(date);
    if (Number.isNaN(parsed.getTime())) return localize("Bây giờ", "Now");
    return parsed.toLocaleTimeString(locale === "vi" ? "vi-VN" : "en-US", {
      hour: "2-digit",
      minute: "2-digit",
    });
  };

  return (
    <>
      {/* Chat Panel */}
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="false"
        aria-labelledby="luxury-hotel-chat-title"
        aria-hidden={!isOpen}
        inert={!isOpen}
        className={`fixed ${
          avoidMobileBookingBar
            ? "bottom-[calc(6.5rem+env(safe-area-inset-bottom))]"
            : "bottom-[calc(4.75rem+env(safe-area-inset-bottom))]"
        } right-4 z-[76] w-[380px] max-w-[calc(100vw-2rem)] origin-bottom-right transition-all duration-300 sm:bottom-24 sm:right-6 ${
          isOpen
            ? "scale-100 opacity-100 translate-y-0 pointer-events-auto"
            : "scale-90 opacity-0 translate-y-4 pointer-events-none"
        }`}
      >
        <div className="flex h-[min(560px,calc(100dvh-6rem))] flex-col overflow-hidden rounded-2xl border border-white/10 shadow-2xl">
          {/* Header */}
          <div className="bg-[#0F2A43] px-5 py-4 flex items-center gap-3 shrink-0">
            {/* Bot Avatar */}
            <div className="w-10 h-10 rounded-full bg-gradient-to-br from-[#C8A35B] to-[#c99a4e] flex items-center justify-center shrink-0 shadow-md">
              <svg
                viewBox="0 0 24 24"
                fill="none"
                className="w-5 h-5 text-[#0F2A43]"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="M12 2a4 4 0 0 1 4 4v2H8V6a4 4 0 0 1 4-4Z" />
                <rect x="4" y="8" width="16" height="12" rx="3" />
                <circle cx="9" cy="14" r="1.5" fill="currentColor" stroke="none" />
                <circle cx="15" cy="14" r="1.5" fill="currentColor" stroke="none" />
              </svg>
            </div>
            <div className="flex-1 min-w-0">
              <h3 id="luxury-hotel-chat-title" className="text-white font-bold text-sm tracking-wide">
                {localize("Trợ lý Luxury Hotel", "Luxury Hotel assistant")}
              </h3>
              <p className="text-white/50 text-xs font-medium">
                {localize("Trợ lý ảo 24/7", "Virtual assistant 24/7")}
              </p>
            </div>
            <button
              type="button"
              onClick={handleClearConversation}
              className="flex h-8 w-8 items-center justify-center rounded-full text-white/60 transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#C8A35B]"
              aria-label={localize("Xóa cuộc trò chuyện", "Clear conversation")}
              title={localize("Xóa cuộc trò chuyện", "Clear conversation")}
            >
              <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                <path d="M3 6h18M8 6V4h8v2m-9 0 1 14h8l1-14M10 10v6m4-6v6" />
              </svg>
            </button>
            {/* Close button */}
            <button
              type="button"
              onClick={() => setIsOpen(false)}
              className="w-8 h-8 rounded-full hover:bg-white/10 flex items-center justify-center transition-colors text-white/60 hover:text-white"
              aria-label={localize("Đóng chat", "Close chat")}
            >
              <svg viewBox="0 0 24 24" className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2.5">
                <path d="M18 6 6 18M6 6l12 12" />
              </svg>
            </button>
          </div>

          {/* Messages */}
          <div
            className="flex-1 overflow-y-auto bg-[#F1F0EA] px-4 py-4 space-y-3"
            aria-live="polite"
            aria-busy={isLoading}
          >
            {messages.map((msg) => (
              <div
                key={msg.id}
                className={`flex ${msg.role === "user" ? "justify-end" : "justify-start"}`}
              >
                <div
                  className={`max-w-[82%] rounded-2xl px-4 py-2.5 text-sm leading-relaxed shadow-sm ${
                    msg.role === "user"
                      ? "bg-[#0F2A43] text-white rounded-br-md"
                      : "bg-white text-[#0F2A43] border border-[#D8DDE1] rounded-bl-md"
                  }`}
                >
                  {/* Render text với line breaks */}
                  {msg.content.split("\n").map((line, i) => (
                    <React.Fragment key={i}>
                      {i > 0 && <br />}
                      {line}
                    </React.Fragment>
                  ))}
                  {msg.action === "OPEN_MY_BOOKINGS" && (
                    <Link
                      href="/my-bookings"
                      onClick={() => setIsOpen(false)}
                      className="mt-3 flex min-h-11 w-full items-center justify-center rounded-xl bg-[#B8944F] px-3 py-2 text-xs font-bold text-[#0F2A43] transition hover:bg-[#caa45d] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#0F2A43]"
                    >
                      {localize("Mở lịch sử đặt phòng", "Open My bookings")}
                    </Link>
                  )}
                  {msg.action === "CREATE_RESERVATION_CONFIRM" && (
                    <div className="mt-3 grid grid-cols-2 gap-2">
                      <button
                        type="button"
                        onClick={() => handleMessageAction(msg, false)}
                        className="min-h-11 rounded-xl border border-[#0F2A43]/20 bg-white px-3 py-2 text-xs font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F7F1E5] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]"
                      >
                        {localize("Hủy", "Cancel")}
                      </button>
                      <button
                        type="button"
                        onClick={() => handleMessageAction(msg, true)}
                        className="min-h-11 rounded-xl bg-[#B8944F] px-3 py-2 text-xs font-bold text-[#0F2A43] transition hover:bg-[#caa45d] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#0F2A43]"
                      >
                        {localize("Xem giá & tiếp tục", "Review price")}
                      </button>
                    </div>
                  )}
                  <div
                    className={`text-[10px] mt-1.5 ${
                      msg.role === "user" ? "text-white/40" : "text-[#999]"
                    }`}
                  >
                    {formatTime(msg.timestamp)}
                  </div>
                </div>
              </div>
            ))}

            {/* Typing indicator */}
            {isLoading && (
              <div className="flex justify-start">
                <div className="bg-white border border-[#D8DDE1] rounded-2xl rounded-bl-md px-4 py-3 shadow-sm">
                  <div className="flex gap-1.5 items-center">
                    <span className="w-2 h-2 bg-[#0F2A43]/30 rounded-full animate-bounce [animation-delay:0ms]" />
                    <span className="w-2 h-2 bg-[#0F2A43]/30 rounded-full animate-bounce [animation-delay:150ms]" />
                    <span className="w-2 h-2 bg-[#0F2A43]/30 rounded-full animate-bounce [animation-delay:300ms]" />
                  </div>
                </div>
              </div>
            )}

            <div ref={messagesEndRef} />
          </div>

          {/* Input */}
          <div className="bg-white border-t border-[#D8DDE1] px-4 py-3 shrink-0">
            <div className="flex items-center gap-2">
              <input
                ref={inputRef}
                type="text"
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                maxLength={MAX_CHAT_INPUT_LENGTH}
                placeholder={localize("Nhập câu hỏi của bạn...", "Type your question...")}
                aria-label={localize("Câu hỏi cho trợ lý khách sạn", "Question for the hotel assistant")}
                disabled={isLoading}
                className="flex-1 bg-[#F1F0EA] border border-[#D8DDE1] rounded-xl px-4 py-2.5 text-sm text-[#0F2A43] placeholder:text-[#66727C] focus:outline-none focus:border-[#C8A35B] focus:ring-1 focus:ring-[#C8A35B]/30 transition-colors disabled:opacity-50"
              />
              <button
                type="button"
                onClick={sendMessage}
                disabled={!input.trim() || isLoading}
                className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-[#0F2A43] text-white transition-all hover:bg-[#091E30] active:scale-95 disabled:cursor-not-allowed disabled:opacity-30"
                aria-label={localize("Gửi tin nhắn", "Send message")}
              >
                <svg viewBox="0 0 24 24" className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2.5">
                  <path d="M22 2 11 13M22 2l-7 20-4-9-9-4 20-7z" />
                </svg>
              </button>
            </div>
            <div className="mt-2 flex items-center justify-between gap-3 text-[10px] text-[#7A838B]">
              <span>{localize("Không chia sẻ OTP, mật khẩu hoặc số thẻ.", "Never share OTPs, passwords, or card numbers.")}</span>
              <span aria-live="polite" className={input.length >= 450 ? "font-semibold text-[#9A5D13]" : "shrink-0"}>
                {input.length}/{MAX_CHAT_INPUT_LENGTH}
              </span>
            </div>
          </div>
        </div>
      </div>

      {/* Floating Bubble Button */}
      <button
        type="button"
        ref={bubbleRef}
        onClick={() => setIsOpen(!isOpen)}
        className={`group fixed ${
          avoidMobileBookingBar
            ? "bottom-[calc(5.75rem+env(safe-area-inset-bottom))]"
            : "bottom-[calc(1rem+env(safe-area-inset-bottom))]"
        } right-4 z-[76] flex h-12 w-12 items-center justify-center rounded-full shadow-lg transition-all duration-300 active:scale-90 sm:bottom-6 sm:right-6 sm:h-14 sm:w-14 lg:bottom-6 ${
          isOpen
            ? "rotate-0 bg-[#0F2A43] hover:bg-[#091E30]"
            : "bg-gradient-to-br from-[#C8A35B] to-[#c99a4e] hover:from-[#d4a85e] hover:to-[#b8893f] hover:shadow-xl hover:shadow-[#C8A35B]/25"
        }`}
        aria-label={isOpen ? localize("Đóng chat", "Close chat") : localize("Mở chat hỗ trợ", "Open support chat")}
      >
        {/* Unread badge */}
        {hasUnread && !isOpen && (
          <span className="absolute -top-1 -right-1 w-5 h-5 bg-red-500 rounded-full text-white text-[10px] font-bold flex items-center justify-center animate-pulse shadow-md">
            !
          </span>
        )}

        {/* Chat icon (khi đóng) */}
        <svg
          viewBox="0 0 24 24"
          className={`w-6 h-6 text-white absolute transition-all duration-300 ${
            isOpen ? "scale-0 rotate-90 opacity-0" : "scale-100 rotate-0 opacity-100"
          }`}
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
        </svg>

        {/* Close icon (khi mở) */}
        <svg
          viewBox="0 0 24 24"
          className={`w-5 h-5 text-white absolute transition-all duration-300 ${
            isOpen ? "scale-100 rotate-0 opacity-100" : "scale-0 -rotate-90 opacity-0"
          }`}
          fill="none"
          stroke="currentColor"
          strokeWidth="2.5"
        >
          <path d="M18 6 6 18M6 6l12 12" />
        </svg>
      </button>
    </>
  );
}
