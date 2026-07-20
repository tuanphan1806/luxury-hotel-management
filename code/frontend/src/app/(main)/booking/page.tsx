"use client";

import React, { useState, useEffect, Suspense } from "react";
import axios from "axios";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import ViewportModal from "@/components/UI/ViewportModal";
import { useSearchParams, useRouter } from "next/navigation";
import Link from "next/link";
import { apiClient, authSession, publicApiClient } from "@/lib/api";
import { saveGuestReservationToken } from "@/lib/guest-reservation-token";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { getPublicRoomTypes } from "@/lib/public-catalog";
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from "@/lib/idempotency";

interface BookingData {
  roomName: string;
  size: string;
  image: string;
  pricePerHour: number;
  totalHours: number;
  checkInDate: string;
  checkOutDate: string;
  adultsCount: string;
  childrenCount: string;
  selectedRooms: Array<{
    roomTypeId: number;
    roomName: string;
    quantity: number;
    pricePerHour: number;
  }>;
}

interface CurrentUserProfile {
  fullName?: string;
  email?: string;
  phone?: string;
  address?: string;
}

interface PendingReservationSession {
  id: number;
  reservationCode: string;
  guestToken?: string;
  guest: boolean;
}

interface BookingRoomType {
  id: number;
  typeName?: string;
  typeNameEn?: string;
  price?: number;
  imageUrl?: string;
  size?: string;
}

const getApiErrorMessage = (error: unknown, fallback: string) =>
  axios.isAxiosError<{ message?: string }>(error)
    ? error.response?.data?.message || fallback
    : error instanceof Error && error.message
      ? error.message
      : fallback;

// Fallback details removed as we use API

