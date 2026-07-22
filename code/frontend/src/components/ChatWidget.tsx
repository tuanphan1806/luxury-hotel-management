"use client";

import React, { useState, useRef, useEffect, useCallback } from "react";
import axios from "axios";
import { apiClient, authSession, publicApiClient, type ApiErrorPayload } from "@/lib/api";
import { saveGuestReservationToken } from "@/lib/guest-reservation-token";
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from "@/lib/idempotency";

/**
 * ChatWidget - Floating chatbot widget tích hợp API /api/chat
 *
 * Hiển thị ở góc dưới bên phải màn hình dưới dạng bubble.
 * Khi click sẽ mở panel chat cho phép khách hỏi đáp về khách sạn.
 */

interface ChatMessage {
  id: string;
  role: "user" | "bot";
  content: string;
  timestamp: Date | null;
}

interface ChatReservationPayload {
  checkIn: string;
  checkOut: string;
  guestCount?: number;
  note?: string;
  roomTypes: Array<{
    roomTypeId: number;
    quantity: number;
  }>;
}

interface ChatApiResponse {
  answer?: string;
  action?: string;
  payload?: ChatReservationPayload | { context?: string };
}

interface PendingPayment {
  reservationId: number;
  reservationCode: string;
  guestToken?: string;
}

interface GuestCustomer {
  fullName: string;
  email: string;
  phone: string;
  address?: string;
}

interface GuestBookingDraft {
  payload: ChatReservationPayload;
  customer: Partial<GuestCustomer>;
  step: "fullName" | "email" | "phone" | "address" | "confirm";
}

const INITIAL_BOT_MESSAGE: ChatMessage = {
  id: "welcome",
  role: "bot",
  content:
    "Xin chào! Tôi là trợ lý AI của Luxury Hotel. Tôi có thể hỗ trợ bạn về phòng, giá, tiện nghi và thông tin đặt phòng. Hãy hỏi tôi bất cứ điều gì! 🏨",
  // Giá trị ổn định cho SSR/hydration. Các tin nhắn phát sinh trên client vẫn
  // dùng thời gian thực tế; tin chào hiển thị nhãn "Bây giờ".
  timestamp: null,
};

