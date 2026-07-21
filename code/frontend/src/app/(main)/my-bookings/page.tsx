"use client";

import React, { useCallback, useEffect, useState } from "react";
import axios from "axios";
import dynamic from "next/dynamic";
import Link from "next/link";
import { apiClient, authSession } from "@/lib/api";
import type { ReservationInvoice } from "@/components/reservations/ReservationInvoiceModal";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import RefundRecipientForm, {
  type RefundDestinationStatus,
  type RefundRoute,
} from "@/components/refunds/RefundRecipientForm";
import RefundProgressCard, { type CustomerRefund } from "@/components/refunds/RefundProgressCard";
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from "@/lib/idempotency";
import GuestPageHero from "@/components/guest/GuestPageHero";
import BankAccountFields from "@/components/forms/BankAccountFields";
import { GALLERY_HERO_IMAGES } from "@/constants/content";

const ReservationInvoiceModal = dynamic(
  () => import("@/components/reservations/ReservationInvoiceModal"),
  { ssr: false },
);

interface Booking {
  id: number;
  bookingId: string;
  roomName: string;
  checkInDate: string;
  checkOutDate: string;
  totalAmount: number;
  status: "DRAFT" | "CONFIRMED" | "CANCELLATION_PENDING" | "CANCELLED" | "CHECKED_IN" | "CHECKED_OUT" | "NO_SHOW";
  guestCount: string;
  createdAt: string;
  cancellationReason?: string;
  cancellationRefundPending?: boolean;
  refundableAmount?: number;
  refundRoute?: RefundRoute;
  refundDestinationStatus?: RefundDestinationStatus;
  refundBankSummary?: string;
  refunds: CustomerRefund[];
  roomTypes: Array<{ roomTypeId: number; roomTypeName: string; roomTypeNameEn?: string }>;
}

interface MyReview { id: number; reservationId: number; roomTypeId: number; rating: number; comment?: string; }

interface ApiReservationRoomType {
  roomTypeId: number;
  roomTypeName: string;
  roomTypeNameEn?: string;
}

interface ApiReservation {
  id: number;
  reservationCode: string;
  checkIn?: string;
  checkOut?: string;
  totalAmount: number;
  status: Booking["status"];
  guestCount?: number;
  createdAt: string;
  cancellationReason?: string;
  cancellationRefundPending?: boolean;
  refundableAmount?: number;
  refundRoute?: RefundRoute;
  refundDestinationStatus?: RefundDestinationStatus;
  refundBankSummary?: string;
  refunds?: CustomerRefund[];
  roomTypes?: ApiReservationRoomType[];
}

const getApiErrorMessage = (error: unknown, fallback: string) =>
  axios.isAxiosError<{ message?: string }>(error)
    ? error.response?.data?.message || fallback
    : fallback;