function BookingFormContent() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const { localeTag, localize } = useLanguage();

  const roomId = searchParams.get("roomId");
  const roomTypesParam = searchParams.get("roomTypes");
  const checkIn = searchParams.get("checkIn");
  const checkOut = searchParams.get("checkOut");
  const adults = searchParams.get("adults") || "2";
  const childrenVal = searchParams.get("children") || "0";

  const [step] = useState(2); // Step 2 is active on this page
  const [bookingData, setBookingData] = useState<BookingData | null>(null);

  // Form states
  const [fullName, setFullName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [country, setCountry] = useState("Việt Nam");
  const [specialRequest, setSpecialRequest] = useState("");
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Payment states
  const [agree, setAgree] = useState(false);
  const [paymentPlan, setPaymentPlan] = useState<"DEPOSIT_50" | "PREPAY_100">("DEPOSIT_50");
  const [paymentError, setPaymentError] = useState("");
  const [pendingReservation, setPendingReservation] = useState<PendingReservationSession | null>(null);
  const [isConfirmationOpen, setIsConfirmationOpen] = useState(false);

  // Booking hold and code states (US3.2)
  const [bookingCode, setBookingCode] = useState("");
  const [timeLeft, setTimeLeft] = useState(300); // QR giữ phòng tối đa 5 phút

  useEffect(() => {
    // Match backend pricing: totalAmount = pricePerHour * quantity * totalHours.
    const requestedRooms = roomTypesParam
      ? roomTypesParam.split(",").map((item) => {
          const [id, quantity] = item.split(":").map(Number);
          return { id, quantity };
        }).filter((item) => item.id > 0 && item.quantity > 0)
      : roomId ? [{ id: Number(roomId), quantity: 1 }] : [];

    if (requestedRooms.length === 0 || !checkIn || !checkOut) {
      router.push("/rooms");
      return;
    }

    const checkInTime = new Date(checkIn);
    const checkOutTime = new Date(checkOut);
    if (
      Number.isNaN(checkInTime.getTime())
      || Number.isNaN(checkOutTime.getTime())
      || checkOutTime <= checkInTime
    ) {
      router.push("/rooms");
      return;
    }
    const totalHours = Math.max(
      1,
      Math.ceil((checkOutTime.getTime() - checkInTime.getTime()) / (1000 * 60 * 60)),
    );

    // Prefill customer info when an account session exists, but allow guest checkout.
    void authSession.getCurrentUser<CurrentUserProfile>(false).then((profile) => {
      if (profile) {
        setIsAuthenticated(true);
        setFullName(profile?.fullName || "");
        setEmail(profile?.email || "");
        setPhone(profile?.phone || "");
        setCountry(profile?.address || "Việt Nam");
      } else {
        setIsAuthenticated(false);
      }
    });

    // Một catalog dùng chung thay cho một request riêng cho từng loại phòng.
    getPublicRoomTypes<BookingRoomType>()
      .then((roomTypes) => {
        const matches = requestedRooms.map((requested) => ({
          requested,
          roomType: roomTypes.find((item) => Number(item.id) === requested.id),
        }));
        if (matches.some((item) => !item.roomType)) {
          throw new Error("Không tìm thấy loại phòng đã chọn");
        }
        const selectedRooms = matches.map(({ requested, roomType }) => ({
          roomTypeId: requested.id,
          roomName: localize(roomType?.typeName, roomType?.typeNameEn),
          quantity: requested.quantity,
          pricePerHour: Number(roomType?.price || 0),
        }));
        if (selectedRooms.length > 0) {
          const match = matches[0].roomType as BookingRoomType;
          setBookingData({
            roomName: selectedRooms.map((room) => `${room.quantity} × ${room.roomName}`).join(", "),
            size: match.size || "",
            image: match.imageUrl || "",
            pricePerHour: Number(match.price || 0),
            totalHours,
            checkInDate: checkIn,
            checkOutDate: checkOut,
            adultsCount: adults,
            childrenCount: childrenVal,
            selectedRooms,
          });
        }
      })
      .catch(err => {
        console.error("Error fetching room for booking", err);
        router.push("/rooms");
      });
  }, [roomId, roomTypesParam, checkIn, checkOut, adults, childrenVal, router, localize]);

  // Hold Countdown effect
  useEffect(() => {
    if (step !== 3 || timeLeft <= 0) return;
    const interval = setInterval(() => {
      setTimeLeft((prev) => prev - 1);
    }, 1000);
    return () => clearInterval(interval);
  }, [step, timeLeft]);

  if (!bookingData) return null;

  // Math calculations
  const total = bookingData.selectedRooms.reduce(
    (sum, room) => sum + (room.pricePerHour + Math.max(0, bookingData.totalHours - 1) * 10_000) * room.quantity,
    0
  );
  const deposit50 = Math.ceil(total * 0.5);
  const amountDueNow = paymentPlan === "PREPAY_100" ? total : deposit50;

  const formatVND = (num: number) => {
    return num.toLocaleString("vi-VN") + " đ";
  };

  const formatDateTimeVietnamese = (dateStr: string) => {
    const date = new Date(dateStr);
    return Number.isNaN(date.getTime())
      ? dateStr
      : date.toLocaleString(localeTag, {
          weekday: "short",
          day: "2-digit",
          month: "2-digit",
          year: "numeric",
          hour: "2-digit",
          minute: "2-digit",
        });
  };

  const formatTimeLeft = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
  };

  const getFormValidationError = (name = fullName, customerEmail = email, customerPhone = phone) => {
    const normalizedPhone = customerPhone.replace(/[\s().+-]/g, "");
    if (name.trim().length < 2 || name.trim().length > 100) {
      return localize("Họ và tên phải từ 2–100 ký tự.", "Full name must contain 2–100 characters.");
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(customerEmail.trim()) || customerEmail.trim().length > 254) {
      return localize("Vui lòng nhập địa chỉ email hợp lệ.", "Please enter a valid email address.");
    }
    if (!/^\d{8,15}$/.test(normalizedPhone)) {
      return localize("Số điện thoại phải gồm 8–15 chữ số.", "Phone number must contain 8–15 digits.");
    }
    if (specialRequest.trim().length > 500) {
      return localize("Yêu cầu đặc biệt không được vượt quá 500 ký tự.", "Special requests cannot exceed 500 characters.");
    }
    if (!agree) {
      return localize("Bạn phải đồng ý với Điều khoản & Điều kiện trước khi đặt phòng.", "You must accept the Terms & Conditions before booking.");
    }
    return "";
  };

  const handleRequestBooking = (event: React.MouseEvent<HTMLButtonElement>) => {
    event.preventDefault();
    const validationError = getFormValidationError();
    setPaymentError(validationError);
    if (validationError) return;
    setIsConfirmationOpen(true);
  };

  const handleConfirmBooking = async () => {
    setPaymentError("");
    // Kiểm tra trạng thái thật từ backend; không dựa vào state có thể
    // chưa kịp cập nhật sau khi trang vừa khôi phục refresh cookie.
    const currentUser = await authSession.getCurrentUser<CurrentUserProfile>(false);
    const isGuestBooking = !currentUser;
    const bookingFullName = currentUser?.fullName || fullName;
    const bookingEmail = currentUser?.email || email;
    const bookingPhone = currentUser?.phone || phone;

    setIsAuthenticated(!isGuestBooking);
    const validationError = getFormValidationError(bookingFullName, bookingEmail, bookingPhone);
    if (validationError) {
      setPaymentError(validationError);
      setIsConfirmationOpen(false);
      return;
    }

    setIsConfirmationOpen(false);
    setIsSubmitting(true);
    try {
      // Tạo PAYMENT_PENDING trước; backend chỉ khóa tồn phòng ở bước
      // /payments/create, ngay khi mã QR thực sự được phát hành.
      const checkInDateTime = `${bookingData.checkInDate}:00`;
      const checkOutDateTime = `${bookingData.checkOutDate}:00`;
      let reservation = pendingReservation;
      if (!reservation) {
        const reservationClient = isGuestBooking ? publicApiClient : apiClient;
        const reservationCreateScope = "reservation:create:booking";
        const createResResponse = await reservationClient.post("/api/reservations", {
          checkIn: checkInDateTime,
          checkOut: checkOutDateTime,
          guestCount: parseInt(bookingData.adultsCount) + parseInt(bookingData.childrenCount),
          note: specialRequest,
          paymentPlan,
          customer: {
            fullName: bookingFullName,
            email: bookingEmail,
            phone: bookingPhone,
            address: country,
          },
          roomTypes: bookingData.selectedRooms.map((room) => ({
            roomTypeId: room.roomTypeId,
            quantity: room.quantity,
          }))
        }, {
          headers: {
            "Idempotency-Key": getOrCreateIdempotencyKey(reservationCreateScope),
          },
        });

        const created = createResResponse.data?.data ?? createResResponse.data;
        if (typeof created?.id !== "number" || !created?.reservationCode) {
          throw new Error("Không thể tạo đơn đặt phòng");
        }
        if (isGuestBooking && !created.guestToken) {
          throw new Error("Backend không trả về guestToken cho đặt phòng không đăng nhập");
        }
        reservation = {
          id: created.id,
          reservationCode: created.reservationCode,
          guestToken: created.guestToken,
          guest: isGuestBooking,
        };
        setPendingReservation(reservation);
        clearIdempotencyKey(reservationCreateScope);
        if (reservation.guestToken) {
          saveGuestReservationToken(reservation.id, reservation.guestToken);
        }
      }

      const code = reservation.reservationCode;
      const guestToken = reservation.guestToken;

      setBookingCode(code);
      setTimeLeft(300);

      // 2. Process Payment
      const paymentClient = reservation.guest ? publicApiClient : apiClient;
      const idempotencyKey = getOrCreateIdempotencyKey(
        `payment:${reservation.id}:DEPOSIT`,
      );
      const paymentResponse = await paymentClient.post("/api/payments/create", {
        bookingId: reservation.id,
        provider: "SEPAY",
        purpose: "DEPOSIT",
        orderInfo: `Thanh toan dat phong ${code}`
      }, {
        headers: {
          "Idempotency-Key": idempotencyKey,
          ...(reservation.guest ? { "X-Guest-Token": guestToken } : {}),
        },
      });
      const payment = paymentResponse.data?.data ?? paymentResponse.data;
      const paymentUrl = typeof payment?.paymentUrl === "string" ? payment.paymentUrl.trim() : "";
      const transactionId = typeof payment?.transactionId === "string" ? payment.transactionId.trim() : "";
      const paymentResultUrl = paymentUrl || (transactionId
        ? `/booking/payment-result?transactionId=${encodeURIComponent(transactionId)}`
        : "");

      if (!paymentResultUrl) {
        throw new Error("Backend không trả về đường dẫn hoặc mã giao dịch QR hợp lệ");
      }
      window.location.assign(paymentResultUrl);
    } catch (error: unknown) {
      console.error("Booking error:", error);
      const message = getApiErrorMessage(
        error,
        "Lỗi trong quá trình tạo thanh toán QR. Vui lòng kiểm tra thông tin và thử lại."
      );
      if (/hết hạn|đã hủy|không còn hiệu lực/i.test(message)) {
        setPendingReservation(null);
      }
      setPaymentError(message);
    } finally {
      setIsSubmitting(false);
    }
  };

  if (step === 3) {
    return (
      <div className="bg-[#F1F0EA] py-10 min-h-screen">
        <div className="max-w-6xl mx-auto px-6 space-y-8">
          {/* Step Indicator Header */}
          <div className="flex items-center justify-center gap-6 text-xs sm:text-sm font-semibold text-text-light border-b border-gray-200/50 pb-6">
            <div className="flex items-center gap-2 text-[#80632F] font-medium">
              <span className="w-6 h-6 rounded-full bg-[#80632F] text-white flex items-center justify-center text-[10px] font-bold">1</span>
              <span className="uppercase tracking-wider">Chọn phòng</span>
            </div>
            <div className="h-px w-10 sm:w-16 bg-[#80632F]" />
            <div className="flex items-center gap-2 text-[#80632F] font-medium">
              <span className="w-6 h-6 rounded-full bg-[#80632F] text-white flex items-center justify-center text-[10px] font-bold">2</span>
              <span className="uppercase tracking-wider">Dịch vụ</span>
            </div>
            <div className="h-px w-10 sm:w-16 bg-[#80632F]" />
            <div className="flex items-center gap-2 text-primary-navy font-bold">
              <span className="w-6 h-6 rounded-full bg-primary-navy text-white flex items-center justify-center text-[10px] font-bold">3</span>
              <span className="uppercase tracking-wider">Xác nhận</span>
            </div>
          </div>

          {/* Success Checkmark & Titles */}
          <div className="text-center space-y-4 max-w-2xl mx-auto pb-6">
            <div className="w-16 h-12 bg-[#80632F] rounded-md flex items-center justify-center mx-auto text-white text-xl font-bold shadow-sm">
              ✓
            </div>
            <h2 className="font-serif text-3xl md:text-4xl font-bold text-primary-navy tracking-wide">
              Đặt phòng thành công!
            </h2>
            <p className="text-sm md:text-base font-serif font-bold text-[#80632F] tracking-widest uppercase">
              Mã số đặt phòng: {bookingCode}
            </p>
            {timeLeft > 0 ? (
              <div className="inline-block bg-[#F0EADF] border border-[#F0EADF] text-[#80632F] px-4 py-2 rounded-xl text-xs font-bold mt-2 animate-pulse">
                ⏰ Đã khóa giữ phòng! Vui lòng quét mã QR và thanh toán trong: <span className="text-sm font-mono text-red-600">{formatTimeLeft(timeLeft)}</span>
              </div>
            ) : (
              <div className="inline-block bg-red-50 border border-red-100 text-red-600 px-4 py-2 rounded-xl text-xs font-bold mt-2">
                ⚠️ Hết thời gian giữ phòng tạm thời. Đơn đặt phòng của bạn đã bị hủy tự động!
              </div>
            )}
            <p className="text-xs text-text-light font-medium pt-2">
              Một email xác nhận đã được gửi đến địa chỉ email của bạn.
            </p>
          </div>

          {/* Two Columns Grid */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-8 items-start">
            
            {/* Left Column: Confirmation Details */}
            <div className="lg:col-span-2 space-y-6">
              <div className="bg-white border border-gray-200 rounded-sm shadow-sm overflow-hidden">
                <div className="p-6 border-b border-gray-100 bg-gray-50/50">
                  <h3 className="text-sm font-bold text-primary-navy tracking-wider uppercase">
                    Chi tiết xác nhận
                  </h3>
                </div>

                <div className="p-8 grid grid-cols-1 md:grid-cols-2 gap-8 text-sm">
                  {/* Customer Info */}
                  <div className="space-y-4">
                    <h4 className="text-[10px] font-bold text-[#80632F] tracking-widest uppercase">
                      Thông tin khách hàng
                    </h4>
                    <div className="space-y-3">
                      <div>
                        <p className="text-[10px] text-text-light font-bold uppercase tracking-wider">Họ tên</p>
                        <p className="font-semibold text-text-dark text-base mt-0.5">{fullName || "Not provided"}</p>
                      </div>
                      <div>
                        <p className="text-[10px] text-text-light font-bold uppercase tracking-wider">Email</p>
                        <p className="font-medium text-text-dark mt-0.5">{email || "Not provided"}</p>
                      </div>
                      <div>
                        <p className="text-[10px] text-text-light font-bold uppercase tracking-wider">Số điện thoại</p>
                        <p className="font-medium text-text-dark mt-0.5">{phone || "Not provided"}</p>
                      </div>
                    </div>
                  </div>

                  {/* Room Booking Info */}
                  <div className="space-y-4">
                    <h4 className="text-[10px] font-bold text-[#80632F] tracking-widest uppercase">
                      Thông tin đặt phòng
                    </h4>
                    <div className="space-y-3">
                      <div>
                        <p className="text-[10px] text-text-light font-bold uppercase tracking-wider">Phòng</p>
                        <p className="font-semibold text-text-dark text-base mt-0.5">{bookingData.roomName}</p>
                      </div>
                      <div className="grid grid-cols-2 gap-4">
                        <div>
                          <p className="text-[10px] text-text-light font-bold uppercase tracking-wider">Nhận phòng</p>
                          <p className="font-medium text-text-dark mt-0.5">{bookingData.checkInDate}</p>
                        </div>
                        <div>
                          <p className="text-[10px] text-text-light font-bold uppercase tracking-wider">Trả phòng</p>
                          <p className="font-medium text-text-dark mt-0.5">{bookingData.checkOutDate}</p>
                        </div>
                      </div>
                      <div>
                        <p className="text-[10px] text-text-light font-bold uppercase tracking-wider">Số khách</p>
                        <p className="font-medium text-text-dark mt-0.5">{bookingData.adultsCount} người lớn, {bookingData.childrenCount} trẻ em</p>
                      </div>
                    </div>
                  </div>
                </div>

                {/* Navy Total Bar */}
                <div className="bg-primary-navy p-6 text-white flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2">
                  <span className="text-sm font-bold tracking-wider uppercase">{localize("Tổng cộng", "Total")}</span>
                  <div className="text-right">
                    <span className="text-xl sm:text-2xl font-bold text-accent-gold">{formatVND(total)}</span>
                    <p className="text-[10px] text-white/50 uppercase tracking-widest font-semibold mt-0.5">
                      {paymentPlan === "PREPAY_100" ? "Thanh toán trước 100%" : "Đặt cọc 50%"}: {formatVND(amountDueNow)}
                    </p>
                  </div>
                </div>
              </div>

              {/* Action Buttons below card */}
              <div className="flex flex-col sm:flex-row gap-4 pt-2">
                <button 
                  onClick={() => window.print()}
                  className="flex-1 border border-[#80632F] text-[#80632F] hover:bg-[#80632F]/5 px-8 py-3.5 font-bold text-xs tracking-widest flex items-center justify-center gap-2 uppercase rounded-sm transition-colors"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="6 9 6 2 18 2 18 9" />
                    <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2" />
                    <rect x="6" y="14" width="12" height="8" />
                  </svg>
                  In xác nhận
                </button>
                <Link 
                  href="/"
                  className="flex-1 bg-[#B8944F] hover:bg-[#967538] text-[#091E30] px-8 py-3.5 font-bold text-xs tracking-widest flex items-center justify-center gap-2 uppercase rounded-sm transition-colors shadow-sm"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
                    <polyline points="9 22 9 12 15 12 15 22" />
                  </svg>
                  Về trang chủ
                </Link>
              </div>
            </div>

            {/* Right Column: Your Stay Booking Summary */}
            <div className="bg-white border border-gray-200 p-6 rounded-sm shadow-sm space-y-6">
              <div className="flex items-center gap-3 pb-3 border-b border-gray-100">
                <span className="text-xl">🏨</span>
                <div>
                  <h3 className="font-serif text-lg font-bold text-primary-navy">{localize("Kỳ lưu trú của bạn", "Your stay")}</h3>
                  <p className="text-[10px] text-text-light font-medium tracking-wider uppercase">{localize("Tóm tắt đặt phòng", "Booking summary")}</p>
                </div>
              </div>

              <div className="space-y-3 text-xs font-semibold text-text-dark">
                <div className="flex justify-between">
                  <span className="text-text-light font-medium">{localize("Phòng đã chọn", "Selected rooms")}</span>
                  <span>01</span>
                </div>
                <div className="flex justify-between pb-3">
                  <span className="text-text-light font-medium">{localize("Tổng số giờ", "Total hours")}</span>
                  <span>{bookingData.totalHours}</span>
                </div>
                <div className="flex justify-between border-t border-gray-150 pt-3 text-sm font-bold text-primary-navy">
                  <span>Total:</span>
                  <span className="text-base text-accent-gold">{formatVND(total)}</span>
                </div>
              </div>

              {/* Quote block */}
              <div className="bg-gray-50 border border-gray-100 p-4 rounded-sm text-xs italic font-light text-text-dark/80 leading-relaxed">
                &ldquo;Chúng tôi rất hân hạnh được đón tiếp quý khách tại Luxury Hotels. Mọi yêu cầu đặc biệt xin vui lòng liên hệ bộ phận Concierge qua hotline +84 24 1234 5678.&rdquo;
              </div>

              {bookingData.image && (
                <div className="relative h-[180px] overflow-hidden rounded-sm shadow-sm">
                  <ProgressiveImage
                    src={bookingData.image}
                    alt={bookingData.roomName}
                    fill
                    sizes="(min-width: 1024px) 24rem, 100vw"
                    className="object-cover hover:scale-105"
                  />
                </div>
              )}
            </div>

          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-[#F1F0EA] py-6">
      <div className="mx-auto max-w-7xl space-y-5 px-4 sm:px-6">
        
        {/* Step Indicator Header */}
        <div className="flex items-center justify-center gap-3 border-b border-gray-200/70 pb-4 text-[11px] font-semibold text-text-light sm:gap-6 sm:text-sm">
          <div className="flex items-center gap-2">
            <span className="w-6 h-6 rounded-full bg-gray-200 text-text-light flex items-center justify-center text-[10px]">1</span>
            <span>Chọn phòng</span>
          </div>
          <div className="h-px w-10 sm:w-16 bg-gray-200" />
          <div className="flex items-center gap-2 text-primary-navy font-bold">
            <span className="w-6 h-6 rounded-full bg-primary-navy text-white flex items-center justify-center text-[10px]">2</span>
            <span>Thông tin & Thanh toán</span>
          </div>
          <div className="h-px w-10 sm:w-16 bg-gray-200" />
          <div className="flex items-center gap-2">
            <span className="w-6 h-6 rounded-full bg-gray-200 text-text-light flex items-center justify-center text-[10px]">3</span>
            <span>Xác nhận</span>
          </div>
        </div>

        {/* Two Columns Grid */}
        <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,3fr)_minmax(280px,1fr)]">
          
          {/* Left Columns - Forms */}
          <div className="space-y-5">
            
            {/* Customer Details Form */}
            <div className="space-y-4 rounded-xl border border-[#0F2A43]/10 bg-white p-5 shadow-sm sm:p-6">
              <h3 className="border-b border-gray-100 pb-3 font-serif text-xl font-bold text-primary-navy">
                Thông tin khách hàng
              </h3>
              {isAuthenticated && (
                <p className="rounded-lg border border-blue-100 bg-blue-50 px-4 py-3 text-sm font-medium text-blue-800">
                  Thông tin được lấy từ tài khoản đang đăng nhập. Bạn có thể cập nhật tại trang cài đặt tài khoản.
                </p>
              )}
              
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div>
                  <label className="block text-xs font-bold text-text-dark uppercase tracking-wider mb-2">Họ và tên *</label>
                  <input 
                    type="text" 
                    placeholder="Nguyễn Văn A" 
                    value={fullName}
                    onChange={(e) => { setFullName(e.target.value.slice(0, 100)); setPaymentError(""); }}
                    readOnly={isAuthenticated || Boolean(pendingReservation)}
                    minLength={2}
                    maxLength={100}
                    autoComplete="name"
                    className="w-full border border-gray-300 px-4 py-3 rounded-sm focus:outline-none focus:ring-2 focus:ring-accent-gold/50 focus:border-accent-gold text-sm font-medium"
                    required
                  />
                </div>
                <div>
                  <label className="block text-xs font-bold text-text-dark uppercase tracking-wider mb-2">Email *</label>
                  <input 
                    type="email" 
                    placeholder="email@example.com" 
                    value={email}
                    onChange={(e) => { setEmail(e.target.value.slice(0, 254)); setPaymentError(""); }}
                    readOnly={isAuthenticated || Boolean(pendingReservation)}
                    maxLength={254}
                    autoComplete="email"
                    className="w-full border border-gray-300 px-4 py-3 rounded-sm focus:outline-none focus:ring-2 focus:ring-accent-gold/50 focus:border-accent-gold text-sm font-medium"
                    required
                  />
                </div>
                <div>
                  <label className="block text-xs font-bold text-text-dark uppercase tracking-wider mb-2">Số điện thoại *</label>
                  <input 
                    type="tel" 
                    placeholder="+84 ..." 
                    value={phone}
                    onChange={(e) => { setPhone(e.target.value.slice(0, 24)); setPaymentError(""); }}
                    readOnly={isAuthenticated || Boolean(pendingReservation)}
                    inputMode="tel"
                    maxLength={24}
                    autoComplete="tel"
                    className="w-full border border-gray-300 px-4 py-3 rounded-sm focus:outline-none focus:ring-2 focus:ring-accent-gold/50 focus:border-accent-gold text-sm font-medium"
                    required
                  />
                </div>
                <div>
                  <label className="block text-xs font-bold text-text-dark uppercase tracking-wider mb-2">Quốc gia</label>
                  <select 
                    value={country}
                    onChange={(e) => setCountry(e.target.value)}
                    disabled={isAuthenticated || Boolean(pendingReservation)}
                    className="w-full border border-gray-300 px-4 py-3 rounded-sm focus:outline-none focus:ring-2 focus:ring-accent-gold/50 focus:border-accent-gold text-sm font-medium bg-transparent"
                  >
                    <option value="Việt Nam">Việt Nam</option>
                    <option value="Mỹ">Mỹ</option>
                    <option value="Nhật Bản">Nhật Bản</option>
                    <option value="Hàn Quốc">Hàn Quốc</option>
                  </select>
                </div>
              </div>

              <div>
                <label className="block text-xs font-bold text-text-dark uppercase tracking-wider mb-2">Yêu cầu đặc biệt</label>
                <textarea 
                  rows={2}
                  placeholder="Ví dụ: Phòng tầng cao, check-in sớm..." 
                  value={specialRequest}
                  onChange={(e) => { setSpecialRequest(e.target.value.slice(0, 500)); setPaymentError(""); }}
                  readOnly={Boolean(pendingReservation)}
                  maxLength={500}
                  className="w-full border border-gray-300 px-4 py-3 rounded-sm focus:outline-none focus:ring-2 focus:ring-accent-gold/50 focus:border-accent-gold text-sm font-medium"
                />
                <span className="mt-1 block text-right text-[10px] font-medium text-text-light">{specialRequest.length}/500</span>
              </div>
            </div>

            <div className="grid items-start gap-5 xl:grid-cols-2">
            {/* Payment Method Selector */}
            <div className="space-y-4 rounded-xl border border-[#0F2A43]/10 bg-white p-5 shadow-sm sm:p-6">
              <h3 className="border-b border-gray-100 pb-3 font-serif text-xl font-bold text-primary-navy">
                {localize("Phương thức thanh toán", "Payment method")}
              </h3>
              <fieldset disabled={Boolean(pendingReservation)}>
                <legend className="mb-3 text-xs font-bold uppercase tracking-wider text-text-dark">
                  {localize("Số tiền thanh toán trước", "Prepayment amount")}
                </legend>
                <div className="grid gap-3 sm:grid-cols-2">
                  {([
                    ["DEPOSIT_50", localize("Đặt cọc 50%", "50% deposit"), formatVND(deposit50)],
                    ["PREPAY_100", localize("Thanh toán 100%", "Pay 100% now"), formatVND(total)],
                  ] as const).map(([value, label, amount]) => (
                    <label key={value} className={`cursor-pointer rounded-xl border p-4 transition ${paymentPlan === value ? "border-[#80632F] bg-[#F0EADF] ring-1 ring-[#80632F]/40" : "border-[#0F2A43]/10 bg-white hover:border-[#80632F]/50"}`}>
                      <input type="radio" name="paymentPlan" value={value} checked={paymentPlan === value} onChange={() => setPaymentPlan(value)} className="sr-only" />
                      <span className="block text-sm font-bold text-primary-navy">{label}</span>
                      <span className="mt-1 block text-lg font-black tabular-nums text-[#80632F]">{amount}</span>
                    </label>
                  ))}
                </div>
              </fieldset>
              <div className="flex items-center justify-between gap-4 rounded-sm border border-[#80632F] bg-[#F0EADF]/30 p-4 ring-1 ring-[#80632F]">
                <div className="flex min-w-0 items-center gap-3">
                  <div className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full border-2 border-[#80632F]" aria-hidden="true">
                    <div className="h-2.5 w-2.5 rounded-full bg-[#80632F]" />
                  </div>
                  <div>
                    <p className="text-sm font-bold text-primary-navy">Thanh toán QR</p>
                    <p className="mt-0.5 text-[11px] font-medium text-text-light">
                      {localize("Quét mã bằng ứng dụng ngân hàng để chuyển khoản", "Scan with your banking app to transfer")}
                    </p>
                  </div>
                </div>
                <span className="shrink-0 rounded border border-primary-navy/15 bg-white px-2 py-1 text-[10px] font-black text-primary-navy">VIETQR</span>
              </div>
            </div>

            <div className="space-y-4 rounded-xl border border-[#0F2A43]/10 bg-white p-5 shadow-sm sm:p-6">
              <h3 className="border-b border-gray-100 pb-3 font-serif text-xl font-bold text-primary-navy">
                Xác nhận điều khoản
              </h3>
              <p className="text-sm text-text-light font-medium leading-relaxed">
                {localize(
                  "Bạn sẽ được chuyển tới trang thanh toán QR để quét mã và theo dõi trạng thái an toàn.",
                  "You will continue to the QR payment page to scan the code and securely track payment status."
                )}
              </p>
              <label className="flex items-start gap-3 text-xs text-text-light font-medium cursor-pointer pt-2">
                <input
                  type="checkbox"
                  checked={agree}
                  onChange={(e) => {
                    setAgree(e.target.checked);
                    if (e.target.checked) setPaymentError("");
                  }}
                  className="mt-0.5 rounded border-gray-300 text-accent-gold focus:ring-accent-gold"
                  required
                />
                <span>
                  Tôi đã đọc và đồng ý với các <Link href="/terms" className="text-accent-gold hover:underline">Điều khoản & Điều kiện</Link> và <Link href="/privacy" className="text-accent-gold hover:underline">Chính sách bảo mật</Link> của Luxury Hotel.
                </span>
              </label>
              {paymentError && (
                <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-xs font-medium leading-5 text-rose-800">
                  {paymentError}
                </p>
              )}
              {pendingReservation && (
                <p className="rounded-lg border border-sky-200 bg-sky-50 px-4 py-3 text-xs font-medium leading-5 text-sky-900">
                  {localize(
                    `Phiên ${pendingReservation.reservationCode} đã được tạo. Thử lại thanh toán sẽ dùng đúng phiên này, không tạo trùng đơn.`,
                    `Reservation ${pendingReservation.reservationCode} was created. Retrying payment reuses this reservation and will not create a duplicate.`
                  )}
                </p>
              )}
            </div>
            </div>

          </div>

          {/* Right Column - Booking Summary */}
          <div className="sticky top-24 space-y-4 rounded-xl border border-[#0F2A43]/10 bg-white p-5 shadow-md">
            <h3 className="font-sans text-lg font-bold text-primary-navy pb-3 border-b border-gray-100">
              Tóm tắt đặt phòng
            </h3>

            {/* Room Info */}
            <div className="flex gap-4">
              <div className="relative h-16 w-24 shrink-0 overflow-hidden rounded-sm border border-gray-100 bg-[#E5E9ED]">
                {bookingData.image ? (
                  <ProgressiveImage src={bookingData.image} alt={bookingData.roomName} fill sizes="6rem" className="object-cover" />
                ) : (
                  <span className="flex h-full items-center justify-center text-[10px] font-semibold text-[#66727C]">{localize("Chưa có ảnh", "No image")}</span>
                )}
              </div>
              <div className="min-w-0">
                <h4 className="font-serif text-base font-bold text-primary-navy truncate">{bookingData.roomName}</h4>
                <p className="text-xs text-text-light font-medium mt-0.5">Diện tích: {bookingData.size}</p>
              </div>
            </div>

            {/* Dates & Guests */}
            <div className="grid grid-cols-2 gap-4 border-t border-b border-gray-100 py-4 text-xs font-semibold text-text-dark">
              <div>
                <p className="text-text-light font-medium uppercase tracking-wider mb-1">Ngày đến</p>
                <p>{formatDateTimeVietnamese(bookingData.checkInDate)}</p>
              </div>
              <div>
                <p className="text-text-light font-medium uppercase tracking-wider mb-1">Ngày đi</p>
                <p>{formatDateTimeVietnamese(bookingData.checkOutDate)}</p>
              </div>
              <div className="col-span-2 pt-2 border-t border-gray-100/50">
                <p className="text-text-light font-medium uppercase tracking-wider mb-1">Khách</p>
                <p>{bookingData.adultsCount} Người lớn, {bookingData.childrenCount} Trẻ em</p>
              </div>
            </div>

            {/* Prices details */}
              <div className="space-y-2 text-sm">
              <div className="flex justify-between text-text-light font-medium">
                <span>Giá phòng ({bookingData.totalHours} giờ)</span>
                <span>{formatVND(total)}</span>
              </div>
              <div className="flex justify-between text-text-light font-medium">
                <span>{paymentPlan === "PREPAY_100" ? "Thanh toán trước 100%" : "Đặt cọc 50%"}</span>
                <span>{formatVND(amountDueNow)}</span>
              </div>
              <div className="flex justify-between border-t border-gray-200 pt-3 text-base font-bold text-primary-navy">
                <span>THANH TOÁN HÔM NAY</span>
                <span className="text-accent-gold">{formatVND(amountDueNow)}</span>
              </div>
            </div>

            <button 
              type="button"
              onClick={handleRequestBooking}
              disabled={isSubmitting}
              className="mt-2 w-full rounded-lg bg-[#80632F] py-3 text-sm font-bold uppercase tracking-widest text-white shadow-sm transition-colors hover:bg-[#735630] disabled:cursor-wait disabled:opacity-60"
            >
              {isSubmitting ? localize("Đang xử lý...", "Processing...") : localize("Đặt phòng", "Book now")}
            </button>

            <p className="text-center text-[10px] text-text-light font-medium">
              Thanh toán thành công sẽ tạo đơn DRAFT; đơn chỉ được CONFIRMED sau khi khách sạn xác nhận.
            </p>
          </div>

        </div>

      </div>

      <ViewportModal open={isConfirmationOpen} onClose={() => setIsConfirmationOpen(false)} labelledBy="booking-confirmation-title" busy={isSubmitting} panelClassName="max-w-xl" testId="booking-confirmation-modal">
          <section className="flex min-h-0 flex-1 flex-col bg-[#FBFAF6]">
            <header className="bg-[#091E30] px-6 py-5 text-white sm:px-7">
              <p className="text-xs font-bold uppercase tracking-[0.18em] text-[#D8C398]">{localize("Kiểm tra lần cuối", "Final review")}</p>
              <h2 id="booking-confirmation-title" className="mt-2 font-serif text-2xl font-bold">{localize("Xác nhận đặt phòng", "Confirm reservation")}</h2>
            </header>
            <div className="lux-scrollbar min-h-0 flex-1 space-y-5 overflow-y-auto px-6 py-6 sm:px-7">
              <div className="rounded-xl border border-[#0F2A43]/12 bg-[#E5E9ED] p-4">
                <p className="font-bold text-[#091E30]">{bookingData.roomName}</p>
                <dl className="mt-3 grid gap-3 text-sm sm:grid-cols-2">
                  <div><dt className="text-xs font-semibold text-[#66727C]">{localize("Nhận phòng", "Check-in")}</dt><dd className="mt-1 font-bold">{formatDateTimeVietnamese(bookingData.checkInDate)}</dd></div>
                  <div><dt className="text-xs font-semibold text-[#66727C]">{localize("Trả phòng", "Check-out")}</dt><dd className="mt-1 font-bold">{formatDateTimeVietnamese(bookingData.checkOutDate)}</dd></div>
                </dl>
              </div>
              <div className="flex items-end justify-between gap-4 border-b border-[#0F2A43]/10 pb-4">
                <div>
                  <p className="text-xs font-semibold uppercase tracking-[0.14em] text-[#66727C]">{paymentPlan === "PREPAY_100" ? localize("Trả trước 100%", "Pay 100% now") : localize("Đặt cọc 50%", "50% deposit")}</p>
                  <p className="mt-1 text-sm text-[#66727C]">Thanh toán QR</p>
                </div>
                <strong className="text-xl tabular-nums text-[#80632F]">{formatVND(amountDueNow)}</strong>
              </div>
              <p className="text-sm leading-6 text-[#66727C]">{localize("Sau khi xác nhận, hệ thống tạo đơn và chuyển bạn đến mã QR thanh toán. Hãy kiểm tra đúng thời gian, số phòng và số tiền trước khi tiếp tục.", "After confirmation, we create the reservation and open its payment QR. Check the stay time, room quantity, and amount before continuing.")}</p>
              {paymentError && <p role="alert" className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm font-medium text-rose-700">{paymentError}</p>}
            </div>
            <footer className="flex flex-col-reverse gap-3 border-t border-[#0F2A43]/10 bg-white px-6 py-4 sm:flex-row sm:justify-end sm:px-7">
              <button type="button" disabled={isSubmitting} onClick={() => setIsConfirmationOpen(false)} className="min-h-11 rounded-xl border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43] hover:bg-[#E5E9ED] disabled:opacity-50">{localize("Quay lại", "Go back")}</button>
              <button type="button" disabled={isSubmitting} onClick={() => void handleConfirmBooking()} className="min-h-11 rounded-xl bg-[#0F2A43] px-5 text-sm font-bold text-white hover:bg-[#091E30] disabled:cursor-wait disabled:opacity-60">{isSubmitting ? localize("Đang xử lý...", "Processing...") : localize("Xác nhận đặt phòng", "Confirm reservation")}</button>
            </footer>
          </section>
      </ViewportModal>
    </div>
  );
}

export default function BookingPage() {
  return (
    <Suspense fallback={
      <div className="min-h-screen flex items-center justify-center bg-[#F1F0EA]">
        <div className="flex flex-col items-center gap-4">
          <div className="w-12 h-12 border-4 border-primary-navy border-t-accent-gold rounded-full animate-spin"></div>
          <p className="text-primary-navy font-semibold">Đang chuẩn bị thanh toán QR...</p>
        </div>
      </div>
    }>
      <BookingFormContent />
    </Suspense>
  );
}