export default function ChatWidget() {
  const [isOpen, setIsOpen] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([INITIAL_BOT_MESSAGE]);
  const [input, setInput] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [hasUnread, setHasUnread] = useState(false);
  const [pendingReservationPayload, setPendingReservationPayload] =
    useState<ChatReservationPayload | null>(null);
  const [pendingPayment, setPendingPayment] = useState<PendingPayment | null>(null);
  const [guestBookingDraft, setGuestBookingDraft] = useState<GuestBookingDraft | null>(null);
  const [pendingChatContext, setPendingChatContext] = useState<string | null>(null);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const chatSessionIdRef = useRef<string | null>(null);

  // Auto-scroll khi có tin nhắn mới
  const scrollToBottom = useCallback(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, []);

  useEffect(() => {
    scrollToBottom();
  }, [messages, scrollToBottom]);

  // Focus vào input khi mở chat
  useEffect(() => {
    if (isOpen) {
      setHasUnread(false);
      setTimeout(() => inputRef.current?.focus(), 100);
    }
  }, [isOpen]);

  const generateId = () => `msg_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;

  const appendBotMessage = (content: string) => {
    const botMsg: ChatMessage = {
      id: generateId(),
      role: "bot",
      content,
      timestamp: new Date(),
    };

    setMessages((prev) => [...prev, botMsg]);

    if (!isOpen) {
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
    return ["huy", "khong", "cancel", "no"].includes(normalized);
  };

  const isValidEmail = (value: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
  const isValidPhone = (value: string) => /^[0-9+][0-9\s.-]{7,14}$/.test(value);

  const getChatErrorMessage = (error: unknown) => {
    const status = axios.isAxiosError<ApiErrorPayload>(error) ? error.response?.status : undefined;
    const payload = axios.isAxiosError<ApiErrorPayload>(error) ? error.response?.data : undefined;
    const serverMessage = payload?.message || payload?.error || payload?.answer;

    if (status === 401) {
      return "Bạn cần đăng nhập để thực hiện thao tác này.";
    }

    if (status === 403) {
      return "Tài khoản của bạn không có quyền thực hiện thao tác này.";
    }

    if (serverMessage) {
      return `Không thể xử lý yêu cầu: ${serverMessage}`;
    }

    if (axios.isAxiosError(error) && error.code === "ERR_NETWORK") {
      return "Không thể kết nối tới hệ thống. Bạn vui lòng kiểm tra backend đang chạy hoặc thử lại sau.";
    }

    if (error instanceof Error && error.message) {
      return `Không thể xử lý yêu cầu: ${error.message}`;
    }

    return "Xin lỗi, đã có lỗi xảy ra khi kết nối. Vui lòng thử lại sau hoặc liên hệ lễ tân để được hỗ trợ.";
  };

  const sendMessage = async () => {
    const trimmed = input.trim();
    if (!trimmed || isLoading) return;

    const userMsg: ChatMessage = {
      id: generateId(),
      role: "user",
      content: trimmed,
      timestamp: new Date(),
    };

    setMessages((prev) => [...prev, userMsg]);
    setInput("");
    setIsLoading(true);

    try {
      if (pendingPayment && isReservationCancellation(trimmed)) {
        const reservationClient = pendingPayment.guestToken ? publicApiClient : apiClient;
        const operationScope = `reservation:${pendingPayment.reservationId}:CANCEL_CHAT`;
        await reservationClient.patch(
          `/api/reservations/cancel/${pendingPayment.reservationId}`,
          { cancellationReason: "Khách hủy trong chatbot trước khi thanh toán" },
          {
            headers: {
              "Idempotency-Key": getOrCreateIdempotencyKey(operationScope),
              ...(pendingPayment.guestToken
                ? { "X-Guest-Token": pendingPayment.guestToken }
                : {}),
            },
          }
        );
        clearIdempotencyKey(operationScope);
        setPendingPayment(null);
        appendBotMessage(
          `Đã hủy mã đặt phòng ${pendingPayment.reservationCode}. Trạng thái đã chuyển sang CANCELLED và phòng giữ đã được giải phóng.`
        );
        return;
      }

      if (pendingPayment && isReservationConfirmation(trimmed)) {
        const paymentClient = pendingPayment.guestToken ? publicApiClient : apiClient;
        const paymentResponse = await paymentClient.post(
          "/api/payments/create",
          {
            bookingId: pendingPayment.reservationId,
            provider: "SEPAY",
            orderInfo: `Thanh toan dat phong ${pendingPayment.reservationCode}`,
          },
          {
            headers: {
              "Idempotency-Key": getOrCreateIdempotencyKey(
                `payment:${pendingPayment.reservationId}:DEPOSIT`,
              ),
              ...(pendingPayment.guestToken
                ? { "X-Guest-Token": pendingPayment.guestToken }
                : {}),
            },
          }
        );
        const payment = paymentResponse.data?.data ?? paymentResponse.data;
        const paymentUrl = payment?.paymentUrl || (payment?.transactionId
          ? `/booking/payment-result?transactionId=${encodeURIComponent(payment.transactionId)}`
          : "");
        if (!paymentUrl) {
          throw new Error("Backend không trả về trang thanh toán SePay VietQR");
        }
        setPendingPayment(null);
        window.location.assign(paymentUrl);
        return;
      }

      if (pendingChatContext && isReservationCancellation(trimmed)) {
        setPendingChatContext(null);
        appendBotMessage("Mình đã hủy yêu cầu tư vấn đặt phòng đang nhập. Chưa có reservation nào được tạo.");
        return;
      }

      if (guestBookingDraft && isReservationCancellation(trimmed)) {
        setGuestBookingDraft(null);
        clearIdempotencyKey("reservation:create:chat:guest");
        appendBotMessage("Mình đã hủy việc thu thập thông tin đặt phòng. Chưa có reservation nào được tạo.");
        return;
      }

      if (guestBookingDraft) {
        const { payload, customer, step } = guestBookingDraft;

        if (step === "fullName") {
          if (trimmed.length < 2) {
            appendBotMessage("Họ tên chưa hợp lệ. Bạn vui lòng nhập họ và tên của người đặt phòng.");
            return;
          }
          setGuestBookingDraft({ payload, customer: { ...customer, fullName: trimmed }, step: "email" });
          appendBotMessage("Bạn vui lòng nhập email để nhận link tra cứu booking và guest token.");
          return;
        }

        if (step === "email") {
          if (!isValidEmail(trimmed)) {
            appendBotMessage("Email chưa đúng định dạng. Ví dụ: khachhang@example.com");
            return;
          }
          setGuestBookingDraft({ payload, customer: { ...customer, email: trimmed }, step: "phone" });
          appendBotMessage("Bạn vui lòng nhập số điện thoại liên hệ.");
          return;
        }

        if (step === "phone") {
          if (!isValidPhone(trimmed)) {
            appendBotMessage("Số điện thoại chưa hợp lệ. Bạn vui lòng nhập lại, ví dụ 0901234567.");
            return;
          }
          setGuestBookingDraft({ payload, customer: { ...customer, phone: trimmed }, step: "address" });
          appendBotMessage('Bạn vui lòng nhập địa chỉ, hoặc nhắn "bỏ qua" nếu không muốn cung cấp.');
          return;
        }

        if (step === "address") {
          const address = normalizeMessage(trimmed) === "bo qua" ? undefined : trimmed;
          const completeCustomer = { ...customer, address } as GuestCustomer;
          setGuestBookingDraft({ payload, customer: completeCustomer, step: "confirm" });
          appendBotMessage(
            `Bạn vui lòng xác nhận thông tin khách:\n- Họ tên: ${completeCustomer.fullName}\n- Email: ${completeCustomer.email}\n- Điện thoại: ${completeCustomer.phone}\n- Địa chỉ: ${completeCustomer.address || "Không cung cấp"}\n- Check-in: ${payload.checkIn}\n- Check-out: ${payload.checkOut}\n- Số khách: ${payload.guestCount || 1}\n\nNhắn "xác nhận" để tạo phiên giữ chỗ chờ thanh toán hoặc "hủy" để dừng.`
          );
          return;
        }

        if (step === "confirm") {
          if (!isReservationConfirmation(trimmed)) {
            appendBotMessage('Bạn vui lòng nhắn "xác nhận" để tạo booking hoặc "hủy" để dừng.');
            return;
          }
          const reservationCreateScope = "reservation:create:chat:guest";
          const createResResponse = await publicApiClient.post("/api/reservations", {
            ...payload,
            customer,
          }, {
            headers: {
              "Idempotency-Key": getOrCreateIdempotencyKey(reservationCreateScope),
            },
          });
          const reservation = createResResponse.data?.data;
          if (typeof reservation?.id !== "number" || !reservation?.guestToken) {
            throw new Error("Backend không trả về reservation id hoặc guest token hợp lệ");
          }
          clearIdempotencyKey(reservationCreateScope);
          setGuestBookingDraft(null);
          saveGuestReservationToken(reservation.id, reservation.guestToken);
          setPendingPayment({
            reservationId: reservation.id,
            reservationCode: reservation.reservationCode || String(reservation.id),
            guestToken: reservation.guestToken,
          });
          appendBotMessage(
            `Đã ghi nhận thông tin đặt phòng với mã ${reservation.reservationCode}. Phòng chưa bị giữ ở bước này. Khi bạn nhắn "xác nhận" để mở SePay VietQR, hệ thống mới kiểm tra lại phòng trống và giữ phòng trong đúng 5 phút. Sau khi ngân hàng xác nhận đã nhận tiền, đơn chuyển sang DRAFT và chờ khách sạn xác nhận.`
          );
          return;
        }
      }

      if (pendingReservationPayload && isReservationCancellation(trimmed)) {
        setPendingReservationPayload(null);
        clearIdempotencyKey("reservation:create:chat:authenticated");
        appendBotMessage("Mình đã hủy yêu cầu đặt phòng đang chờ xác nhận. Bạn có thể gửi lại thông tin mới bất cứ lúc nào.");
        return;
      }

      if (pendingReservationPayload && isReservationConfirmation(trimmed)) {
        if (!(await authSession.isAuthenticated())) {
          const payload = pendingReservationPayload;
          setPendingReservationPayload(null);
          setGuestBookingDraft({ payload, customer: {}, step: "fullName" });
          appendBotMessage(
            "Để tạo guest booking, mình cần bổ sung các thông tin còn thiếu ngay trong chat. Trước tiên, bạn vui lòng nhập họ và tên người đặt phòng. Bạn có thể nhắn \"hủy\" bất cứ lúc nào để dừng."
          );
          return;
        }

        const reservationCreateScope = "reservation:create:chat:authenticated";
        const createResResponse = await apiClient.post(
          "/api/reservations",
          pendingReservationPayload,
          {
            headers: {
              "Idempotency-Key": getOrCreateIdempotencyKey(reservationCreateScope),
            },
          },
        );
        const reservation = createResResponse.data?.data;
        const reservationId = reservation?.id;
        const reservationCode = reservation?.reservationCode || String(reservationId || "mới");

        if (typeof reservationId !== "number") {
          throw new Error("Backend không trả về reservation id hợp lệ");
        }

        clearIdempotencyKey(reservationCreateScope);
        setPendingReservationPayload(null);
        setPendingPayment({ reservationId, reservationCode });
        appendBotMessage(
          `Đã ghi nhận thông tin đặt phòng với mã ${reservationCode}; phòng chưa bị giữ. Khi bạn nhắn "xác nhận" để mở SePay VietQR, hệ thống mới kiểm tra lại tồn phòng và giữ trong đúng 5 phút. Tiền cọc bằng 50% tổng giá trị đặt phòng. Sau khi ngân hàng xác nhận đã nhận tiền, đơn chuyển sang DRAFT và chờ khách sạn xác nhận.`
        );
        return;
      }

      // Hỏi đáp chatbot là endpoint public. Không gắn access token hoặc kích hoạt
      // refresh/redirect đăng nhập khi khách chỉ đang cần tư vấn.
      const response = await publicApiClient.post("/api/chat", {
        question: pendingChatContext
          ? `${pendingChatContext}\nThông tin bổ sung: ${trimmed}`
          : trimmed,
        conversationId: chatSessionIdRef.current ||= typeof crypto !== "undefined" && "randomUUID" in crypto
          ? crypto.randomUUID()
          : `chat_${Date.now()}_${Math.random().toString(36).slice(2)}`,
      });
      const chatResponse = response.data as ChatApiResponse;

      const answer = chatResponse?.answer || "Xin lỗi, tôi chưa thể trả lời câu hỏi này.";

      if (chatResponse?.action === "CONTINUE_RESERVATION") {
        const continuation = chatResponse.payload as { context?: string } | undefined;
        setPendingChatContext(continuation?.context || pendingChatContext || trimmed);
      } else if (
        chatResponse?.action === "CREATE_RESERVATION_CONFIRM" &&
        chatResponse?.payload
      ) {
        setPendingChatContext(null);
        setPendingReservationPayload(chatResponse.payload as ChatReservationPayload);
      } else {
        setPendingChatContext(null);
      }

      appendBotMessage(answer);
    } catch (error) {
      const errorMsg: ChatMessage = {
        id: generateId(),
        role: "bot",
        content: getChatErrorMessage(error),
        timestamp: new Date(),
      };
      setMessages((prev) => [...prev, errorMsg]);
    } finally {
      setIsLoading(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  };

  const formatTime = (date: Date | null) => {
    if (!date) return "Bây giờ";
    return date.toLocaleTimeString("vi-VN", {
      hour: "2-digit",
      minute: "2-digit",
    });
  };

  return (
    <>
      {/* Chat Panel */}
      <div
        className={`fixed bottom-40 right-4 z-[9999] w-[380px] max-w-[calc(100vw-2rem)] origin-bottom-right transition-all duration-300 sm:right-6 lg:bottom-24 ${
          isOpen
            ? "scale-100 opacity-100 translate-y-0 pointer-events-auto"
            : "scale-90 opacity-0 translate-y-4 pointer-events-none"
        }`}
      >
        <div className="rounded-2xl shadow-2xl overflow-hidden border border-white/10 flex flex-col" style={{ height: "520px" }}>
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
              <h3 className="text-white font-bold text-sm tracking-wide">
                Trợ lý Luxury Hotel
              </h3>
              <p className="text-white/50 text-xs font-medium">
                Trợ lý ảo 24/7
              </p>
            </div>
            {/* Close button */}
            <button
              onClick={() => setIsOpen(false)}
              className="w-8 h-8 rounded-full hover:bg-white/10 flex items-center justify-center transition-colors text-white/60 hover:text-white"
              aria-label="Đóng chat"
            >
              <svg viewBox="0 0 24 24" className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2.5">
                <path d="M18 6 6 18M6 6l12 12" />
              </svg>
            </button>
          </div>

          {/* Messages */}
          <div className="flex-1 overflow-y-auto bg-[#F1F0EA] px-4 py-4 space-y-3">
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
                placeholder="Nhập câu hỏi của bạn..."
                disabled={isLoading}
                className="flex-1 bg-[#F1F0EA] border border-[#D8DDE1] rounded-xl px-4 py-2.5 text-sm text-[#0F2A43] placeholder:text-[#66727C] focus:outline-none focus:border-[#C8A35B] focus:ring-1 focus:ring-[#C8A35B]/30 transition-colors disabled:opacity-50"
              />
              <button
                onClick={sendMessage}
                disabled={!input.trim() || isLoading}
                className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-[#0F2A43] text-white transition-all hover:bg-[#091E30] active:scale-95 disabled:cursor-not-allowed disabled:opacity-30"
                aria-label="Gửi tin nhắn"
              >
                <svg viewBox="0 0 24 24" className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth="2.5">
                  <path d="M22 2 11 13M22 2l-7 20-4-9-9-4 20-7z" />
                </svg>
              </button>
            </div>
            <p className="text-[10px] text-[#aaa] text-center mt-2">
              Trợ lý AI • Hỗ trợ tiếng Việt và English
            </p>
          </div>
        </div>
      </div>

      {/* Floating Bubble Button */}
      <button
        onClick={() => setIsOpen(!isOpen)}
        className={`group fixed bottom-24 right-1 z-[9999] flex h-12 w-12 items-center justify-center rounded-full shadow-lg transition-all duration-300 active:scale-90 sm:right-6 sm:h-14 sm:w-14 lg:bottom-6 ${
          isOpen
            ? "rotate-0 bg-[#0F2A43] hover:bg-[#091E30]"
            : "bg-gradient-to-br from-[#C8A35B] to-[#c99a4e] hover:from-[#d4a85e] hover:to-[#b8893f] hover:shadow-xl hover:shadow-[#C8A35B]/25"
        }`}
        aria-label={isOpen ? "Đóng chat" : "Mở chat hỗ trợ"}
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
