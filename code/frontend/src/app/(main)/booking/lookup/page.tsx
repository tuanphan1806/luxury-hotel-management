"use client";

import React, { Suspense, useEffect, useMemo, useState } from "react";
import axios from "axios";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { publicApiClient } from "@/lib/api";
import { saveGuestReservationToken } from "@/lib/guest-reservation-token";
import RefundRecipientForm, {
  type RefundDestinationStatus,
  type RefundRoute,
} from "@/components/refunds/RefundRecipientForm";
import RefundProgressCard, { type CustomerRefund } from "@/components/refunds/RefundProgressCard";
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from "@/lib/idempotency";
import BankAccountFields from "@/components/forms/BankAccountFields";
import ViewportModal from "@/components/UI/ViewportModal";

type ReservationRoomType = {
  roomTypeName?: string;
  quantity?: number;
  roomPrice?: number;
  subtotal?: number;
};

type Reservation = {
  id: number;
  reservationCode: string;
  customerName?: string;
  customerEmail?: string;
  customerPhone?: string;
  checkIn: string;
  checkOut: string;
  totalAmount: number;
  guestCount?: number;
  status: "PAYMENT_PENDING" | "DRAFT" | "CONFIRMED" | "CANCELLATION_PENDING" | "CANCELLED" | "CHECKED_IN" | "CHECKED_OUT" | "NO_SHOW";
  cancellationReason?: string;
  refundableAmount?: number;
  refundRoute?: RefundRoute;
  refundDestinationStatus?: RefundDestinationStatus;
  refundBankSummary?: string;
  refunds?: CustomerRefund[];
  roomTypes?: ReservationRoomType[];
};

const formatVND = (value?: number) => Number(value || 0).toLocaleString("vi-VN") + " đ";

const reservationStatusLabel: Record<Reservation["status"], string> = {
  PAYMENT_PENDING: "Chờ thanh toán",
  DRAFT: "Chờ khách sạn xác nhận",
  CONFIRMED: "Đã xác nhận",
  CANCELLATION_PENDING: "Đang chờ duyệt hủy",
  CANCELLED: "Đã hủy",
  CHECKED_IN: "Đã nhận phòng",
  CHECKED_OUT: "Đã trả phòng",
  NO_SHOW: "Không đến",
};

const getApiErrorMessage = (error: unknown, fallback: string) =>
  axios.isAxiosError<{ message?: string }>(error)
    ? error.response?.data?.message || fallback
    : fallback;

const formatDateTime = (value?: string) => {
  if (!value) return "-";
  try {
    return new Date(value).toLocaleString("vi-VN", {
      dateStyle: "medium",
      timeStyle: "short",
    });
  } catch {
    return value;
  }
};

function GuestBookingLookupContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const legacyToken = searchParams.get("token") || "";
  const [token, setToken] = useState("");
  const [tokenResolved, setTokenResolved] = useState(false);
  const [reservation, setReservation] = useState<Reservation | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState("");
  const [cancelReason, setCancelReason] = useState("Khách yêu cầu hủy qua email lookup");
  const [bankCode, setBankCode] = useState("");
  const [bankName, setBankName] = useState("");
  const [accountNumber, setAccountNumber] = useState("");
  const [accountHolderName, setAccountHolderName] = useState("");
  const [isCancelling, setIsCancelling] = useState(false);
  const [cancelError, setCancelError] = useState("");
  const [isCancelConfirmationOpen, setIsCancelConfirmationOpen] = useState(false);

  const canCancel = useMemo(
    () => reservation?.status === "DRAFT" || reservation?.status === "CONFIRMED",
    [reservation?.status]
  );

  useEffect(() => {
    // Link mới dùng URL fragment để secret không bao giờ được gửi tới web server.
    // Query token vẫn được đọc để các email cũ tiếp tục hoạt động.
    const fragmentToken = new URLSearchParams(window.location.hash.slice(1)).get("token") || "";
    setToken(fragmentToken || legacyToken);
    window.history.replaceState(null, "", window.location.pathname);
    setTokenResolved(true);
  }, [legacyToken]);

  useEffect(() => {
    const loadReservation = async () => {
      if (!tokenResolved) return;
      if (!token) {
        router.replace("/my-bookings");
        return;
      }

      try {
        const response = await publicApiClient.post("/api/reservations/lookup", null, {
          headers: { "X-Guest-Token": token },
        });
        const nextReservation = response.data?.data;
        if (!nextReservation?.id) {
          throw new Error("Không tìm thấy đặt phòng.");
        }
        saveGuestReservationToken(nextReservation.id, token);
        setReservation(nextReservation);
      } catch (error: unknown) {
        setError(getApiErrorMessage(error, "Không thể tải thông tin đặt phòng từ link này."));
      } finally {
        setIsLoading(false);
      }
    };

    loadReservation();
  }, [router, token, tokenResolved]);

  const handleCancel = async () => {
    if (!reservation || !token || !canCancel) return;
    if (!/^[A-Z0-9]{2,20}$/.test(bankCode.trim().toUpperCase())
      || bankName.trim().length < 2
      || !/^\d{6,24}$/.test(accountNumber)
      || accountHolderName.trim().length < 2) {
      setCancelError("Vui lòng nhập đầy đủ và đúng thông tin tài khoản nhận hoàn tiền.");
      return;
    }
    setIsCancelling(true);
    setCancelError("");
    const operationScope = `reservation:${reservation.id}:CANCEL_GUEST`;
    try {
      const response = await publicApiClient.patch(
        `/api/reservations/cancel/${reservation.id}`,
        {
          cancellationReason: cancelReason,
          refundRecipient: {
            bankCode: bankCode.trim().toUpperCase(),
            bankName: bankName.trim(),
            accountNumber,
            accountHolderName: accountHolderName.trim().toLocaleUpperCase("vi-VN"),
          },
        },
        {
          headers: {
            "X-Guest-Token": token,
            "Idempotency-Key": getOrCreateIdempotencyKey(operationScope),
          },
        }
      );
      clearIdempotencyKey(operationScope);
      const cancelledReservation = response.data?.data;
      setReservation(cancelledReservation || { ...reservation, status: "CANCELLATION_PENDING", cancellationReason: cancelReason });
      setIsCancelConfirmationOpen(false);
    } catch (error: unknown) {
      setCancelError(getApiErrorMessage(error, "Không thể hủy đặt phòng. Vui lòng liên hệ lễ tân."));
    } finally {
      setIsCancelling(false);
    }
  };

  const requestCancelConfirmation = () => {
    if (!/^[A-Z0-9]{2,20}$/.test(bankCode.trim().toUpperCase())
      || bankName.trim().length < 2
      || !/^\d{6,24}$/.test(accountNumber)
      || accountHolderName.trim().length < 2) {
      setCancelError("Vui lòng nhập đầy đủ và đúng thông tin tài khoản nhận hoàn tiền.");
      return;
    }
    setCancelError("");
    setIsCancelConfirmationOpen(true);
  };

  return (
    <div className="min-h-screen bg-[#F1F0EA] px-4 py-28 sm:px-6">
      <div className="mx-auto max-w-4xl space-y-6">
        <div className="space-y-3">
          <p className="text-xs font-bold uppercase tracking-[0.24em] text-[#80632F]">Guest booking</p>
          <h1 className="font-serif text-4xl font-bold text-primary-navy">Tra cứu đặt phòng</h1>
        </div>

        {isLoading ? (
          <div className="border border-[#0F2A43]/10 bg-white p-8 text-sm font-semibold text-primary-navy shadow-sm">
            Đang tải thông tin đặt phòng...
          </div>
        ) : error ? (
          <div className="space-y-4 border border-rose-200 bg-white p-8 shadow-sm">
            <p className="text-sm font-semibold text-rose-700">{error}</p>
            <Link href="/rooms" className="inline-flex bg-primary-navy px-5 py-3 text-xs font-bold uppercase tracking-widest text-white">
              Đặt phòng mới
            </Link>
          </div>
        ) : reservation ? (
          <div className="space-y-6">
            <div className="border border-[#0F2A43]/10 bg-white shadow-sm">
              <div className="flex flex-col gap-4 border-b border-[#0F2A43]/10 bg-[#101417] p-6 text-white sm:flex-row sm:items-center sm:justify-between">
                <div>
                  <p className="text-xs font-bold uppercase tracking-[0.22em] text-[#C8A35B]">Booking code</p>
                  <h2 className="mt-2 font-serif text-3xl font-bold">{reservation.reservationCode}</h2>
                </div>
                <span className="w-fit border border-white/20 px-3 py-1 text-xs font-bold uppercase tracking-widest">
                  {reservationStatusLabel[reservation.status]}
                </span>
              </div>

              <div className="grid gap-5 p-6 text-sm sm:grid-cols-2">
                <div>
                  <p className="text-xs font-bold uppercase tracking-wider text-[#66727C]">Khách hàng</p>
                  <p className="mt-1 font-semibold text-[#0F2A43]">{reservation.customerName || "-"}</p>
                  <p className="text-[#66727C]">{reservation.customerEmail || "-"}</p>
                  <p className="text-[#66727C]">{reservation.customerPhone || "-"}</p>
                </div>
                <div>
                  <p className="text-xs font-bold uppercase tracking-wider text-[#66727C]">Thời gian lưu trú</p>
                  <p className="mt-1 font-semibold text-[#0F2A43]">Nhận phòng: {formatDateTime(reservation.checkIn)}</p>
                  <p className="font-semibold text-[#0F2A43]">Trả phòng: {formatDateTime(reservation.checkOut)}</p>
                  <p className="text-[#66727C]">Số khách: {reservation.guestCount || "-"}</p>
                </div>
              </div>

              <div className="border-t border-[#0F2A43]/10 p-6">
                <p className="mb-3 text-xs font-bold uppercase tracking-wider text-[#66727C]">Loại phòng</p>
                <div className="space-y-3">
                  {(reservation.roomTypes || []).map((roomType, index) => (
                    <div key={`${roomType.roomTypeName}-${index}`} className="flex items-center justify-between gap-4 bg-[#F1F0EA] p-4 text-sm">
                      <div>
                        <p className="font-bold text-primary-navy">{roomType.roomTypeName || "Room type"}</p>
                        <p className="text-xs font-semibold text-[#66727C]">
                          {roomType.quantity || 0} phòng x {formatVND(roomType.roomPrice)}
                        </p>
                      </div>
                      <p className="font-serif text-lg font-bold text-[#80632F]">{formatVND(roomType.subtotal)}</p>
                    </div>
                  ))}
                </div>
              </div>

              <div className="flex items-center justify-between border-t border-[#0F2A43]/10 bg-[#F1F0EA] p-6">
                <span className="text-sm font-bold uppercase tracking-wider text-primary-navy">Tổng tiền</span>
                <span className="font-serif text-2xl font-bold text-[#80632F]">{formatVND(reservation.totalAmount)}</span>
              </div>
            </div>

            {reservation.status === "CANCELLATION_PENDING" ? (
              <p className="rounded-lg border border-violet-200 bg-violet-50 p-4 text-sm font-medium text-violet-800">Yêu cầu hủy đang chờ khách sạn duyệt.</p>
            ) : reservation.status === "CANCELLED" ? (
              <div className="border border-rose-200 bg-rose-50 p-5 text-sm font-semibold text-rose-800">
                Đặt phòng đã hủy. {reservation.cancellationReason || ""}
              </div>
            ) : canCancel ? (
              <div className="space-y-4 border border-[#0F2A43]/10 bg-white p-6 shadow-sm">
                <label className="block text-xs font-bold uppercase tracking-wider text-[#66727C]">
                  Lý do hủy
                  <textarea
                    value={cancelReason}
                    onChange={(event) => setCancelReason(event.target.value.slice(0, 500))}
                    maxLength={500}
                    rows={3}
                    className="mt-2 w-full border border-[#0F2A43]/10 px-4 py-3 text-sm font-medium normal-case tracking-normal outline-none focus:border-[#80632F]"
                  />
                </label>
                <section className="rounded-xl border border-amber-200 bg-amber-50/70 p-4" aria-labelledby="guest-cancel-refund-title">
                  <h3 id="guest-cancel-refund-title" className="text-xs font-bold uppercase tracking-wider text-amber-950">Tài khoản nhận hoàn tiền *</h3>
                  <p className="mb-3 mt-1 text-xs leading-5 text-amber-900">Thông tin được dùng nếu khách sạn duyệt hoàn qua QR. Không cung cấp OTP, PIN hoặc mật khẩu.</p>
                  <BankAccountFields
                    disabled={isCancelling}
                    bankCode={bankCode}
                    bankName={bankName}
                    accountNumber={accountNumber}
                    accountHolderName={accountHolderName}
                    onBankChange={(nextCode, nextName) => { setBankCode(nextCode); setBankName(nextName); setCancelError(""); }}
                    onAccountNumberChange={(value) => { setAccountNumber(value); setCancelError(""); }}
                    onAccountHolderNameChange={(value) => { setAccountHolderName(value); setCancelError(""); }}
                    error={Boolean(cancelError)}
                  />
                </section>
                <button
                  type="button"
                  onClick={requestCancelConfirmation}
                  disabled={isCancelling}
                  className="min-h-11 rounded-lg bg-rose-700 px-5 py-3 text-xs font-bold uppercase tracking-widest text-white transition hover:bg-rose-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-700 focus-visible:ring-offset-2 disabled:opacity-60"
                >
                  {isCancelling ? "Đang hủy..." : "Hủy đặt phòng"}
                </button>
                {cancelError && (
                  <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm font-medium text-rose-700">
                    {cancelError}
                  </p>
                )}
              </div>
            ) : (
              <div className="border border-[#0F2A43]/10 bg-white p-5 text-sm font-semibold text-[#66727C]">
                Đặt phòng ở trạng thái này không thể tự hủy. Vui lòng liên hệ lễ tân nếu cần hỗ trợ.
              </div>
            )}

            <RefundProgressCard refunds={reservation.refunds} />

            {(reservation.refundRoute === "VNPAY_ORIGINAL" || reservation.refundRoute === "MIXED") && (
              <div className="rounded-xl border border-sky-200 bg-sky-50 p-4 text-sm text-sky-900">
                <p className="font-bold">Hoàn theo giao dịch trực tuyến ban đầu</p>
                <p className="mt-1 leading-6">{reservation.refundRoute === "MIXED"
                  ? "Phần giao dịch lịch sử sẽ hoàn theo nguồn gốc; phần hoàn QR thủ công cần tài khoản ngân hàng bên dưới."
                  : "Hệ thống xử lý hoàn tiền trên giao dịch gốc. Bạn không cần cung cấp tài khoản ngân hàng."}</p>
              </div>
            )}

            {(reservation.refundRoute === "MANUAL_BANK_TRANSFER" || reservation.refundRoute === "MIXED") && (
              <RefundRecipientForm
                reservationId={reservation.id}
                route={reservation.refundRoute}
                status={reservation.refundDestinationStatus}
                bankSummary={reservation.refundBankSummary}
                guestToken={token}
                onSaved={(recipient) => setReservation((current) => current ? {
                  ...current,
                  refundDestinationStatus: recipient.refundDestinationStatus || "SUBMITTED",
                  refundBankSummary: recipient.refundBankSummary,
                } : current)}
              />
            )}

            <ViewportModal
              open={isCancelConfirmationOpen}
              onClose={() => setIsCancelConfirmationOpen(false)}
              labelledBy="lookup-cancel-confirmation-title"
              describedBy="lookup-cancel-confirmation-description"
              busy={isCancelling}
              panelClassName="max-w-md"
            >
              <div className="p-6">
                <p className="text-xs font-bold uppercase tracking-[0.18em] text-rose-700">Xác nhận yêu cầu</p>
                <h2 id="lookup-cancel-confirmation-title" className="mt-2 font-serif text-2xl font-bold text-[#0F2A43]">Gửi yêu cầu hủy đặt phòng?</h2>
                <p id="lookup-cancel-confirmation-description" className="mt-3 text-sm leading-6 text-[#66727C]">Phòng vẫn được giữ cho đến khi nhân viên xét duyệt. Thông tin tài khoản đã nhập chỉ được dùng nếu phát sinh hoàn tiền qua QR.</p>
                {cancelError && <p role="alert" className="mt-4 rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm font-medium text-rose-700">{cancelError}</p>}
              </div>
              <footer className="flex flex-col-reverse gap-3 border-t border-[#0F2A43]/10 px-6 py-4 sm:flex-row sm:justify-end">
                <button type="button" disabled={isCancelling} onClick={() => setIsCancelConfirmationOpen(false)} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43] transition hover:bg-[#F1F0EA] disabled:opacity-50">Quay lại kiểm tra</button>
                <button type="button" disabled={isCancelling} onClick={() => void handleCancel()} className="inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-rose-700 px-5 text-sm font-bold text-white transition hover:bg-rose-800 disabled:cursor-wait disabled:opacity-50">{isCancelling && <span aria-hidden="true" className="h-4 w-4 animate-spin rounded-full border-2 border-white border-r-transparent" />}{isCancelling ? "Đang gửi..." : "Gửi yêu cầu hủy"}</button>
              </footer>
            </ViewportModal>
          </div>
        ) : null}
      </div>
    </div>
  );
}

export default function GuestBookingLookupPage() {
  return (
    <Suspense fallback={<div className="min-h-screen bg-[#F1F0EA] px-6 py-28">Đang tải...</div>}>
      <GuestBookingLookupContent />
    </Suspense>
  );
}
