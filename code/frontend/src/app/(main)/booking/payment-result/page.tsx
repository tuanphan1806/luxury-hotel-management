/* eslint-disable @next/next/no-img-element */
"use client";

import React, { Suspense } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { publicApiClient } from "@/lib/api";
import { clearGuestReservationToken, getGuestReservationToken } from "@/lib/guest-reservation-token";

interface VerifiedPaymentResult {
  transactionId: string;
  transactionReference?: string;
  bookingId: number;
  reservationCode?: string;
  provider?: string;
  status: string;
  purpose?: string;
  amount?: number;
  message?: string;
  requestedBankCode?: string;
  bankCode?: string;
  cardType?: string;
  responseCode?: string;
  providerPayDate?: string;
  qrCodeUrl?: string;
  transferContent?: string;
  bankAccountNumber?: string;
  bankName?: string;
  accountHolder?: string;
  expectedAmount?: number;
  receivedAmount?: number;
  refundedAmount?: number;
  refundOutstandingAmount?: number;
  refundChannel?: string;
  refundStatus?: string;
  expiresAt?: string;
}

type VerificationState =
  | { state: "loading"; payment: null }
  | { state: "verified"; payment: VerifiedPaymentResult }
  | { state: "unverified"; payment: null };

const formatCurrency = (value: number | undefined, localeTag: string) =>
  typeof value === "number"
    ? new Intl.NumberFormat(localeTag, { style: "currency", currency: "VND", maximumFractionDigits: 0 }).format(value)
    : "—";

const PENDING_STATUSES = new Set(["PENDING", "PROCESSING"]);
const MAX_POLL_ATTEMPTS_WITHOUT_EXPIRY = 100;

const getExpiryTime = (value?: string) => {
  if (!value) return null;
  const timestamp = new Date(value).getTime();
  return Number.isNaN(timestamp) ? null : timestamp;
};

const formatCountdown = (seconds: number) => {
  const safeSeconds = Math.max(0, seconds);
  const hours = Math.floor(safeSeconds / 3600);
  const minutes = Math.floor((safeSeconds % 3600) / 60);
  const remainingSeconds = safeSeconds % 60;
  const parts = [minutes, remainingSeconds].map((part) => String(part).padStart(2, "0"));
  return hours > 0 ? `${String(hours).padStart(2, "0")}:${parts.join(":")}` : parts.join(":");
};

