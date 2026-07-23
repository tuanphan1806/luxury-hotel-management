"use client";

import React, { useCallback, useEffect, useMemo, useState } from "react";
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
import ViewportModal from "@/components/UI/ViewportModal";

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

type BookingFilter = "ALL" | "UPCOMING" | "STAYING" | "ACTION_REQUIRED" | "COMPLETED" | "CLOSED";

const BOOKINGS_PER_PAGE = 5;

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
  const [reviewSuccess, setReviewSuccess] = useState("");
  const [invoice, setInvoice] = useState<ReservationInvoice | null>(null);
  const [invoiceLoadingId, setInvoiceLoadingId] = useState<number | null>(null);
  const [bookingFilter, setBookingFilter] = useState<BookingFilter>("ALL");
  const [bookingSearch, setBookingSearch] = useState("");
  const [bookingPage, setBookingPage] = useState(1);
  const [expandedBookingId, setExpandedBookingId] = useState<number | null>(null);

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
          const normalizedRoomTypes = Array.from(new Map(
            (r.roomTypes || []).map((item) => [
              Number(item.roomTypeId),
              {
                roomTypeId: Number(item.roomTypeId),
                roomTypeName: localize(item.roomTypeName, item.roomTypeNameEn),
                roomTypeNameEn: item.roomTypeNameEn,
              },
            ]),
          ).values());
          const roomTypeNames = normalizedRoomTypes.map((item) => item.roomTypeName).filter(Boolean);
          const refundRoute = r.refundRoute || "NONE";
          
          return {
            bookingId: r.reservationCode,
            roomName: roomTypeNames.length > 0 ? roomTypeNames.join(" · ") : localize("Phòng được gán khi nhận phòng", "Room assigned at check-in"),
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
            roomTypes: normalizedRoomTypes,
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
    setReviewSuccess("");
  };

  const openBookingReviews = (booking: Booking) => {
    const nextRoomType = booking.roomTypes.find((roomType) => !myReviews.some(
      (review) => review.reservationId === booking.id && review.roomTypeId === roomType.roomTypeId,
    )) || booking.roomTypes[0];
    if (nextRoomType) openReview(booking, nextRoomType);
  };

  const saveReview = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!reviewTarget) return;
    setIsSavingReview(true); setReviewError("");
    try {
      let response;
      if (reviewTarget.existing) {
        response = await apiClient.patch(`/api/reviews/${reviewTarget.existing.id}`, { rating: reviewRating, comment: reviewComment.trim() || undefined });
      } else {
        response = await apiClient.post("/api/reviews", { reservationId: reviewTarget.booking.id, roomTypeId: reviewTarget.roomTypeId, rating: reviewRating, comment: reviewComment.trim() || undefined });
      }
      const savedReview = response.data?.data as MyReview;
      const updatedReviews = [
        ...myReviews.filter((review) => review.id !== savedReview.id
          && !(review.reservationId === savedReview.reservationId && review.roomTypeId === savedReview.roomTypeId)),
        savedReview,
      ];
      setMyReviews(updatedReviews);

      const nextRoomType = reviewTarget.booking.roomTypes.find((roomType) => (
        roomType.roomTypeId !== reviewTarget.roomTypeId
        && !updatedReviews.some((review) => review.reservationId === reviewTarget.booking.id && review.roomTypeId === roomType.roomTypeId)
      ));
      if (!reviewTarget.existing && nextRoomType) {
        setReviewTarget({ booking: reviewTarget.booking, ...nextRoomType });
        setReviewRating(5);
        setReviewComment("");
        setReviewSuccess(localize("Đã lưu đánh giá. Bạn có thể tiếp tục với hạng phòng kế tiếp.", "Review saved. You can continue with the next room type."));
      } else {
        setReviewTarget({ ...reviewTarget, existing: savedReview });
        setReviewRating(savedReview.rating);
        setReviewComment(savedReview.comment || "");
        setReviewSuccess(localize("Đã lưu đánh giá.", "Review saved."));
      }
    } catch (error: unknown) {
      setReviewError(getApiErrorMessage(error, localize("Không thể lưu đánh giá. Vui lòng thử lại.", "Could not save your review. Please try again.")));
    } finally { setIsSavingReview(false); }
  };

  const deleteReview = async () => {
    if (!reviewTarget?.existing) return;
    setIsSavingReview(true);
    try {
      await apiClient.delete(`/api/reviews/${reviewTarget.existing.id}`);
      setMyReviews((current) => current.filter((review) => review.id !== reviewTarget.existing?.id));
      setReviewTarget({ ...reviewTarget, existing: undefined });
      setReviewRating(5);
      setReviewComment("");
      setReviewError("");
      setReviewSuccess(localize("Đã xóa đánh giá. Bạn có thể viết lại cho hạng phòng này.", "Review deleted. You can write a new one for this room type."));
    }
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

  const filteredBookings = useMemo(() => {
    const query = bookingSearch.trim().toLocaleLowerCase(localeTag);
    const matchesFilter = (booking: Booking) => {
      switch (bookingFilter) {
        case "UPCOMING":
          return booking.status === "DRAFT" || booking.status === "CONFIRMED";
        case "STAYING":
          return booking.status === "CHECKED_IN";
        case "ACTION_REQUIRED":
          return booking.status === "CANCELLATION_PENDING"
            || Boolean(booking.cancellationRefundPending)
            || booking.refundDestinationStatus === "REQUIRED";
        case "COMPLETED":
          return booking.status === "CHECKED_OUT";
        case "CLOSED":
          return booking.status === "CANCELLED" || booking.status === "NO_SHOW";
        default:
          return true;
      }
    };

    return [...bookings]
      .filter(matchesFilter)
      .filter((booking) => !query || `${booking.bookingId} ${booking.roomName} ${booking.status}`.toLocaleLowerCase(localeTag).includes(query))
      .sort((left, right) => new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime());
  }, [bookingFilter, bookingSearch, bookings, localeTag]);

  const bookingPageCount = Math.max(1, Math.ceil(filteredBookings.length / BOOKINGS_PER_PAGE));
  const visibleBookings = filteredBookings.slice((bookingPage - 1) * BOOKINGS_PER_PAGE, bookingPage * BOOKINGS_PER_PAGE);

  useEffect(() => {
    setBookingPage(1);
    setExpandedBookingId(null);
  }, [bookingFilter, bookingSearch]);

  useEffect(() => {
    if (bookingPage > bookingPageCount) setBookingPage(bookingPageCount);
  }, [bookingPage, bookingPageCount]);

  return (
    <div className="min-h-screen bg-[#F1F0EA]">
      <GuestPageHero
        imageSrc={GALLERY_HERO_IMAGES.bookings}
        imageAlt={localize("Toàn cảnh Luxury Hotel bên mặt nước", "Luxury Hotel waterfront exterior")}
        eyebrow={localize("Trung tâm kỳ lưu trú", "Stay center")}
        title={localize("Mọi kỳ nghỉ, một nơi để theo dõi.", "Every stay, all in one place.")}
        description={localize("Xem nhanh đơn sắp tới, trạng thái thanh toán, hoàn tiền và những việc cần hoàn tất trước khi đến.", "Review upcoming reservations, payments, refunds, and every action to complete before arrival.")}
        className="min-h-[44dvh] md:min-h-[460px]"
      />

      <div className="relative z-10 mx-auto max-w-6xl space-y-8 px-4 py-10 sm:px-6 md:py-14">
        
        <section className="overflow-hidden rounded-[2rem] border border-[#0F2A43]/12 bg-[#FBFAF6] shadow-[0_18px_50px_rgba(15,42,67,0.08)]" aria-labelledby="booking-overview-title">
          <div className="flex flex-col gap-5 border-b border-[#0F2A43]/10 px-6 py-6 sm:px-8 md:flex-row md:items-end md:justify-between">
            <div>
              <p className="text-xs font-bold uppercase tracking-[0.22em] text-[#80632F]">{localize("Tổng quan lưu trú", "Stay overview")}</p>
              <h2 id="booking-overview-title" className="mt-2 font-serif text-3xl font-bold text-[#0F2A43]">{localize("Hành trình của bạn", "Your stays")}</h2>
              <p className="mt-2 max-w-2xl text-sm leading-6 text-[#66727C]">{localize("Theo dõi trạng thái, khoản thanh toán và việc cần hoàn tất của từng đơn.", "Track the status, payments, and next action for every reservation.")}</p>
            </div>
            <Link href="/reservation" className="inline-flex min-h-12 shrink-0 items-center justify-center rounded-xl bg-[#0F2A43] px-6 text-sm font-bold text-white transition hover:-translate-y-0.5 hover:bg-[#091E30] hover:shadow-lg">
              {localize("Đặt kỳ nghỉ mới", "Book another stay")} <span className="ml-2" aria-hidden="true">→</span>
            </Link>
          </div>

          <dl className="grid gap-px bg-[#0F2A43]/10 sm:grid-cols-3">
            <div className="bg-white px-6 py-5 sm:px-8">
              <dt className="text-xs font-bold uppercase tracking-[0.18em] text-[#66727C]">{localize("Tổng số đơn", "Total bookings")}</dt>
              <dd className="mt-2 font-serif text-3xl font-bold tabular-nums text-[#0F2A43]">{bookings.length}</dd>
            </div>
            <div className="bg-[#F4EFE5] px-6 py-5 sm:px-8">
              <dt className="text-xs font-bold uppercase tracking-[0.18em] text-[#66727C]">{localize("Sắp tới", "Upcoming")}</dt>
              <dd className="mt-2 font-serif text-3xl font-bold tabular-nums text-[#80632F]">{bookings.filter((item) => item.status === "CONFIRMED" || item.status === "DRAFT").length}</dd>
            </div>
            <div className="bg-white px-6 py-5 sm:px-8">
              <dt className="text-xs font-bold uppercase tracking-[0.18em] text-[#66727C]">{localize("Đã hoàn tất", "Completed")}</dt>
              <dd className="mt-2 font-serif text-3xl font-bold tabular-nums text-emerald-700">{bookings.filter((item) => item.status === "CHECKED_OUT").length}</dd>
            </div>
          </dl>
        </section>

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
            <section className="rounded-[1.75rem] border border-[#0F2A43]/12 bg-[#FBFAF6] p-5 shadow-sm sm:p-6" aria-labelledby="booking-list-title">
              <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
                <div>
                  <p className="text-xs font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize("Danh sách đặt phòng", "Booking list")}</p>
                  <h2 id="booking-list-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">{localize("Tìm đúng đơn cần xử lý", "Find the reservation you need")}</h2>
                </div>
                <label className="relative block w-full lg:max-w-sm">
                  <span className="mb-2 block text-xs font-bold uppercase tracking-[0.16em] text-[#66727C]">{localize("Tìm đơn", "Search bookings")}</span>
                  <input
                    type="search"
                    value={bookingSearch}
                    onChange={(event) => setBookingSearch(event.target.value)}
                    placeholder={localize("Mã đơn hoặc hạng phòng", "Booking code or room type")}
                    className="min-h-12 w-full rounded-xl border border-[#0F2A43]/14 bg-white px-4 pr-12 text-sm text-[#0F2A43] outline-none transition placeholder:text-[#66727C]/70 focus:border-[#B8944F] focus:ring-4 focus:ring-[#B8944F]/15"
                  />
                  {bookingSearch && <button type="button" onClick={() => setBookingSearch("")} aria-label={localize("Xóa nội dung tìm kiếm", "Clear search")} className="absolute bottom-0 right-0 flex h-12 w-12 items-center justify-center rounded-r-xl text-lg text-[#66727C] transition hover:bg-[#EAE2D2] hover:text-[#0F2A43]">×</button>}
                </label>
              </div>

              <div className="mt-5 flex gap-2 overflow-x-auto pb-1" role="group" aria-label={localize("Lọc đơn theo trạng thái", "Filter bookings by status") }>
                {([
                  ["ALL", localize("Tất cả", "All"), bookings.length],
                  ["UPCOMING", localize("Sắp tới", "Upcoming"), bookings.filter((item) => item.status === "DRAFT" || item.status === "CONFIRMED").length],
                  ["STAYING", localize("Đang lưu trú", "Staying"), bookings.filter((item) => item.status === "CHECKED_IN").length],
                  ["ACTION_REQUIRED", localize("Cần xử lý", "Action needed"), bookings.filter((item) => item.status === "CANCELLATION_PENDING" || item.cancellationRefundPending || item.refundDestinationStatus === "REQUIRED").length],
                  ["COMPLETED", localize("Hoàn tất", "Completed"), bookings.filter((item) => item.status === "CHECKED_OUT").length],
                  ["CLOSED", localize("Đã đóng", "Closed"), bookings.filter((item) => item.status === "CANCELLED" || item.status === "NO_SHOW").length],
                ] as Array<[BookingFilter, string, number]>).map(([value, label, count]) => (
                  <button
                    key={value}
                    type="button"
                    onClick={() => setBookingFilter(value)}
                    aria-pressed={bookingFilter === value}
                    className={`inline-flex min-h-11 shrink-0 items-center gap-2 rounded-full border px-4 text-sm font-bold transition ${bookingFilter === value ? "border-[#0F2A43] bg-[#0F2A43] text-white shadow-md" : "border-[#0F2A43]/14 bg-white text-[#66727C] hover:border-[#B8944F] hover:text-[#0F2A43]"}`}
                  >
                    {label}<span className={`rounded-full px-2 py-0.5 text-xs tabular-nums ${bookingFilter === value ? "bg-white/14 text-white" : "bg-[#EAE2D2] text-[#80632F]"}`}>{count}</span>
                  </button>
                ))}
              </div>
            </section>

            <div className="flex flex-wrap items-center justify-between gap-3 px-1">
              <p className="text-sm font-semibold text-[#66727C]">{localize(`${filteredBookings.length} đơn phù hợp`, `${filteredBookings.length} matching bookings`)}</p>
              {filteredBookings.length > BOOKINGS_PER_PAGE && <p className="text-xs font-medium text-[#66727C]">{localize(`Trang ${bookingPage}/${bookingPageCount}`, `Page ${bookingPage}/${bookingPageCount}`)}</p>}
            </div>

            {filteredBookings.length === 0 ? (
              <div className="rounded-[1.75rem] border border-dashed border-[#0F2A43]/20 bg-[#FBFAF6] px-6 py-14 text-center">
                <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-[#EAE2D2] font-serif text-xl font-bold text-[#80632F]" aria-hidden="true">0</span>
                <h3 className="mt-4 font-serif text-xl font-bold text-[#0F2A43]">{localize("Không tìm thấy đơn phù hợp", "No matching reservations")}</h3>
                <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-[#66727C]">{localize("Thử đổi bộ lọc hoặc tìm bằng mã đặt phòng khác.", "Try another filter or booking code.")}</p>
                <button type="button" onClick={() => { setBookingFilter("ALL"); setBookingSearch(""); }} className="mt-5 min-h-11 rounded-xl border border-[#0F2A43]/16 bg-white px-5 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F1F0EA]">{localize("Xóa bộ lọc", "Clear filters")}</button>
              </div>
            ) : (
              <>
                <div className="space-y-5">
            {visibleBookings.map((booking) => {
              const isCancellationRefundPending = Boolean(booking.cancellationRefundPending);
              const isExpanded = expandedBookingId === booking.id;
              const canRequestCancellation = !isCancellationRefundPending && (booking.status === "DRAFT" || booking.status === "CONFIRMED");
              const reviewedRoomTypeCount = booking.roomTypes.filter((roomType) => myReviews.some(
                (review) => review.reservationId === booking.id && review.roomTypeId === roomType.roomTypeId,
              )).length;
              
              return (
                <div
                  key={booking.bookingId}
                  className="overflow-hidden rounded-[2rem] border border-[#0F2A43]/12 bg-[#FBFAF6] shadow-[0_12px_38px_rgba(15,42,67,0.08)] transition duration-300 hover:-translate-y-1 hover:shadow-[0_20px_55px_rgba(15,42,67,0.13)]"
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

                    <div className="grid grid-cols-2 gap-3 text-xs lg:grid-cols-[0.9fr_1.45fr_0.7fr_0.85fr]">
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

                    {isExpanded && (
                      <div id={`booking-details-${booking.id}`} className="space-y-4 border-t border-[#0F2A43]/10 pt-5">
                        {booking.status === "CANCELLED" && (
                          <div className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm font-medium text-rose-950">
                            <strong>{localize("Chi tiết hủy", "Cancellation details")}:</strong> {getCancellationReason(booking.cancellationReason)}
                          </div>
                        )}
                        {booking.status === "CANCELLATION_PENDING" && !isCancellationRefundPending && <div className="rounded-xl border border-violet-200 bg-violet-50 p-4 text-sm font-medium text-violet-800">{localize("Yêu cầu hủy đang chờ khách sạn xác nhận. Phòng vẫn được giữ cho đến khi có quyết định.", "Your cancellation request is awaiting hotel confirmation. The room remains held until a decision is made.")}</div>}
                        {isCancellationRefundPending && (
                          <div className="rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950" role="status">
                            <p className="font-bold">{booking.refundRoute === "CASH_AT_COUNTER"
                              ? localize("Khách sạn đang xử lý hoàn tiền mặt", "The hotel is processing a cash refund")
                              : localize("Khách sạn đang chờ thông tin tài khoản nhận hoàn tiền", "The hotel is waiting for your refund bank details")}</p>
                            <p className="mt-1 leading-6">
                              {booking.refundRoute === "CASH_AT_COUNTER"
                                ? localize("Đơn giữ nguyên trạng thái cho đến khi nhân viên giao tiền và xác nhận hoàn tất.", "The reservation keeps its status until staff hands over the cash and confirms completion.")
                                : booking.refundDestinationStatus === "REQUIRED"
                                ? localize("Vui lòng cung cấp ngân hàng, số tài khoản và họ tên chủ tài khoản để khách sạn tiếp tục hoàn qua QR.", "Provide the bank, account number, and account holder name so the hotel can continue the QR refund.")
                                : localize("Thông tin nhận tiền đã được gửi; khách sạn đang chuyển khoản và chờ ngân hàng xác nhận.", "Your payout details were submitted; the hotel is transferring the refund and awaiting bank confirmation.")}
                            </p>
                          </div>
                        )}
                        <RefundProgressCard refunds={booking.refunds} />
                        {(booking.refundRoute === "VNPAY_ORIGINAL" || booking.refundRoute === "MIXED") && (
                          <div className="rounded-xl border border-sky-200 bg-sky-50 p-4 text-sm text-sky-900">
                            <p className="font-bold">{localize("Hoàn về phương thức thanh toán ban đầu", "Refund to the original payment method")}</p>
                            <p className="mt-1 leading-6">{booking.refundRoute === "MIXED"
                              ? localize("Phần giao dịch lịch sử hoàn theo nguồn gốc; phần hoàn QR cần tài khoản ngân hàng bên dưới.", "The legacy transaction portion returns to its original source; the QR portion requires the bank account below.")
                              : localize("Khoản hoàn thuộc giao dịch lịch sử được xử lý theo nguồn gốc; bạn không cần cung cấp tài khoản ngân hàng.", "The legacy refund is processed against its original transaction; no bank details are required.")}</p>
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
                    )}
                  </div>

                  <div className="flex flex-col gap-3 border-t border-[#0F2A43]/10 bg-[#E5E9ED]/65 px-6 py-4 sm:flex-row sm:items-center sm:justify-between sm:px-8">
                    <button
                      type="button"
                      aria-expanded={isExpanded}
                      aria-controls={`booking-details-${booking.id}`}
                      onClick={() => setExpandedBookingId((current) => current === booking.id ? null : booking.id)}
                      className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-[#0F2A43]/16 bg-[#FBFAF6] px-5 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-white"
                    >
                      {isExpanded ? localize("Thu gọn", "Show less") : booking.refundDestinationStatus === "REQUIRED" ? localize("Bổ sung thông tin hoàn", "Add refund details") : localize("Xem chi tiết", "View details")}
                      <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={`h-4 w-4 shrink-0 transition-transform duration-200 ${isExpanded ? "rotate-180" : ""}`}>
                        <path d="m6 9 6 6 6-6" />
                      </svg>
                    </button>
                    <div className="grid w-full gap-2 sm:w-auto sm:grid-flow-col sm:auto-cols-fr">
                      <Link href="/contact" className="inline-flex min-h-11 w-full items-center justify-center rounded-xl border border-[#0F2A43]/16 bg-white px-5 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#FBFAF6] sm:min-w-[8.75rem]">{localize("Liên hệ", "Contact")}</Link>
                      {booking.status === "CHECKED_OUT" && booking.roomTypes.length > 0 && (
                        <button type="button" onClick={() => openBookingReviews(booking)} className="inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-xl border border-[#B8944F]/55 bg-[#FBFAF6] px-5 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F0EADF] sm:min-w-[8.75rem]">
                          {localize("Đánh giá", "Review")}
                          <span className="rounded-full bg-[#EAE2D2] px-2 py-0.5 text-[10px] font-bold tabular-nums text-[#80632F]">{reviewedRoomTypeCount}/{booking.roomTypes.length}</span>
                        </button>
                      )}
                      {booking.status === "CHECKED_OUT" && <button type="button" onClick={() => openInvoice(booking.id)} disabled={invoiceLoadingId === booking.id} className="inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-xl bg-[#B8944F] px-5 text-sm font-bold text-[#0F2A43] transition hover:bg-[#A78343] disabled:cursor-not-allowed disabled:opacity-60 sm:min-w-[8.75rem]">{invoiceLoadingId === booking.id && <span aria-hidden="true" className="h-4 w-4 animate-spin rounded-full border-2 border-[#0F2A43] border-r-transparent" />}{invoiceLoadingId === booking.id ? localize("Đang tải...", "Loading...") : localize("In hóa đơn", "Print invoice")}</button>}
                      {canRequestCancellation && <button type="button" onClick={() => handleOpenCancel(booking)} className="inline-flex min-h-11 w-full items-center justify-center rounded-xl border border-rose-200 bg-rose-50 px-5 text-sm font-bold text-rose-700 transition hover:border-rose-300 hover:bg-rose-100 sm:min-w-[8.75rem]">{localize("Yêu cầu hủy", "Cancel booking")}</button>}
                    </div>
                  </div>
                </div>
              );
            })}
                </div>

                {bookingPageCount > 1 && (
                  <nav className="flex items-center justify-center gap-3 pt-2" aria-label={localize("Phân trang đơn đặt phòng", "Booking pagination") }>
                    <button type="button" disabled={bookingPage === 1} onClick={() => { setBookingPage((page) => Math.max(1, page - 1)); setExpandedBookingId(null); }} className="inline-flex min-h-11 items-center justify-center rounded-xl border border-[#0F2A43]/14 bg-white px-5 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] disabled:opacity-45">← {localize("Trước", "Previous")}</button>
                    <span className="min-w-20 text-center text-sm font-bold tabular-nums text-[#0F2A43]">{bookingPage} / {bookingPageCount}</span>
                    <button type="button" disabled={bookingPage === bookingPageCount} onClick={() => { setBookingPage((page) => Math.min(bookingPageCount, page + 1)); setExpandedBookingId(null); }} className="inline-flex min-h-11 items-center justify-center rounded-xl border border-[#0F2A43]/14 bg-white px-5 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] disabled:opacity-45">{localize("Sau", "Next")} →</button>
                  </nav>
                )}
              </>
            )}
          </div>
        )}

      </div>

      {reviewTarget && (
        <ViewportModal
          open
          onClose={() => { setReviewTarget(null); setReviewError(""); setReviewSuccess(""); }}
          labelledBy="review-modal-title"
          describedBy="review-modal-description"
          busy={isSavingReview}
          panelClassName="max-w-4xl"
          backdropClassName="bg-[#0F2A43]/72"
        >
          <form onSubmit={saveReview} className="flex min-h-0 flex-1 flex-col">
            <header className="relative bg-[#0F2A43] px-6 py-5 text-white sm:px-7">
              <button type="button" onClick={() => setReviewTarget(null)} disabled={isSavingReview} aria-label={localize("Đóng cửa sổ đánh giá", "Close review dialog")} className="absolute right-4 top-4 flex h-11 w-11 items-center justify-center rounded-full border border-white/15 bg-white/5 text-xl transition hover:bg-white/12 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] disabled:cursor-not-allowed disabled:opacity-50">×</button>
              <p className="pr-14 text-[10px] font-bold uppercase tracking-[0.2em] text-[#D8C398]">{reviewTarget.booking.bookingId}</p>
              <h3 id="review-modal-title" className="mt-2 pr-14 font-serif text-2xl font-bold sm:text-3xl">{localize("Đánh giá các hạng phòng", "Review room types")}</h3>
              <p id="review-modal-description" className="mt-2 max-w-2xl pr-10 text-sm leading-6 text-white/72">
                {localize(
                  `Đơn có ${reviewTarget.booking.roomTypes.length} hạng phòng. Mỗi hạng được đánh giá riêng để phản ánh đúng trải nghiệm.`,
                  `This booking has ${reviewTarget.booking.roomTypes.length} room types. Review each one separately to reflect your stay accurately.`,
                )}
              </p>
            </header>

            <div className="grid min-h-0 flex-1 md:grid-cols-[minmax(220px,0.78fr)_minmax(0,1.45fr)]">
              <aside className="min-h-0 overflow-y-auto border-b border-[#0F2A43]/10 bg-[#F1F0EA] p-4 md:border-b-0 md:border-r sm:p-5" aria-label={localize("Chọn hạng phòng để đánh giá", "Choose a room type to review") }>
                <div className="flex items-end justify-between gap-3">
                  <div>
                    <p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#80632F]">{localize("Tiến độ đánh giá", "Review progress")}</p>
                    <p className="mt-1 text-sm font-bold text-[#0F2A43]">{localize("Hạng phòng trong đơn", "Room types in booking")}</p>
                  </div>
                  <span className="rounded-full bg-white px-3 py-1 text-xs font-bold tabular-nums text-[#80632F]">
                    {reviewTarget.booking.roomTypes.filter((roomType) => myReviews.some((review) => review.reservationId === reviewTarget.booking.id && review.roomTypeId === roomType.roomTypeId)).length}/{reviewTarget.booking.roomTypes.length}
                  </span>
                </div>
                <div className="mt-4 space-y-2">
                  {reviewTarget.booking.roomTypes.map((roomType, index) => {
                    const existing = myReviews.find((review) => review.reservationId === reviewTarget.booking.id && review.roomTypeId === roomType.roomTypeId);
                    const selected = roomType.roomTypeId === reviewTarget.roomTypeId;
                    return (
                      <button
                        key={roomType.roomTypeId}
                        type="button"
                        disabled={isSavingReview}
                        aria-pressed={selected}
                        onClick={() => openReview(reviewTarget.booking, roomType)}
                        className={`flex min-h-16 w-full items-center gap-3 rounded-xl border px-3 py-3 text-left transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] disabled:cursor-not-allowed disabled:opacity-60 ${selected ? "border-[#0F2A43] bg-[#0F2A43] text-white shadow-md" : "border-[#0F2A43]/12 bg-white text-[#0F2A43] hover:-translate-y-0.5 hover:border-[#B8944F] hover:shadow-sm"}`}
                      >
                        <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-xs font-bold ${selected ? "bg-[#B8944F] text-[#0F2A43]" : "bg-[#EAE2D2] text-[#80632F]"}`}>{String(index + 1).padStart(2, "0")}</span>
                        <span className="min-w-0 flex-1"><span className="block truncate text-sm font-bold">{roomType.roomTypeName}</span><span className={`mt-1 block text-xs font-medium ${selected ? "text-white/65" : existing ? "text-emerald-700" : "text-[#66727C]"}`}>{existing ? localize("Đã đánh giá", "Reviewed") : localize("Chưa đánh giá", "Not reviewed")}</span></span>
                        <span aria-hidden="true" className={`text-base ${existing ? "text-emerald-600" : selected ? "text-[#D8C398]" : "text-[#9AA0A8]"}`}>{existing ? "✓" : "→"}</span>
                      </button>
                    );
                  })}
                </div>
              </aside>

              <section className="min-h-0 space-y-5 overflow-y-auto bg-white p-5 sm:p-6">
                <div className="flex flex-wrap items-start justify-between gap-3 border-b border-[#0F2A43]/10 pb-4">
                  <div>
                    <p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#80632F]">{reviewTarget.existing ? localize("Đã đánh giá · có thể chỉnh sửa", "Reviewed · editable") : localize("Đánh giá mới", "New review")}</p>
                    <h4 className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">{reviewTarget.roomTypeName}</h4>
                  </div>
                  {reviewTarget.existing && <span className="rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1.5 text-xs font-bold text-emerald-700">✓ {localize("Đã lưu", "Saved")}</span>}
                </div>
                {reviewError && <p role="alert" className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm font-medium text-rose-700">{reviewError}</p>}
                {reviewSuccess && <p role="status" className="rounded-xl border border-emerald-200 bg-emerald-50 p-3 text-sm font-medium text-emerald-800">{reviewSuccess}</p>}
                <div>
                  <p className="mb-3 text-xs font-bold uppercase tracking-wider text-[#66727C]">{localize("Mức độ hài lòng", "Satisfaction")}</p>
                  <div className="flex flex-wrap gap-2">{[1,2,3,4,5].map((star) => <button key={star} type="button" disabled={isSavingReview} onClick={() => { setReviewRating(star); setReviewSuccess(""); }} aria-label={localize(`${star} sao`, `${star} stars`)} aria-pressed={star === reviewRating} className={`flex h-11 w-11 items-center justify-center rounded-xl border text-xl transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] disabled:cursor-not-allowed disabled:opacity-60 ${star <= reviewRating ? "border-[#B8944F] bg-[#B8944F] text-[#0F2A43]" : "border-[#0F2A43]/10 bg-[#F1F0EA] text-[#9AA0A8] hover:border-[#B8944F]"}`}>★</button>)}</div>
                </div>
                <label className="block"><span className="mb-2 block text-xs font-bold uppercase tracking-wider text-[#66727C]">{localize("Nhận xét", "Comment")}</span><textarea rows={5} maxLength={1000} disabled={isSavingReview} value={reviewComment} onChange={(event) => { setReviewComment(event.target.value); setReviewSuccess(""); }} placeholder={localize("Chia sẻ trải nghiệm thực tế của bạn...", "Share your real experience...")} className="w-full resize-none rounded-xl border border-[#0F2A43]/12 bg-[#FBFAF6] px-4 py-3 text-sm text-[#0F2A43] outline-none transition placeholder:text-[#66727C]/65 focus:border-[#B8944F] focus:ring-4 focus:ring-[#B8944F]/15 disabled:cursor-not-allowed disabled:opacity-60" /><span className="mt-1 block text-right text-[10px] text-[#66727C]">{reviewComment.length}/1000</span></label>
                <p className="rounded-xl bg-[#F1F0EA] p-3 text-xs font-medium leading-5 text-[#66727C]">{localize("Mỗi hạng phòng trong đơn có một đánh giá riêng. Đánh giá được công khai tại trang chi tiết của đúng hạng phòng đó.", "Each room type in the booking has its own review. It appears publicly on that room type's detail page.")}</p>
              </section>
            </div>
            <footer className="flex flex-wrap items-center justify-between gap-3 border-t border-[#0F2A43]/10 bg-[#FBFAF6] p-4 sm:px-6">
              {reviewTarget.existing ? <button type="button" onClick={deleteReview} disabled={isSavingReview} className="min-h-11 rounded-xl px-4 text-sm font-bold text-rose-700 transition hover:bg-rose-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-300 disabled:cursor-not-allowed disabled:opacity-50">{localize("Xóa đánh giá", "Delete review")}</button> : <span />}
              <div className="ml-auto flex gap-3"><button type="button" onClick={() => setReviewTarget(null)} disabled={isSavingReview} className="min-h-11 rounded-xl border border-[#0F2A43]/14 bg-white px-5 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F1F0EA] disabled:cursor-not-allowed disabled:opacity-50">{localize("Đóng", "Close")}</button><button type="submit" disabled={isSavingReview} className="inline-flex min-h-11 min-w-[9rem] items-center justify-center gap-2 rounded-xl bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#091E30] disabled:cursor-not-allowed disabled:opacity-50">{isSavingReview && <span aria-hidden="true" className="h-4 w-4 animate-spin rounded-full border-2 border-white/35 border-r-white" />}{isSavingReview ? localize("Đang lưu...", "Saving...") : reviewTarget.existing ? localize("Cập nhật", "Update review") : localize("Lưu đánh giá", "Save review")}</button></div>
            </footer>
          </form>
        </ViewportModal>
      )}

      {/* ───── CANCEL MODAL DIALOG ───── */}
      {invoice && <ReservationInvoiceModal invoice={invoice} onClose={() => setInvoice(null)} />}

      {isCancelModalOpen && selectedBookingForCancel && (
        <ViewportModal
          open
          onClose={() => { setIsCancelModalOpen(false); setCancelError(""); }}
          labelledBy="cancel-booking-title"
          busy={isCancelling}
          panelClassName="max-w-md"
          backdropClassName="bg-[#0F2A43]/72"
        >
          <form onSubmit={(event) => { event.preventDefault(); void handleConfirmCancel(); }} className="flex min-h-0 flex-1 flex-col" noValidate>
            {/* Header */}
            <div className="bg-red-950 p-6 text-white text-center relative">
              <h3 id="cancel-booking-title" className="font-serif text-xl font-bold tracking-wide">{localize("Hủy đặt phòng", "Cancel reservation")}</h3>
              <p className="text-[10px] font-semibold text-accent-gold uppercase tracking-wider mt-1">{localize("Đơn", "Booking")}: {selectedBookingForCancel.bookingId}</p>
            </div>

            {/* Body */}
            <div className="min-h-0 flex-1 space-y-5 overflow-y-auto p-6 text-sm">
              
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
        </ViewportModal>
      )}

    </div>
  );
}