export default function MyBookingsPage() {
  const { locale, localeTag, localize } = useLanguage();
  const [bookings, setBookings] = useState<Booking[]>([]);
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [myReviews, setMyReviews] = useState<MyReview[]>([]);
  const [reviewTarget, setReviewTarget] = useState<{ booking: Booking; roomTypeId: number; roomTypeName: string; existing?: MyReview } | null>(null);
  const [reviewRating, setReviewRating] = useState(5);
  const [reviewComment, setReviewComment] = useState("");
  const [isSavingReview, setIsSavingReview] = useState(false);
  const [reviewError, setReviewError] = useState("");
  const [invoice, setInvoice] = useState<ReservationInvoice | null>(null);
  const [invoiceLoadingId, setInvoiceLoadingId] = useState<number | null>(null);

  // Cancel modal states
  const [isCancelModalOpen, setIsCancelModalOpen] = useState(false);
  const [selectedBookingForCancel, setSelectedBookingForCancel] = useState<Booking | null>(null);
  const [cancelReasonCategory, setCancelReasonCategory] = useState("Thay đổi lịch trình");
  const [cancelReasonText, setCancelReasonText] = useState("");
  const [cancelBankCode, setCancelBankCode] = useState("");
  const [cancelBankName, setCancelBankName] = useState("");
  const [cancelAccountNumber, setCancelAccountNumber] = useState("");
  const [cancelAccountHolder, setCancelAccountHolder] = useState("");
  const [cancelError, setCancelError] = useState("");
  const [isCancelling, setIsCancelling] = useState(false);

  const [isLoading, setIsLoading] = useState(true);

  const openInvoice = async (reservationId: number) => {
    setInvoiceLoadingId(reservationId);
    try {
      const response = await apiClient.get(`/api/reservations/${reservationId}/invoice`);
      setInvoice(response.data?.data || null);
    } catch (error: unknown) {
      setLoadError(getApiErrorMessage(error, localize("Không thể tải hóa đơn.", "Could not load the invoice.")));
    } finally {
      setInvoiceLoadingId(null);
    }
  };

  const loadBookings = useCallback(async () => {
    setIsLoading(true);
    setLoadError("");
    try {
      // Access token nằm trong memory và refresh token là cookie HttpOnly,
      // do đó phải khôi phục phiên qua backend thay vì đọc cookie cũ.
      const currentUser = await authSession.getCurrentUser(false);
      if (!currentUser) {
        setIsAuthenticated(false);
        setBookings([]);
        return;
      }

      setIsAuthenticated(true);
      const res = await apiClient.get("/api/reservations/my");
      const reservations: ApiReservation[] = Array.isArray(res.data?.data) ? res.data.data : Array.isArray(res.data) ? res.data : [];
      if (reservations.length > 0) {
        // Backend /reservations/my đã kiểm tra ownership theo user trong JWT.
        const apiBookings: Booking[] = reservations.map((r) => {
          const roomType = r.roomTypes && r.roomTypes.length > 0 ? r.roomTypes[0] : null;
          const refundRoute = r.refundRoute || "NONE";
          
          return {
            bookingId: r.reservationCode,
            roomName: roomType ? localize(roomType.roomTypeName, roomType.roomTypeNameEn) : localize("Phòng được gán khi nhận phòng", "Room assigned at check-in"),
            checkInDate: r.checkIn ? r.checkIn.substring(0, 16) : "",
            checkOutDate: r.checkOut ? r.checkOut.substring(0, 16) : "",
            totalAmount: r.totalAmount,
            status: r.status,
            guestCount: r.guestCount ? localize(`${r.guestCount} khách`, `${r.guestCount} guests`) : "-",
            createdAt: r.createdAt,
            cancellationReason: r.cancellationReason,
            cancellationRefundPending: Boolean(r.cancellationRefundPending),
            refundableAmount: Number(r.refundableAmount || 0),
            refundRoute,
            refundDestinationStatus: r.refundDestinationStatus
              || (refundRoute === "MANUAL_BANK_TRANSFER" || refundRoute === "MIXED" ? "REQUIRED" : "NOT_REQUIRED"),
            refundBankSummary: r.refundBankSummary,
            refunds: Array.isArray(r.refunds) ? r.refunds : [],
            id: r.id,
            roomTypes: Array.isArray(r.roomTypes) ? r.roomTypes.map((item) => ({ roomTypeId: Number(item.roomTypeId), roomTypeName: localize(item.roomTypeName, item.roomTypeNameEn), roomTypeNameEn: item.roomTypeNameEn })) : [],
          };
        });
        setBookings(apiBookings);
      } else {
        setBookings([]);
      }

      // Review là dữ liệu phụ: lỗi hoặc tải chậm không được chặn danh sách reservation.
      void apiClient.get("/api/reviews/my")
        .then((reviewsRes) => {
          setMyReviews(Array.isArray(reviewsRes.data?.data) ? reviewsRes.data.data : []);
        })
        .catch((reviewError) => {
          console.warn("Không thể tải review, vẫn tiếp tục hiển thị booking", reviewError);
          setMyReviews([]);
        });
    } catch (error: unknown) {
      console.error("Lỗi khi tải lịch sử đặt phòng:", error);
      setBookings([]);
      setLoadError(getApiErrorMessage(error, localize("Không thể tải đơn đặt phòng. Vui lòng thử lại.", "We could not load your reservations. Please try again.")));
    } finally {
      setIsLoading(false);
    }
  }, [localize]);

  // Load bookings from API
  useEffect(() => {
    void loadBookings();
  }, [loadBookings]);

  useEffect(() => {
    if (!reviewTarget && !isCancelModalOpen) return;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      if (reviewTarget && !isSavingReview) setReviewTarget(null);
      else if (isCancelModalOpen && !isCancelling) setIsCancelModalOpen(false);
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener("keydown", closeOnEscape);
    };
  }, [isCancelModalOpen, isCancelling, isSavingReview, reviewTarget]);

  // Open Cancel dialog
  const handleOpenCancel = (booking: Booking) => {
    setSelectedBookingForCancel(booking);
    setCancelReasonCategory("Thay đổi lịch trình");
    setCancelReasonText("");
    setCancelBankCode("");
    setCancelBankName("");
    setCancelAccountNumber("");
    setCancelAccountHolder("");
    setCancelError("");
    setIsCancelModalOpen(true);
  };

  // Confirm Cancel (API call)
  const handleConfirmCancel = async () => {
    if (!selectedBookingForCancel) return;
    if (cancelReasonCategory === "Khác" && !cancelReasonText.trim()) {
      setCancelError(localize("Vui lòng nhập chi tiết lý do hủy.", "Please provide cancellation details."));
      return;
    }
    if (!/^[A-Z0-9]{2,20}$/.test(cancelBankCode.trim().toUpperCase())
      || cancelBankName.trim().length < 2
      || !/^\d{6,24}$/.test(cancelAccountNumber)
      || cancelAccountHolder.trim().length < 2) {
      setCancelError(localize("Vui lòng nhập đầy đủ và đúng thông tin tài khoản nhận hoàn tiền.", "Please enter valid refund bank-account details."));
      return;
    }

    setIsCancelling(true);
    setCancelError("");
    const operationScope = `reservation:${selectedBookingForCancel.id}:CANCEL_OWNER`;
    try {
      await apiClient.patch(`/api/reservations/cancel/${selectedBookingForCancel.id}`, {
        cancellationReason: `${cancelReasonCategory}${cancelReasonText.trim() ? `: ${cancelReasonText.trim()}` : ""}`,
        refundRecipient: {
          bankCode: cancelBankCode.trim().toUpperCase(),
          bankName: cancelBankName.trim(),
          accountNumber: cancelAccountNumber,
          accountHolderName: cancelAccountHolder.trim().toLocaleUpperCase("vi-VN"),
        },
      }, {
        headers: { "Idempotency-Key": getOrCreateIdempotencyKey(operationScope) },
      });
      clearIdempotencyKey(operationScope);
      setIsCancelModalOpen(false);
      setSelectedBookingForCancel(null);
      await loadBookings();
    } catch (error: unknown) {
      setCancelError(getApiErrorMessage(error, localize("Không thể hủy đặt phòng. Vui lòng thử lại hoặc liên hệ lễ tân.", "Could not cancel the reservation. Please try again or contact the front desk.")));
    } finally {
      setIsCancelling(false);
    }
  };

  const openReview = (booking: Booking, roomType: Booking["roomTypes"][number]) => {
    const existing = myReviews.find((review) => review.reservationId === booking.id && review.roomTypeId === roomType.roomTypeId);
    setReviewTarget({ booking, ...roomType, existing });
    setReviewRating(existing?.rating || 5);
    setReviewComment(existing?.comment || "");
    setReviewError("");
  };

  const saveReview = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!reviewTarget) return;
    setIsSavingReview(true); setReviewError("");
    try {
      if (reviewTarget.existing) {
        await apiClient.patch(`/api/reviews/${reviewTarget.existing.id}`, { rating: reviewRating, comment: reviewComment.trim() || undefined });
      } else {
        await apiClient.post("/api/reviews", { reservationId: reviewTarget.booking.id, roomTypeId: reviewTarget.roomTypeId, rating: reviewRating, comment: reviewComment.trim() || undefined });
      }
      setReviewTarget(null);
      await loadBookings();
    } catch (error: unknown) {
      setReviewError(getApiErrorMessage(error, localize("Không thể lưu đánh giá. Vui lòng thử lại.", "Could not save your review. Please try again.")));
    } finally { setIsSavingReview(false); }
  };

  const deleteReview = async () => {
    if (!reviewTarget?.existing) return;
    setIsSavingReview(true);
    try { await apiClient.delete(`/api/reviews/${reviewTarget.existing.id}`); setReviewTarget(null); await loadBookings(); }
    catch (error: unknown) { setReviewError(getApiErrorMessage(error, localize("Không thể xóa đánh giá.", "Could not delete the review."))); }
    finally { setIsSavingReview(false); }
  };

  const formatVND = (num: number) => {
    if (num == null) return "0 đ";
    return num.toLocaleString(localeTag) + " đ";
  };

  const getStatusLabel = (status: Booking["status"]) => ({
    DRAFT: localize("Chờ đặt cọc", "Awaiting deposit"),
    CONFIRMED: localize("Đã xác nhận", "Confirmed"),
    CANCELLATION_PENDING: localize("Chờ duyệt hủy", "Cancellation pending"),
    CANCELLED: localize("Đã hủy", "Cancelled"),
    CHECKED_IN: localize("Đang lưu trú", "Checked in"),
    CHECKED_OUT: localize("Đã trả phòng", "Checked out"),
    NO_SHOW: localize("Không đến", "No-show"),
  }[status]);

  const getCancellationReason = (reason?: string) => {
    if (!reason) return localize("Không có lý do cụ thể.", "No specific reason provided.");
    if (locale === "vi") return reason;
    const knownReasons: Record<string, string> = {
      "Khách sạn xác nhận hủy theo yêu cầu.": "The hotel confirmed the cancellation request.",
      "Khách sạn xác nhận hủy theo yêu cầu": "The hotel confirmed the cancellation request.",
      "Thay đổi lịch trình": "Schedule changed",
      "Tìm được phòng khác tốt hơn": "Found a better room",
      "Lý do cá nhân": "Personal reason",
      "Khác": "Other",
    };
    const exact = knownReasons[reason];
    if (exact) return exact;
    const matchedPrefix = Object.keys(knownReasons).find((key) => reason.startsWith(`${key}:`));
    return matchedPrefix ? `${knownReasons[matchedPrefix]}:${reason.slice(matchedPrefix.length + 1)}` : reason;
  };

  const getStatusBadgeStyle = (status: Booking["status"]) => {
    switch (status) {
      case "CHECKED_IN":
        return "text-emerald-700 bg-emerald-50 border border-emerald-200";
      case "CHECKED_OUT":
        return "text-gray-700 bg-gray-50 border border-gray-200";
      case "CONFIRMED":
        return "text-blue-700 bg-blue-50 border border-blue-200";
      case "DRAFT":
        return "text-amber-700 bg-amber-50 border border-amber-200";
      case "CANCELLED":
        return "text-rose-700 bg-rose-50 border border-rose-200";
      case "NO_SHOW":
        return "text-orange-700 bg-orange-50 border border-orange-200";
      case "CANCELLATION_PENDING":
        return "text-violet-700 bg-violet-50 border border-violet-200";
    }
  };

  const formatStayDateTime = (value: string) => {
    if (!value) return "—";
    const date = new Date(value);
    return Number.isNaN(date.getTime())
      ? value.replace("T", " ")
      : date.toLocaleString(localeTag, {
          day: "2-digit",
          month: "2-digit",
          year: "numeric",
          hour: "2-digit",
          minute: "2-digit",
        });
  };

  return (
    <div className="min-h-screen bg-[#F1F0EA] pb-24">
      <GuestPageHero
        imageSrc={GALLERY_HERO_IMAGES.bookings}
        imageAlt={localize("Sảnh khách sạn Luxury Hotel", "Luxury Hotel lobby")}
        eyebrow={localize("Đơn của tôi", "My bookings")}
        title={localize("Đơn đặt phòng của tôi", "My reservations")}
        description={localize("Theo dõi kỳ lưu trú, tiền đặt cọc và yêu cầu hủy tại một nơi.", "Track upcoming stays, deposit payments, and cancellation requests in one place.")}
        className="min-h-[52dvh] md:min-h-[520px]"
      />

      <div className="relative z-10 mx-auto max-w-6xl space-y-8 px-4 py-12 sm:px-6 md:py-16">
        
        {/* Editorial Page Header */}
        <div className="grid gap-4 md:grid-cols-3">
          <div className="rounded-[1.5rem] bg-[#FBFAF6] p-5 shadow-sm">
            <p className="text-xs font-bold uppercase tracking-[0.18em] text-[#66727C]">{localize("Tổng số", "Total")}</p>
            <p className="mt-2 font-serif text-3xl font-bold text-primary-navy">{bookings.length}</p>
          </div>
          <div className="rounded-[1.5rem] bg-[#EAE2D2] p-5 shadow-sm">
            <p className="text-xs font-bold uppercase tracking-[0.18em] text-[#66727C]">{localize("Đang hoạt động", "Active")}</p>
            <p className="mt-2 font-serif text-3xl font-bold text-emerald-700">{bookings.filter((item) => item.status === "CONFIRMED" || item.status === "DRAFT").length}</p>
          </div>
          <Link href="/reservation" className="rounded-[1.5rem] bg-[#B8944F] p-5 text-[#0F2A43] shadow-sm transition hover:bg-[#967538]">
            <p className="text-xs font-bold uppercase tracking-[0.18em]">{localize("Cần đặt thêm kỳ nghỉ?", "Need another stay?")}</p>
            <p className="mt-2 font-serif text-2xl font-bold">{localize("Đặt ngay", "Reserve now")} →</p>
          </Link>
        </div>

        {/* Bookings List */}
        {isLoading ? (
          <div className="flex justify-center items-center py-20">
            <div className="w-10 h-10 border-4 border-primary-navy border-t-accent-gold rounded-full animate-spin"></div>
          </div>
        ) : !isAuthenticated ? (
          <div className="bg-white border border-[#0F2A43]/10 rounded-[2rem] p-12 text-center space-y-5 shadow-sm">
            <span className="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-[#F1F0EA] text-2xl font-serif text-[#B8944F]">L</span>
            <h3 className="font-serif text-2xl font-bold text-primary-navy">{localize("Đăng nhập để xem các đơn đặt phòng", "Sign in to view your reservations")}</h3>
            <p className="text-sm text-text-light font-medium max-w-md mx-auto">
              {localize("Trang này chỉ hiển thị các đơn thuộc tài khoản của bạn.", "My Bookings only shows reservations that belong to your account.")}
            </p>
            <div className="flex flex-wrap justify-center gap-3">
              <Link
                href="/login"
                className="inline-block bg-[#C8A35B] hover:bg-[#cda45e] text-white px-6 py-3 text-xs font-bold tracking-widest uppercase transition-colors rounded-[1.25rem] shadow-sm"
              >
                {localize("Đăng nhập", "Log in")}
              </Link>
              <Link
                href="/rooms"
                className="inline-block border border-[#0F2A43]/10 bg-white hover:bg-[#F1F0EA] text-primary-navy px-6 py-3 text-xs font-bold tracking-widest uppercase transition-colors rounded-[1.25rem] shadow-sm"
              >
                {localize("Xem phòng", "Browse rooms")}
              </Link>
            </div>
          </div>
        ) : bookings.length === 0 ? (
          <div className="bg-white border border-gray-200 rounded-[2rem] p-12 text-center space-y-4 shadow-sm">
            <span className="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-[#F1F0EA] text-3xl text-[#B8944F]">∅</span>
            <h3 className="font-serif text-xl font-bold text-primary-navy">
              {loadError ? localize("Không thể tải đơn đặt phòng", "Reservations unavailable") : localize("Tài khoản chưa có đơn đặt phòng", "No reservations for your account")}
            </h3>
            <p className="text-sm text-text-light font-medium max-w-sm mx-auto">
              {loadError || localize("Đơn được tạo khi bạn đăng nhập sẽ tự động xuất hiện tại đây.", "When you create a reservation while signed in, it will appear here automatically.")}
            </p>
            <Link
              href="/rooms"
              className="inline-block bg-[#C8A35B] hover:bg-[#cda45e] text-white px-6 py-3 text-xs font-bold tracking-widest uppercase transition-colors rounded-[1.25rem] shadow-sm"
            >
              {localize("Đặt phòng", "Book a room")}
            </Link>
          </div>
        ) : (
          <div className="space-y-6">
            {bookings.map((booking) => {
              const isCancellationRefundPending = Boolean(booking.cancellationRefundPending);
              const isImmutable = isCancellationRefundPending || booking.status === "CHECKED_IN" || booking.status === "CHECKED_OUT" || booking.status === "CANCELLED" || booking.status === "NO_SHOW" || booking.status === "CANCELLATION_PENDING";
              
              return (
                <div
                  key={booking.bookingId}
                  className="bg-[#FBFAF6] border border-[#0F2A43]/10 rounded-[2rem] shadow-sm overflow-hidden flex flex-col md:flex-row justify-between items-stretch transition duration-300 hover:-translate-y-1 hover:shadow-xl"
                >
                  {/* Left Side: Stay summary */}
                  <div className="p-6 sm:p-8 flex-1 space-y-4 min-w-0">
                    <div className="flex flex-wrap items-center gap-3">
                      <h3 className="font-serif text-lg sm:text-xl font-bold text-primary-navy truncate">
                        {booking.roomName}
                      </h3>
                      <span className={`text-[10px] font-bold px-3 py-1 rounded-full ${getStatusBadgeStyle(booking.status)}`}>
                        {getStatusLabel(booking.status)}
                      </span>
                      {isCancellationRefundPending && (
                        <span className="rounded-full border border-amber-300 bg-amber-50 px-3 py-1 text-[10px] font-bold uppercase tracking-wider text-amber-900">
                          {booking.refundRoute === "CASH_AT_COUNTER"
                            ? localize("Chờ giao tiền hoàn", "Awaiting cash handover")
                            : localize("Chờ thông tin hoàn QR", "Awaiting QR refund details")}
                        </span>
                      )}
                    </div>

                    <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 text-xs">
                      <div className="rounded-[1rem] bg-[#EAE2D2] p-3">
                        <p className="text-[10px] text-text-light font-bold uppercase tracking-wider mb-1">{localize("Mã đặt phòng", "Booking code")}</p>
                        <p className="font-bold text-primary-navy tracking-wide text-sm">{booking.bookingId}</p>
                      </div>
                      <div className="rounded-[1rem] bg-[#EAE2D2] p-3">
                        <p className="text-[10px] text-text-light font-bold uppercase tracking-wider mb-1">{localize("Thời gian lưu trú", "Stay dates")}</p>
                        <p className="font-semibold leading-5 text-text-dark">{formatStayDateTime(booking.checkInDate)} → {formatStayDateTime(booking.checkOutDate)}</p>
                      </div>
                      <div className="rounded-[1rem] bg-[#EAE2D2] p-3">
                        <p className="text-[10px] text-text-light font-bold uppercase tracking-wider mb-1">{localize("Số khách", "Guests")}</p>
                        <p className="font-semibold text-text-dark">{booking.guestCount}</p>
                      </div>
                      <div className="rounded-[1rem] bg-[#EAE2D2] p-3">
                        <p className="text-[10px] text-text-light font-bold uppercase tracking-wider mb-1">{localize("Tổng tiền", "Total")}</p>
                        <p className="font-bold text-accent-gold text-sm">{formatVND(booking.totalAmount)}</p>
                      </div>
                    </div>

                    {booking.status === "CANCELLED" && (
                      <div className="bg-red-50/55 border border-red-150 rounded-sm p-3 text-xs text-red-950 font-medium">
                        <strong>{localize("Chi tiết hủy", "Cancellation details")}:</strong> {getCancellationReason(booking.cancellationReason)}
                      </div>
                    )}
                    {booking.status === "CANCELLATION_PENDING" && !isCancellationRefundPending && <div className="rounded-lg border border-violet-200 bg-violet-50 p-3 text-xs font-medium text-violet-800">{localize("Yêu cầu hủy đang chờ khách sạn xác nhận. Phòng vẫn được giữ cho đến khi có quyết định.", "Your cancellation request is awaiting hotel confirmation. The room remains held until a decision is made.")}</div>}
                    {isCancellationRefundPending && (
                      <div className="rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950" role="status">
                        <p className="font-bold">{booking.refundRoute === "CASH_AT_COUNTER"
                          ? localize("Khách sạn đang xử lý hoàn tiền mặt", "The hotel is processing a cash refund")
                          : localize("Khách sạn đang chờ thông tin tài khoản nhận hoàn tiền", "The hotel is waiting for your refund bank details")}</p>
                        <p className="mt-1 leading-6">
                          {booking.refundRoute === "CASH_AT_COUNTER"
                            ? localize("Đơn vẫn giữ trạng thái hiện tại cho đến khi staff giao tiền trực tiếp và bấm xác nhận hoàn tất. Bạn không cần cung cấp tài khoản, ảnh minh chứng hoặc mã giao dịch.", "The reservation keeps its current status until staff hands over the cash and confirms completion. You do not need to provide bank details, proof, or a transaction code.")
                            : booking.refundDestinationStatus === "REQUIRED"
                            ? localize("Staff đã tạo yêu cầu hủy và hoàn qua QR. Vui lòng nhập ngân hàng, số tài khoản và họ tên chủ tài khoản ở biểu mẫu bên dưới. Staff chỉ có thể tiếp tục chuyển khoản sau khi nhận đủ thông tin này.", "Staff created a cancellation with a QR refund. Enter the bank, account number, and account holder name below. Staff cannot continue the transfer until these details are received.")
                            : localize("Thông tin nhận tiền đã được gửi. Đơn vẫn giữ trạng thái hiện tại trong khi khách sạn chuyển khoản và chờ ngân hàng xác nhận tự động theo đúng mã hoàn và số tiền.", "Your payout details were submitted. The reservation keeps its current status while the hotel transfers the refund and the bank confirms the exact refund code and amount automatically.")}
                        </p>
                      </div>
                    )}
                    <RefundProgressCard refunds={booking.refunds} />
                    {(booking.refundRoute === "VNPAY_ORIGINAL" || booking.refundRoute === "MIXED") && (
                      <div className="rounded-xl border border-sky-200 bg-sky-50 p-4 text-sm text-sky-900">
                        <p className="font-bold">{localize("Hoàn về phương thức thanh toán ban đầu", "Refund to the original payment method")}</p>
                        <p className="mt-1 leading-6">{booking.refundRoute === "MIXED"
                          ? localize("Phần giao dịch lịch sử sẽ hoàn theo nguồn gốc; phần hoàn QR thủ công cần tài khoản ngân hàng bên dưới.", "The legacy transaction portion returns to its original source; the manual QR portion requires the bank account below.")
                          : localize("Khoản hoàn thuộc giao dịch lịch sử sẽ được xử lý theo nguồn gốc. Bạn không cần cung cấp tài khoản ngân hàng.", "The legacy refund will be processed against its original transaction. No bank details are required.")}</p>
                      </div>
                    )}
                    {(booking.refundRoute === "MANUAL_BANK_TRANSFER" || booking.refundRoute === "MIXED") && (
                      <RefundRecipientForm
                        reservationId={booking.id}
                        route={booking.refundRoute}
                        status={booking.refundDestinationStatus}
                        bankSummary={booking.refundBankSummary}
                        onSaved={(recipient) => setBookings((current) => current.map((item) => item.id === booking.id
                          ? {
                              ...item,
                              refundDestinationStatus: recipient.refundDestinationStatus || "SUBMITTED",
                              refundBankSummary: recipient.refundBankSummary,
                            }
                          : item))}
                      />
                    )}
                  </div>

                  {/* Right Side: Action panel */}
                  <div className="bg-[#101417] border-t md:border-t-0 md:border-l border-gray-250 p-6 shrink-0 flex flex-col justify-center items-center gap-3 w-full md:w-64 text-white">
                    {booking.status === "CHECKED_OUT" ? (
                      <div className="w-full space-y-3">
                        <button type="button" onClick={() => openInvoice(booking.id)} disabled={invoiceLoadingId === booking.id} className="w-full rounded-lg bg-[#B8944F] px-3 py-3 text-xs font-bold text-[#0F2A43] transition hover:bg-[#D3B87D] disabled:opacity-60">
                          {invoiceLoadingId === booking.id ? localize("Đang tải hóa đơn...", "Loading invoice...") : localize("In hóa đơn", "Print invoice")}
                        </button>
                        <p className="text-center text-xs font-bold uppercase tracking-wider text-[#80632F]">{localize("Đánh giá kỳ nghỉ", "Review your stay")}</p>
                        {booking.roomTypes.map((roomType) => {
                          const reviewed = myReviews.some((review) => review.reservationId === booking.id && review.roomTypeId === roomType.roomTypeId);
                          return <button key={roomType.roomTypeId} type="button" onClick={() => openReview(booking, roomType)} className="w-full rounded-xl border border-white/20 bg-white/10 px-3 py-3 text-left text-xs font-semibold transition hover:bg-white/15"><span className="block truncate">{roomType.roomTypeName}</span><span className="mt-1 block text-[10px] text-white/55">{reviewed ? localize("Chỉnh sửa đánh giá", "Edit review") : localize("Viết đánh giá", "Write a review")}</span></button>;
                        })}
                      </div>
                    ) : isImmutable ? (
                      <div className="text-center space-y-1 py-4">
                        <span className="text-xs text-white/70 font-semibold uppercase tracking-wider block">{localize("Trạng thái", "Status")}</span>
                        <p className="text-[11px] text-white/55 font-medium max-w-[200px] leading-relaxed">
                          {isCancellationRefundPending
                            ? booking.refundRoute === "CASH_AT_COUNTER"
                              ? localize("Đang chờ staff giao tiền mặt và xác nhận. Bạn không cần gửi lại yêu cầu hủy.", "Staff cash handover confirmation is pending. You do not need to submit another cancellation request.")
                              : localize("Đang chờ hoàn tiền qua QR. Bạn không cần gửi lại yêu cầu hủy.", "The QR refund is pending. You do not need to submit another cancellation request.")
                            : booking.status === "CANCELLED"
                            ? localize("Đơn đặt phòng này đã bị hủy.", "This booking has been cancelled.")
                            : localize("Kỳ lưu trú đã đến giai đoạn nhận hoặc trả phòng nên không thể thay đổi tại đây.", "This stay has reached check-in or checkout and can no longer be changed here.")}
                        </p>
                      </div>
                    ) : (
                      <>
                        <Link
                          href="/contact"
                          className="w-full rounded-[1.25rem] border border-white bg-white py-3 text-center text-xs font-bold uppercase tracking-widest text-primary-navy transition-colors hover:bg-[#F1F0EA]"
                        >
                          {localize("Liên hệ", "Contact")}
                        </Link>
                        <button
                          onClick={() => handleOpenCancel(booking)}
                          className="w-full bg-rose-50 hover:bg-rose-100 border border-rose-200 text-rose-700 py-3 text-xs font-bold tracking-widest uppercase transition-colors rounded-[1.25rem]"
                        >
                          {localize("Yêu cầu hủy", "Cancel booking")}
                        </button>
                      </>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}

      </div>

      {reviewTarget && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-[#0F2A43]/72 p-4"
          role="dialog"
          aria-modal="true"
          aria-labelledby="review-modal-title"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget && !isSavingReview) setReviewTarget(null);
          }}
        >
          <form onSubmit={saveReview} className="w-full max-w-lg overflow-hidden rounded-[1.5rem] bg-white shadow-2xl">
            <div className="bg-[#0F2A43] p-6 text-white"><p className="text-[10px] font-bold uppercase tracking-[0.2em] text-[#B8944F]">{reviewTarget.booking.bookingId}</p><h3 id="review-modal-title" className="mt-2 text-2xl font-bold">{reviewTarget.existing ? localize("Chỉnh sửa đánh giá", "Edit review") : localize("Đánh giá kỳ nghỉ", "Review your stay")}</h3><p className="mt-1 text-sm text-white/65">{reviewTarget.roomTypeName}</p></div>
            <div className="space-y-5 p-6">
              {reviewError && <p className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm font-medium text-rose-700">{reviewError}</p>}
              <div><p className="mb-3 text-xs font-bold uppercase tracking-wider text-[#66727C]">{localize("Mức độ hài lòng", "Satisfaction")}</p><div className="flex gap-2">{[1,2,3,4,5].map((star) => <button key={star} type="button" onClick={() => setReviewRating(star)} aria-label={localize(`${star} sao`, `${star} stars`)} className={`flex h-11 w-11 items-center justify-center rounded-xl text-xl transition ${star <= reviewRating ? "bg-[#B8944F] text-[#0F2A43]" : "bg-[#F1F0EA] text-[#9AA0A8]"}`}>★</button>)}</div></div>
              <label className="block"><span className="mb-2 block text-xs font-bold uppercase tracking-wider text-[#66727C]">{localize("Nhận xét", "Comment")}</span><textarea rows={5} maxLength={1000} value={reviewComment} onChange={(event) => setReviewComment(event.target.value)} placeholder={localize("Chia sẻ trải nghiệm thực tế của bạn...", "Share your real experience...")} className="w-full resize-none rounded-xl border border-[#0F2A43]/10 px-4 py-3 text-sm outline-none focus:border-[#B8944F]" /><span className="mt-1 block text-right text-[10px] text-[#66727C]">{reviewComment.length}/1000</span></label>
              <p className="rounded-xl bg-[#E5E9ED] p-3 text-xs font-medium leading-5 text-[#66727C]">{localize("Đánh giá được công khai tại trang chi tiết loại phòng. Chỉ tài khoản sở hữu đơn đã trả phòng mới có thể đánh giá.", "Reviews are public on the room-type detail page. Only the account that owns a checked-out reservation can review it.")}</p>
            </div>
            <div className="flex flex-wrap justify-between gap-3 border-t border-[#0F2A43]/10 bg-[#FBFAF6] p-4">{reviewTarget.existing ? <button type="button" onClick={deleteReview} disabled={isSavingReview} className="rounded-xl px-4 py-2.5 text-xs font-bold text-rose-700 hover:bg-rose-50">{localize("Xóa đánh giá", "Delete review")}</button> : <span />}<div className="flex gap-3"><button type="button" onClick={() => setReviewTarget(null)} className="rounded-xl border border-[#0F2A43]/10 px-4 py-2.5 text-xs font-bold">{localize("Hủy", "Cancel")}</button><button disabled={isSavingReview} className="rounded-xl bg-[#0F2A43] px-5 py-2.5 text-xs font-bold text-white disabled:opacity-50">{isSavingReview ? localize("Đang lưu...", "Saving...") : localize("Lưu đánh giá", "Save review")}</button></div></div>
          </form>
        </div>
      )}

      {/* ───── CANCEL MODAL DIALOG ───── */}
      {invoice && <ReservationInvoiceModal invoice={invoice} onClose={() => setInvoice(null)} />}

      {isCancelModalOpen && selectedBookingForCancel && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-[#0F2A43]/72 p-4 animate-fade-in"
          role="dialog"
          aria-modal="true"
          aria-labelledby="cancel-booking-title"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget && !isCancelling) setIsCancelModalOpen(false);
          }}
        >
          <form onSubmit={(event) => { event.preventDefault(); void handleConfirmCancel(); }} className="max-h-[92vh] w-full max-w-md overflow-y-auto rounded-xl border border-gray-200 bg-white shadow-2xl" noValidate>
            {/* Header */}
            <div className="bg-red-950 p-6 text-white text-center relative">
              <h3 id="cancel-booking-title" className="font-serif text-xl font-bold tracking-wide">{localize("Hủy đặt phòng", "Cancel reservation")}</h3>
              <p className="text-[10px] font-semibold text-accent-gold uppercase tracking-wider mt-1">{localize("Đơn", "Booking")}: {selectedBookingForCancel.bookingId}</p>
            </div>

            {/* Body */}
            <div className="p-6 space-y-5 text-sm">
              
              {/* Penalty Notice */}
              <div className="rounded-sm border border-amber-200 bg-amber-50 p-4 text-amber-950">
                <p className="font-bold text-xs uppercase tracking-wider mb-1">
                  {localize("Chính sách hủy", "Cancellation policy")}:
                </p>
                <p className="text-xs leading-relaxed font-medium">
                  {localize(
                    "Khách sạn sẽ xem xét yêu cầu và xác nhận có hoàn tiền hay không. Phòng vẫn được giữ cho đến khi nhân viên ra quyết định.",
                    "The hotel will review the request and decide whether a refund applies. Your room remains held until staff makes a decision."
                  )}
                </p>
              </div>

              {/* Form Input */}
              <div className="space-y-3">
                <div>
                  <label htmlFor="cancel-reason-category" className="block text-xs font-bold text-text-dark uppercase tracking-wider mb-2">{localize("Lý do hủy", "Cancellation reason")} *</label>
                  <select
                    id="cancel-reason-category"
                    value={cancelReasonCategory}
                    onChange={(e) => { setCancelReasonCategory(e.target.value); setCancelError(""); }}
                    className="w-full border border-gray-300 px-3 py-2 text-sm font-medium rounded-sm focus:outline-none focus:ring-1 focus:ring-[#C8A35B] focus:border-[#C8A35B] bg-transparent"
                  >
                    <option value="Thay đổi lịch trình">{localize("Thay đổi lịch trình", "Schedule changed")}</option>
                    <option value="Tìm được phòng khác tốt hơn">{localize("Tìm được phòng khác tốt hơn", "Found a better room")}</option>
                    <option value="Lý do cá nhân">{localize("Lý do cá nhân", "Personal reason")}</option>
                    <option value="Khác">{localize("Khác...", "Other...")}</option>
                  </select>
                </div>

                {cancelReasonCategory === "Khác" && (
                  <div>
                    <label htmlFor="cancel-reason-detail" className="block text-xs font-bold text-text-dark uppercase tracking-wider mb-2">{localize("Chi tiết lý do khác", "Other reason details")} *</label>
                    <textarea
                      id="cancel-reason-detail"
                      rows={3}
                      placeholder={localize("Vui lòng mô tả lý do...", "Please describe the reason...")}
                      value={cancelReasonText}
                      onChange={(e) => { setCancelReasonText(e.target.value.slice(0, 500)); setCancelError(""); }}
                      maxLength={500}
                      className="w-full border border-gray-300 px-3 py-2 text-sm font-medium rounded-sm focus:outline-none focus:ring-1 focus:ring-[#C8A35B] focus:border-[#C8A35B]"
                      required
                    />
                  </div>
                )}
              </div>

              <section className="rounded-xl border border-amber-200 bg-amber-50/70 p-4" aria-labelledby="cancel-refund-account-title">
                <h4 id="cancel-refund-account-title" className="text-xs font-bold uppercase tracking-wider text-amber-950">{localize("Tài khoản nhận hoàn tiền", "Refund bank account")} *</h4>
                <p className="mb-3 mt-1 text-xs leading-5 text-amber-900">{localize("Khách sạn sẽ dùng thông tin này nếu nhân viên duyệt hoàn qua QR. Không cung cấp OTP, PIN hoặc mật khẩu.", "The hotel uses these details only if staff approves a QR refund. Never provide an OTP, PIN, or password.")}</p>
                <BankAccountFields
                  disabled={isCancelling}
                  bankCode={cancelBankCode}
                  bankName={cancelBankName}
                  accountNumber={cancelAccountNumber}
                  accountHolderName={cancelAccountHolder}
                  onBankChange={(nextCode, nextName) => { setCancelBankCode(nextCode); setCancelBankName(nextName); setCancelError(""); }}
                  onAccountNumberChange={(value) => { setCancelAccountNumber(value); setCancelError(""); }}
                  onAccountHolderNameChange={(value) => { setCancelAccountHolder(value); setCancelError(""); }}
                  error={Boolean(cancelError)}
                />
              </section>

              {cancelError && <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm font-medium text-rose-700">{cancelError}</p>}

            </div>

            {/* Footer Buttons */}
            <div className="bg-[#F1F0EA] px-6 py-4 flex gap-3 border-t border-gray-200 justify-end">
              <button
                type="button"
                disabled={isCancelling}
                onClick={() => { setIsCancelModalOpen(false); setCancelError(""); }}
                className="px-5 py-2.5 border border-gray-350 text-xs font-bold tracking-widest uppercase transition-colors hover:bg-gray-100 rounded-sm"
              >
                {localize("Hủy", "Cancel")}
              </button>
              <button
                type="submit"
                disabled={isCancelling}
                className="px-5 py-2.5 bg-rose-600 hover:bg-rose-700 text-white text-xs font-bold tracking-widest uppercase transition-colors rounded-sm shadow-sm disabled:cursor-not-allowed disabled:opacity-60"
              >
                {isCancelling ? localize("Đang gửi...", "Submitting...") : localize("Gửi yêu cầu hủy", "Submit cancellation request")}
              </button>
            </div>

          </form>
        </div>
      )}

    </div>
  );
}