function PaymentResultContent() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const { localeTag, localize } = useLanguage();
  const transactionId = searchParams.get("transactionId")?.trim() || "";
  const [verification, setVerification] = React.useState<VerificationState>({ state: "loading", payment: null });
  const [pollAttempt, setPollAttempt] = React.useState(0);
  const [nowMs, setNowMs] = React.useState(() => Date.now());
  const [copiedField, setCopiedField] = React.useState<"amount" | "account" | "content" | null>(null);
  const [copyError, setCopyError] = React.useState("");
  const [abandonError, setAbandonError] = React.useState("");
  const [isAbandoning, setIsAbandoning] = React.useState(false);
  const paymentRef = React.useRef<VerifiedPaymentResult | null>(null);
  const copyResetTimerRef = React.useRef<number | undefined>(undefined);

  React.useEffect(() => {
    paymentRef.current = null;
    setPollAttempt(0);
    setNowMs(Date.now());
    setVerification(transactionId ? { state: "loading", payment: null } : { state: "unverified", payment: null });
  }, [transactionId]);

  React.useEffect(() => {
    let active = true;
    let retryTimer: number | undefined;
    const controller = new AbortController();

    if (!transactionId) {
      return () => {
        active = false;
        controller.abort();
      };
    }

    // Không polling khi tab nằm nền. Khi khách quay lại, listener visibility/focus
    // bên dưới sẽ kiểm tra ngay nên vẫn giữ nguyên cơ chế tự xác nhận thanh toán.
    if (document.visibilityState === "hidden") {
      return () => {
        active = false;
        controller.abort();
      };
    }

    publicApiClient
      .get(`/api/payments/result/${encodeURIComponent(transactionId)}`, { signal: controller.signal })
      .then(async (response) => {
        if (!active) return;
        const payment = (response.data?.data ?? response.data) as VerifiedPaymentResult | undefined;
        if (
          !payment?.transactionId ||
          payment.transactionId !== transactionId ||
          typeof payment.bookingId !== "number" ||
          !payment.status
        ) {
          throw new Error("Invalid payment verification response");
        }

        paymentRef.current = payment;
        setVerification({ state: "verified", payment });

        const normalizedStatus = payment.status.toUpperCase();
        if (PENDING_STATUSES.has(normalizedStatus)) {
          const expiryTime = getExpiryTime(payment.expiresAt);
          if ((expiryTime === null && pollAttempt < MAX_POLL_ATTEMPTS_WITHOUT_EXPIRY)
              || (expiryTime !== null && expiryTime > Date.now())) {
            retryTimer = window.setTimeout(() => {
              if (active) setPollAttempt((attempt) => attempt + 1);
            }, 3000);
          }
          return;
        }

        const bookingId = String(payment.bookingId);
        const staffFinalPaymentId = sessionStorage.getItem("staff_final_payment_reservation_id");
        if (staffFinalPaymentId === bookingId) {
          sessionStorage.removeItem("staff_final_payment_reservation_id");
          router.replace(`/dashboard/reservations?finalPaymentId=${bookingId}`);
          return;
        }

        const guestToken = getGuestReservationToken(bookingId);
        if (guestToken) {
          try {
            const reservationResponse = await publicApiClient.get(`/api/reservations/${bookingId}`, {
              headers: { "X-Guest-Token": guestToken },
            });
            const reservation = reservationResponse.data?.data ?? reservationResponse.data;
            if (["CHECKED_OUT", "NO_SHOW"].includes(String(reservation?.status || ""))) {
              clearGuestReservationToken(bookingId);
            }
          } catch {
            // Kết quả thanh toán đã được xác minh độc lập; lỗi dọn guest token không làm đổi kết quả.
          }
        }
      })
      .catch(() => {
        if (!active || controller.signal.aborted) return;
        const lastPayment = paymentRef.current;
        const lastStatus = lastPayment?.status?.toUpperCase() || "";
        const lastExpiryTime = getExpiryTime(lastPayment?.expiresAt);
        const shouldRetryKnownPayment = Boolean(
          lastPayment
          && PENDING_STATUSES.has(lastStatus)
          && ((lastExpiryTime === null && pollAttempt < MAX_POLL_ATTEMPTS_WITHOUT_EXPIRY)
              || (lastExpiryTime !== null && lastExpiryTime > Date.now())),
        );
        const shouldRetryInitialLoad = !lastPayment && pollAttempt < 20;
        if (shouldRetryKnownPayment || shouldRetryInitialLoad) {
          retryTimer = window.setTimeout(() => {
            if (active) setPollAttempt((attempt) => attempt + 1);
          }, 3000);
          return;
        }
        if (!lastPayment) setVerification({ state: "unverified", payment: null });
      });

    return () => {
      active = false;
      controller.abort();
      if (retryTimer !== undefined) window.clearTimeout(retryTimer);
    };
  }, [pollAttempt, router, transactionId]);

  React.useEffect(() => {
    if (!transactionId) return;

    const checkLatestStatus = () => {
      if (document.visibilityState === "visible") {
        setPollAttempt((attempt) => attempt + 1);
      }
    };

    window.addEventListener("focus", checkLatestStatus);
    window.addEventListener("online", checkLatestStatus);
    document.addEventListener("visibilitychange", checkLatestStatus);
    return () => {
      window.removeEventListener("focus", checkLatestStatus);
      window.removeEventListener("online", checkLatestStatus);
      document.removeEventListener("visibilitychange", checkLatestStatus);
    };
  }, [transactionId]);

  React.useEffect(() => () => {
    if (copyResetTimerRef.current !== undefined) window.clearTimeout(copyResetTimerRef.current);
  }, []);

  const payment = verification.payment;
  const guestReservationToken = payment?.bookingId
    ? getGuestReservationToken(payment.bookingId)
    : null;
  const reservationHref = verification.state === "unverified"
    ? "/booking/lookup"
    : guestReservationToken
      ? `/booking/lookup#token=${encodeURIComponent(guestReservationToken)}`
      : "/my-bookings";
  const status = payment?.status?.toUpperCase() || "";
  const purpose = payment?.purpose?.toUpperCase() || "";
  const provider = payment?.provider?.toUpperCase() || "";
  const refundChannel = payment?.refundChannel?.toUpperCase() || "";
  const refundStatus = payment?.refundStatus?.toUpperCase() || "";
  const refundedAmount = Math.max(0, payment?.refundedAmount ?? 0);
  const refundOutstandingAmount = Math.max(0, payment?.refundOutstandingAmount ?? 0);
  const providerLabel = provider === "SEPAY"
    ? localize("Thanh toán QR", "QR payment")
    : provider === "VNPAY"
      ? localize("Cổng thanh toán cũ", "Legacy payment gateway")
      : payment?.provider || localize("Cổng thanh toán", "Payment provider");
  const refundChannelLabel = refundChannel === "MANUAL_BANK_TRANSFER"
    ? localize("Hoàn qua QR", "QR refund")
    : refundChannel === "VNPAY_ORIGINAL"
      ? localize("Hoàn theo giao dịch gốc", "Original transaction refund")
      : localize("Theo sổ đối soát hoàn tiền", "According to the refund ledger");
  const expiryTime = getExpiryTime(payment?.expiresAt);
  const remainingSeconds = expiryTime === null ? null : Math.max(0, Math.ceil((expiryTime - nowMs) / 1000));
  const formattedExpiry = expiryTime === null
    ? ""
    : new Date(expiryTime).toLocaleString(localeTag, {
        day: "2-digit",
        month: "2-digit",
        year: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      });
  const isLoading = verification.state === "loading";
  const isRefunded = verification.state === "verified" && status === "REFUNDED";
  const hasOutstandingRefund = verification.state === "verified" && refundOutstandingAmount > 0;
  const isRefundPending = verification.state === "verified"
    && !isRefunded
    && (status === "REFUND_PENDING" || hasOutstandingRefund);
  const isPartiallyRefunded = verification.state === "verified"
    && !isRefunded
    && !isRefundPending
    && refundedAmount > 0;
  const isSuccess = verification.state === "verified"
    && status === "SUCCESS"
    && !isPartiallyRefunded
    && !isRefundPending;
  const isRawPending = verification.state === "verified" && PENDING_STATUSES.has(status);
  const isExpired = verification.state === "verified"
    && (status === "EXPIRED" || (isRawPending && remainingSeconds === 0));
  const isPending = isRawPending && !isExpired;
  const isFailure = verification.state === "verified"
    && !isSuccess
    && !isPartiallyRefunded
    && !isRefunded
    && !isRefundPending
    && !isPending
    && !isExpired;
  const isDeposit = purpose === "DEPOSIT";
  const expectedAmount = payment?.expectedAmount ?? payment?.amount;
  const receivedAmount = payment?.receivedAmount;
  const remainingAmount = typeof expectedAmount === "number" && typeof receivedAmount === "number"
    ? Math.max(0, expectedAmount - receivedAmount)
    : undefined;
  const showSepayInstructions = isPending && provider === "SEPAY";

  const handleAbandonPayment = async () => {
    if (!transactionId || isAbandoning) return;
    setIsAbandoning(true);
    setAbandonError("");
    try {
      await publicApiClient.post(
        `/api/payments/result/${encodeURIComponent(transactionId)}/abandon`,
      );
      router.replace("/rooms");
    } catch {
      setAbandonError(localize(
        "Chưa thể hủy phiên thanh toán. Vui lòng thử lại; phòng vẫn tự nhả khi hết 5 phút.",
        "We could not cancel this payment session. Try again; the room will still release when the five-minute timer ends.",
      ));
      setIsAbandoning(false);
    }
  };
  const completedRefundDescription = refundChannel === "MANUAL_BANK_TRANSFER"
    ? localize(
        `Khoản hoàn QR ${formatCurrency(refundedAmount, localeTag)} đã được ngân hàng đối soát thành công.`,
        `The ${formatCurrency(refundedAmount, localeTag)} QR refund was successfully reconciled by the bank.`,
      )
    : refundChannel === "VNPAY_ORIGINAL"
      ? localize(
          `Dữ liệu giao dịch lịch sử đã xác nhận hoàn ${formatCurrency(refundedAmount, localeTag)} về nguồn gốc.`,
          `The legacy transaction record confirms a ${formatCurrency(refundedAmount, localeTag)} refund to its original source.`,
        )
      : localize(
          `Sổ đối soát đã ghi nhận hoàn thành ${formatCurrency(refundedAmount, localeTag)}.`,
          `The refund ledger records ${formatCurrency(refundedAmount, localeTag)} as completed.`,
        );
  const pendingRefundDescription = refundChannel === "MANUAL_BANK_TRANSFER"
    ? refundStatus === "FAILED"
      ? localize(
          `Khoản hoàn ${formatCurrency(refundOutstandingAmount, localeTag)} chưa chuyển thành công. Khách sạn phải đối soát và thực hiện lại; hệ thống chưa ghi nhận đã hoàn.`,
          `The ${formatCurrency(refundOutstandingAmount, localeTag)} refund has not been transferred successfully. The hotel must reconcile and retry it; the system does not mark it as refunded.`,
        )
      : localize(
          `Khách sạn đang xử lý khoản hoàn QR ${formatCurrency(refundOutstandingAmount, localeTag)}. Đơn chỉ được chốt sau khi ngân hàng xác nhận đúng mã hoàn và số tiền.`,
          `The hotel is processing a ${formatCurrency(refundOutstandingAmount, localeTag)} QR refund. The booking is finalized only after the bank confirms the exact refund code and amount.`,
        )
    : refundChannel === "VNPAY_ORIGINAL"
      ? localize(
          `Đang chờ đối soát khoản hoàn lịch sử ${formatCurrency(refundOutstandingAmount, localeTag)} theo giao dịch gốc.`,
          `Waiting to reconcile the legacy ${formatCurrency(refundOutstandingAmount, localeTag)} refund against its original transaction.`,
        )
      : localize(
          `Hệ thống đang theo dõi khoản hoàn ${formatCurrency(refundOutstandingAmount, localeTag)}; chưa ghi nhận là đã hoàn.`,
          `The system is tracking a ${formatCurrency(refundOutstandingAmount, localeTag)} refund; it is not yet recorded as completed.`,
        );
  const isConfirmedOutcome = isSuccess || isPartiallyRefunded || isRefunded;

  React.useEffect(() => {
    if (!isRawPending || expiryTime === null) return;

    let countdownTimer: number | undefined;
    const updateCountdown = () => setNowMs(Date.now());
    updateCountdown();
    if (Date.now() < expiryTime) countdownTimer = window.setInterval(updateCountdown, 1000);

    return () => {
      if (countdownTimer !== undefined) window.clearInterval(countdownTimer);
    };
  }, [expiryTime, isRawPending]);

  const handleCopy = async (value: string | undefined, field: "amount" | "account" | "content") => {
    if (!value) return;
    setCopyError("");
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(value);
      } else {
        const textarea = document.createElement("textarea");
        textarea.value = value;
        textarea.setAttribute("readonly", "");
        textarea.style.position = "fixed";
        textarea.style.opacity = "0";
        document.body.appendChild(textarea);
        textarea.select();
        const copied = document.execCommand("copy");
        document.body.removeChild(textarea);
        if (!copied) throw new Error("Copy command failed");
      }
      setCopiedField(field);
      if (copyResetTimerRef.current !== undefined) window.clearTimeout(copyResetTimerRef.current);
      copyResetTimerRef.current = window.setTimeout(() => setCopiedField(null), 2000);
    } catch {
      setCopyError(localize("Không thể sao chép tự động. Vui lòng chọn và sao chép thủ công.", "Could not copy automatically. Please select and copy the value manually."));
    }
  };

  const heading = isLoading
    ? localize("Đang xác minh giao dịch", "Verifying payment")
    : isPartiallyRefunded
      ? localize("Thanh toán đã ghi nhận, hoàn một phần", "Payment recorded, partially refunded")
    : isSuccess
      ? localize("Thanh toán thành công", "Payment successful")
      : isRefunded
        ? localize("Khoản thanh toán đã được hoàn", "Payment refunded")
      : isRefundPending
        ? localize("Giao dịch chờ hoàn tiền", "Refund pending")
        : isPending
          ? localize("Thanh toán đang được xác minh", "Payment verification pending")
          : isExpired
            ? localize("Phiên thanh toán đã hết hạn", "Payment session expired")
          : isFailure
            ? localize("Thanh toán không thành công", "Payment unsuccessful")
            : localize("Không thể xác minh giao dịch", "Payment could not be verified");

  const description = isLoading
    ? localize("Hệ thống đang đối chiếu trực tiếp với dữ liệu thanh toán.", "We are checking the server-side payment record.")
    : isPartiallyRefunded
      ? `${completedRefundDescription} ${localize("Phần tiền còn lại vẫn được ghi nhận cho đơn đặt phòng.", "The remaining amount is still recorded against the reservation.")}`
    : isSuccess
      ? isDeposit
        ? localize("Tiền đặt cọc đã được ghi nhận. Đơn đang chờ khách sạn xác nhận.", "Your deposit was recorded. The reservation is awaiting hotel confirmation.")
        : purpose === "WALK_IN"
          ? localize("Thanh toán cho đơn walk-in đã được ghi nhận.", "The walk-in payment was recorded.")
          : localize("Khoản thanh toán cuối đã được ghi nhận vào đơn đặt phòng.", "The final payment was recorded on the reservation.")
      : isRefunded
        ? completedRefundDescription
      : isRefundPending
        ? pendingRefundDescription
        : isPending
          ? provider === "SEPAY"
            ? localize("Quét mã hoặc chuyển khoản đúng thông tin bên dưới. Hệ thống tự kiểm tra giao dịch mỗi 3 giây.", "Scan the QR code or transfer using the exact details below. The system checks the transaction every 3 seconds.")
            : localize(`${providerLabel} chưa xác nhận kết quả cuối cùng. Vui lòng không thanh toán lại ngay.`, `${providerLabel} has not confirmed the final result. Please do not pay again yet.`)
          : isExpired
            ? localize("Mã thanh toán không còn hiệu lực. Không chuyển khoản bằng thông tin cũ; hãy tạo một phiên thanh toán mới.", "This payment code is no longer valid. Do not transfer using the old details; create a new payment session.")
          : isFailure
            ? payment?.message || localize("Giao dịch không được ghi nhận thành công.", "The payment was not completed.")
            : localize("Đường dẫn thiếu mã giao dịch hợp lệ hoặc giao dịch không tồn tại. Không có khoản thanh toán nào được xác nhận từ thông tin trên URL.", "The link has no valid transaction ID or the transaction does not exist. No payment is confirmed from URL data alone.");

  const statusLabel = isLoading
    ? localize("ĐANG KIỂM TRA", "CHECKING")
    : isPartiallyRefunded
      ? localize("ĐÃ HOÀN MỘT PHẦN", "PARTIALLY REFUNDED")
    : isSuccess
      ? localize("ĐÃ XÁC NHẬN", "VERIFIED")
      : isRefunded
        ? localize("ĐÃ HOÀN TIỀN", "REFUNDED")
      : isRefundPending
        ? localize("CHỜ HOÀN TIỀN", "REFUND PENDING")
        : isPending
          ? localize("ĐANG XỬ LÝ", "PROCESSING")
          : isExpired
            ? localize("ĐÃ HẾT HẠN", "EXPIRED")
          : isFailure
            ? localize("KHÔNG THÀNH CÔNG", "UNSUCCESSFUL")
            : localize("CHƯA XÁC MINH", "UNVERIFIED");

  const bannerClass = isSuccess
    ? "bg-primary-navy"
    : isPartiallyRefunded
      ? "bg-sky-800"
    : isRefunded
      ? "bg-emerald-800"
    : isRefundPending || isPending
      ? "bg-[#0F2A43]"
      : isExpired
        ? "bg-slate-800"
      : isFailure
        ? "bg-red-950"
        : "bg-slate-700";

  const statusClass = isSuccess
    ? "border-emerald-200 bg-emerald-50 text-emerald-800"
    : isPartiallyRefunded
      ? "border-sky-200 bg-sky-50 text-sky-800"
    : isRefunded
      ? "border-emerald-200 bg-emerald-50 text-emerald-800"
    : isRefundPending || isPending
      ? "border-amber-200 bg-amber-50 text-amber-800"
      : isExpired
        ? "border-slate-200 bg-slate-50 text-slate-700"
      : isFailure
        ? "border-rose-200 bg-rose-50 text-rose-800"
        : "border-slate-200 bg-slate-50 text-slate-700";

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#F1F0EA] px-3 py-8 sm:px-6 sm:py-14">
      <section className={`w-full overflow-hidden rounded-2xl border border-[#0F2A43]/10 bg-white shadow-[0_24px_70px_rgba(15,42,67,0.14)] ${showSepayInstructions ? "max-w-5xl" : "max-w-xl"}`}>
        <header className={`relative flex flex-col items-center gap-4 px-5 py-6 text-center text-white sm:flex-row sm:px-8 sm:py-7 sm:text-left ${bannerClass}`}>
          <div className="flex h-14 w-14 shrink-0 items-center justify-center rounded-full border border-white/25 bg-white/10" aria-hidden="true">
            {isLoading ? (
              <span className="h-7 w-7 animate-spin rounded-full border-2 border-white/30 border-t-white motion-reduce:animate-none" />
            ) : isConfirmedOutcome ? (
              <svg className="h-8 w-8 text-[#C8A35B]" fill="none" stroke="currentColor" strokeWidth="2.5" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" /></svg>
            ) : (
              <span className="text-2xl font-bold">!</span>
            )}
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-[11px] font-bold uppercase tracking-[0.18em] text-[#D8C398]">{providerLabel}</p>
            <h1 className="mt-1 text-2xl font-bold tracking-tight sm:text-3xl">{heading}</h1>
            <p className="mt-1 text-xs font-semibold text-white/70">
              {payment?.reservationCode
                ? `${localize("Mã đặt phòng", "Reservation")}: ${payment.reservationCode}`
                : localize("Kết quả được đối chiếu từ máy chủ", "Result verified from the server")}
            </p>
          </div>
          {isPending && (
            <div className="flex shrink-0 items-center gap-2 rounded-full border border-white/20 bg-white/10 px-4 py-2 text-xs font-bold text-white">
              <span className="relative flex h-2.5 w-2.5" aria-hidden="true">
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-[#D8C398] opacity-70 motion-reduce:animate-none" />
                <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-[#D8C398]" />
              </span>
              {localize("Tự động kiểm tra mỗi 3 giây", "Auto-checking every 3 seconds")}
            </div>
          )}
        </header>
        <p className="sr-only" aria-live="polite">{heading}. {description}</p>

        <div className="space-y-6 p-4 sm:p-8">
          <div className="space-y-2 text-center">
            <p className="text-sm font-medium leading-6 text-[#091E30]">{description}</p>
            {isSuccess && isDeposit && (
              <p className="text-xs font-medium text-[#66727C]">{localize("Phòng tiếp tục được giữ theo đơn DRAFT cho đến khi nhân viên xác nhận.", "Rooms remain held under the DRAFT reservation until staff confirms it.")}</p>
            )}
          </div>

          {showSepayInstructions && (
            <section className="overflow-hidden rounded-2xl border border-[#0F2A43]/12 bg-[#FBFAF6]" aria-labelledby="sepay-payment-instructions">
              <div className="grid lg:grid-cols-[minmax(320px,380px)_1fr]">
                <div className="flex flex-col items-center justify-center border-b border-[#0F2A43]/10 bg-[#EAE2D2] p-5 sm:p-7 lg:border-b-0 lg:border-r">
                  <div className="mb-4 flex w-full items-center justify-between gap-3">
                    <div>
                      <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-[#80632F]">VietQR</p>
                      <p className="mt-0.5 text-sm font-bold text-[#0F2A43]">{localize("Quét bằng ứng dụng ngân hàng", "Scan with your banking app")}</p>
                    </div>
                    {remainingSeconds !== null && (
                      <div className="rounded-lg border border-[#B8944F]/45 bg-white/80 px-3 py-2 text-right" role="timer" aria-live="off" aria-label={localize(`Còn ${formatCountdown(remainingSeconds)}`, `${formatCountdown(remainingSeconds)} remaining`)}>
                        <p className="text-[10px] font-bold uppercase tracking-wide text-[#66727C]">{localize("Còn lại", "Remaining")}</p>
                        <p className="font-mono text-lg font-extrabold tabular-nums text-[#0F2A43]">{formatCountdown(remainingSeconds)}</p>
                      </div>
                    )}
                  </div>
                  {/* The verified backend response owns the QR URL, which may use a provider-managed image host. */}
                  {payment?.qrCodeUrl ? (
                    <div className="relative w-full max-w-[19rem] rounded-2xl bg-white p-3 shadow-[0_14px_35px_rgba(15,42,67,0.12)] ring-1 ring-[#0F2A43]/10">
                      <span className="absolute left-2 top-2 h-7 w-7 rounded-tl-lg border-l-2 border-t-2 border-[#B8944F]" aria-hidden="true" />
                      <span className="absolute right-2 top-2 h-7 w-7 rounded-tr-lg border-r-2 border-t-2 border-[#B8944F]" aria-hidden="true" />
                      <span className="absolute bottom-2 left-2 h-7 w-7 rounded-bl-lg border-b-2 border-l-2 border-[#B8944F]" aria-hidden="true" />
                      <span className="absolute bottom-2 right-2 h-7 w-7 rounded-br-lg border-b-2 border-r-2 border-[#B8944F]" aria-hidden="true" />
                      <img
                        src={payment.qrCodeUrl}
                        alt={localize(
                          `Mã QR thanh toán cho đơn ${payment.reservationCode || payment.bookingId}`,
                          `Payment QR code for reservation ${payment.reservationCode || payment.bookingId}`,
                        )}
                        className="aspect-square w-full rounded-xl object-contain"
                        loading="eager"
                        decoding="async"
                      />
                    </div>
                  ) : (
                    <div className="flex aspect-square w-full max-w-[19rem] items-center justify-center rounded-lg border border-dashed border-[#0F2A43]/25 bg-[#F1F0EA] p-6 text-center text-sm font-semibold text-[#66727C]">
                      {localize("Mã QR đang được chuẩn bị. Hệ thống vẫn tiếp tục kiểm tra giao dịch.", "The QR code is being prepared. Payment verification is still running.")}
                    </div>
                  )}
                  <p className="mt-4 flex items-center gap-2 text-center text-xs font-semibold text-[#66727C]">
                    <svg className="h-4 w-4 shrink-0 text-emerald-700" fill="none" stroke="currentColor" strokeWidth="2" viewBox="0 0 24 24" aria-hidden="true"><path strokeLinecap="round" strokeLinejoin="round" d="M12 6v6l4 2m5-2a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
                    {localize("Không cần bấm xác nhận sau khi chuyển khoản", "No confirmation button is needed after transfer")}
                  </p>
                </div>

                <div className="space-y-5 p-5 sm:p-7">
                  <div>
                    <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-[#80632F]">{localize("Thanh toán an toàn", "Secure payment")}</p>
                    <h2 id="sepay-payment-instructions" className="mt-1 text-xl font-extrabold text-[#0F2A43] sm:text-2xl">{localize("Chi tiết chuyển khoản", "Transfer details")}</h2>
                    <p className="mt-2 text-sm font-medium leading-6 text-[#66727C]">{localize("QR đã chứa sẵn số tiền và nội dung. Hãy kiểm tra lại trước khi xác nhận trên ứng dụng ngân hàng.", "The QR code already includes the amount and transfer content. Review them before confirming in your banking app.")}</p>
                  </div>

                  <div className="rounded-xl border border-[#B8944F]/50 bg-[#F0EADF] p-4 sm:p-5">
                    <div className="flex items-center justify-between gap-4">
                      <div className="min-w-0">
                        <p className="text-xs font-semibold text-[#66727C]">{localize("Số tiền cần chuyển", "Amount to transfer")}</p>
                        <p className="mt-1 text-2xl font-extrabold tabular-nums text-[#0F2A43] sm:text-3xl">{formatCurrency(expectedAmount, localeTag)}</p>
                      </div>
                      {typeof expectedAmount === "number" && (
                        <button type="button" onClick={() => void handleCopy(String(expectedAmount), "amount")} className="min-h-11 shrink-0 rounded-lg border border-[#0F2A43]/20 bg-white px-3 text-xs font-bold text-[#0F2A43] hover:bg-[#0F2A43]/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">
                          {copiedField === "amount" ? localize("Đã chép", "Copied") : localize("Sao chép", "Copy")}
                        </button>
                      )}
                    </div>
                    {typeof receivedAmount === "number" && receivedAmount > 0 && (
                      <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs font-semibold text-[#66727C]">
                        <span>{localize("Đã ghi nhận", "Received")}: {formatCurrency(receivedAmount, localeTag)}</span>
                        {typeof remainingAmount === "number" && remainingAmount > 0 && <span>{localize("Còn thiếu", "Remaining")}: {formatCurrency(remainingAmount, localeTag)}</span>}
                      </div>
                    )}
                  </div>

                  {payment?.bankAccountNumber && (
                    <div className="rounded-lg border border-[#0F2A43]/10 bg-white p-4">
                      <div className="flex items-start justify-between gap-4">
                        <div className="min-w-0">
                          <p className="text-xs font-semibold text-[#66727C]">{localize("Tài khoản nhận", "Beneficiary account")}</p>
                          <p className="mt-1 break-all font-mono text-lg font-extrabold text-[#0F2A43]">{payment.bankAccountNumber}</p>
                          <p className="mt-1 text-xs font-medium text-[#66727C]">{[payment.bankName, payment.accountHolder].filter(Boolean).join(" · ")}</p>
                        </div>
                        <button
                          type="button"
                          onClick={() => void handleCopy(payment.bankAccountNumber, "account")}
                          className="min-h-11 shrink-0 rounded-lg border border-[#0F2A43]/20 px-3 text-xs font-bold text-[#0F2A43] hover:bg-[#0F2A43]/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]"
                          aria-label={copiedField === "account"
                            ? localize("Đã sao chép số tài khoản", "Account number copied")
                            : localize("Sao chép số tài khoản", "Copy account number")}
                        >
                          {copiedField === "account" ? localize("Đã chép", "Copied") : localize("Sao chép", "Copy")}
                        </button>
                      </div>
                    </div>
                  )}

                  {payment?.transferContent && (
                    <div className="rounded-lg border border-[#0F2A43]/10 bg-white p-4">
                      <div className="flex items-start justify-between gap-4">
                        <div className="min-w-0">
                          <p className="text-xs font-semibold text-[#66727C]">{localize("Nội dung chuyển khoản", "Transfer content")}</p>
                          <p className="mt-1 break-all font-mono text-lg font-extrabold text-[#0F2A43]">{payment.transferContent}</p>
                        </div>
                        <button
                          type="button"
                          onClick={() => void handleCopy(payment.transferContent, "content")}
                          className="min-h-11 shrink-0 rounded-lg border border-[#0F2A43]/20 px-3 text-xs font-bold text-[#0F2A43] hover:bg-[#0F2A43]/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]"
                          aria-label={copiedField === "content"
                            ? localize("Đã sao chép nội dung chuyển khoản", "Transfer content copied")
                            : localize("Sao chép nội dung chuyển khoản", "Copy transfer content")}
                        >
                          {copiedField === "content" ? localize("Đã chép", "Copied") : localize("Sao chép", "Copy")}
                        </button>
                      </div>
                    </div>
                  )}

                  <ol className="grid gap-2.5 sm:grid-cols-3" aria-label={localize("Các bước thanh toán", "Payment steps")}>
                    {[
                      localize("Quét QR", "Scan QR"),
                      localize("Chuyển đúng tiền", "Pay exact amount"),
                      localize("Chờ tự xác nhận", "Wait for auto-confirmation"),
                    ].map((step, index) => (
                      <li key={step} className="flex items-center gap-2 rounded-lg border border-[#0F2A43]/10 bg-white p-3 text-xs font-bold text-[#0F2A43]">
                        <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-[#0F2A43] text-[11px] text-white">{index + 1}</span>
                        {step}
                      </li>
                    ))}
                  </ol>

                  <div className="flex items-start gap-3 rounded-xl border border-emerald-200 bg-emerald-50 p-3.5 text-xs font-semibold leading-5 text-emerald-900">
                    <span className="relative mt-1 flex h-2.5 w-2.5 shrink-0" aria-hidden="true"><span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-500 opacity-60 motion-reduce:animate-none" /><span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-emerald-600" /></span>
                    <span>{localize("Hệ thống đang chờ thông báo từ ngân hàng và sẽ tự chuyển sang trạng thái thành công. Bạn có thể chuyển sang ứng dụng ngân hàng rồi quay lại trang này; phiên vẫn được giữ đến khi hết 5 phút.", "The system is waiting for the bank notification and will switch to successful automatically. You may open your banking app and return here; the session remains active until the five-minute limit.")}</span>
                  </div>
                  <p className={`min-h-5 text-xs font-semibold ${copyError ? "text-rose-700" : "text-emerald-700"}`} role="status" aria-live="polite">
                    {copyError || (copiedField
                      ? copiedField === "amount"
                        ? localize("Đã sao chép số tiền.", "Amount copied.")
                        : copiedField === "account"
                          ? localize("Đã sao chép số tài khoản.", "Account number copied.")
                          : localize("Đã sao chép nội dung chuyển khoản.", "Transfer content copied.")
                      : "")}
                  </p>
                </div>
              </div>
            </section>
          )}

          <dl className="space-y-3 rounded-xl border border-[#0F2A43]/10 bg-[#F1F0EA] p-5 text-xs">
            <div className="flex items-center justify-between gap-4 border-b border-[#0F2A43]/10 pb-3">
              <dt className="font-semibold text-[#66727C]">{localize("Mã đặt phòng", "Reservation code")}</dt>
              <dd className="font-bold text-[#0F2A43]">{payment?.reservationCode || "—"}</dd>
            </div>
            <div className="flex items-center justify-between gap-4 border-b border-[#0F2A43]/10 pb-3">
              <dt className="font-semibold text-[#66727C]">{localize("Mã tham chiếu giao dịch", "Transaction reference")}</dt>
              <dd className="max-w-[60%] break-all text-right font-mono font-semibold text-[#091E30]">{payment?.transactionReference || payment?.transactionId || "—"}</dd>
            </div>
            <div className="flex items-center justify-between gap-4 border-b border-[#0F2A43]/10 pb-3">
              <dt className="font-semibold text-[#66727C]">{localize("Số tiền", "Amount")}</dt>
              <dd className="font-bold tabular-nums text-[#0F2A43]">{formatCurrency(expectedAmount, localeTag)}</dd>
            </div>
            {typeof receivedAmount === "number" && (
              <div className="flex items-center justify-between gap-4 border-b border-[#0F2A43]/10 pb-3">
                <dt className="font-semibold text-[#66727C]">{localize("Đã ghi nhận", "Received")}</dt>
                <dd className="font-bold tabular-nums text-[#0F2A43]">{formatCurrency(receivedAmount, localeTag)}</dd>
              </div>
            )}
            {refundedAmount > 0 && (
              <div className="flex items-center justify-between gap-4 border-b border-[#0F2A43]/10 pb-3">
                <dt className="font-semibold text-[#66727C]">{localize("Đã hoàn thực tế", "Completed refund")}</dt>
                <dd className="font-bold tabular-nums text-emerald-800">{formatCurrency(refundedAmount, localeTag)}</dd>
              </div>
            )}
            {refundOutstandingAmount > 0 && (
              <div className="flex items-center justify-between gap-4 border-b border-[#0F2A43]/10 pb-3">
                <dt className="font-semibold text-[#66727C]">{localize("Còn chờ hoàn", "Refund outstanding")}</dt>
                <dd className="font-bold tabular-nums text-amber-800">{formatCurrency(refundOutstandingAmount, localeTag)}</dd>
              </div>
            )}
            {(refundedAmount > 0 || refundOutstandingAmount > 0) && (
              <div className="flex items-center justify-between gap-4 border-b border-[#0F2A43]/10 pb-3">
                <dt className="font-semibold text-[#66727C]">{localize("Kênh hoàn", "Refund channel")}</dt>
                <dd className="max-w-[60%] text-right font-semibold text-[#091E30]">{refundChannelLabel}</dd>
              </div>
            )}
            <div className="flex items-center justify-between gap-4 border-b border-[#0F2A43]/10 pb-3">
              <dt className="font-semibold text-[#66727C]">{localize("Trạng thái", "Status")}</dt>
              <dd className={`rounded-full border px-2.5 py-1 font-bold ${statusClass}`}>{statusLabel}</dd>
            </div>
            {formattedExpiry && (
              <div className="flex items-center justify-between gap-4 border-b border-[#0F2A43]/10 pb-3">
                <dt className="font-semibold text-[#66727C]">{localize("Hết hạn lúc", "Expires at")}</dt>
                <dd className="text-right font-semibold tabular-nums text-[#091E30]">{formattedExpiry}</dd>
              </div>
            )}
            <div className="flex items-center justify-between gap-4">
              <dt className="font-semibold text-[#66727C]">{localize("Cổng thanh toán", "Payment gateway")}</dt>
              <dd className="text-right font-bold text-[#091E30]">
                {providerLabel}
                {provider === "VNPAY" && (payment?.cardType || payment?.requestedBankCode) && (
                  <span className="mt-0.5 block text-[10px] font-semibold text-[#66727C]">
                    {payment.cardType === "QRCODE" || payment.requestedBankCode === "VNPAYQR"
                      ? "QR"
                      : payment.cardType || payment.requestedBankCode}
                    {payment.bankCode ? ` · ${payment.bankCode}` : ""}
                  </span>
                )}
              </dd>
            </div>
          </dl>

          <div className="flex flex-col gap-3 pt-1 sm:flex-row">
            {isPending && isDeposit && (
              <button
                type="button"
                onClick={() => void handleAbandonPayment()}
                disabled={isAbandoning}
                className="flex min-h-11 flex-1 items-center justify-center rounded-lg border border-rose-300 px-5 text-xs font-bold text-rose-800 transition hover:bg-rose-50 active:translate-y-px disabled:cursor-wait disabled:opacity-60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]"
              >
                {isAbandoning
                  ? localize("Đang nhả phòng...", "Releasing room...")
                  : localize("Hủy thanh toán và chọn lại", "Cancel payment and choose again")}
              </button>
            )}
            {isConfirmedOutcome && (
              <button type="button" onClick={() => window.print()} className="flex min-h-11 flex-1 items-center justify-center rounded-lg border border-[#0F2A43] px-5 text-xs font-bold text-[#0F2A43] hover:bg-[#0F2A43]/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">
                {localize("In kết quả thanh toán", "Print payment result")}
              </button>
            )}
            {!isConfirmedOutcome && !isLoading && (guestReservationToken ? (
              <a href={reservationHref} className="flex min-h-11 flex-1 items-center justify-center rounded-lg border border-[#0F2A43] px-5 text-center text-xs font-bold text-[#0F2A43] hover:bg-[#0F2A43]/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">
                {localize("Xem đơn đặt phòng", "View reservation")}
              </a>
            ) : (
              <Link href={reservationHref} className="flex min-h-11 flex-1 items-center justify-center rounded-lg border border-[#0F2A43] px-5 text-center text-xs font-bold text-[#0F2A43] hover:bg-[#0F2A43]/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">
                {verification.state === "unverified" ? localize("Tra cứu đặt phòng", "Look up reservation") : localize("Xem đơn đặt phòng", "View reservation")}
              </Link>
            ))}
            {(isPending || isExpired) && (
              <button
                type="button"
                onClick={() => setPollAttempt((attempt) => attempt + 1)}
                className="flex min-h-11 flex-1 items-center justify-center rounded-lg border border-amber-700 px-5 text-xs font-bold text-amber-800 hover:bg-amber-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]"
                aria-describedby="payment-refresh-help"
              >
                {localize("Kiểm tra lại thanh toán", "Check payment again")}
              </button>
            )}
            <Link href="/" className="flex min-h-11 flex-1 items-center justify-center rounded-lg bg-[#0F2A43] px-5 text-center text-xs font-bold text-white hover:bg-[#091E30] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">
              {localize("Về trang chủ", "Back to home")}
            </Link>
          </div>
            {abandonError && <p className="text-center text-xs font-semibold text-rose-700" role="alert">{abandonError}</p>}
            {(isPending || isExpired) && <p id="payment-refresh-help" className="text-center text-xs font-medium text-[#66727C]">{localize("Nút kiểm tra chỉ tải lại trạng thái từ máy chủ, không tự xác nhận đã thanh toán.", "The check button only reloads the server status; it never marks the payment as successful.")}</p>}
        </div>
      </section>
    </div>
  );
}

export default function PaymentResultPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center bg-[#F1F0EA] text-sm font-semibold text-[#0F2A43]">Đang tải kết quả giao dịch...</div>}>
      <PaymentResultContent />
    </Suspense>
  );
}
