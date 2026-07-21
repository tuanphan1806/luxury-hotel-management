/* eslint-disable @next/next/no-img-element */
"use client";

import React, { useCallback, useEffect, useMemo, useState } from "react";
import dynamic from "next/dynamic";
import { apiClient, cachedGet, getApiErrorStatus } from "@/lib/api";
import Toast from "@/components/UI/Toast";
import ViewportModal from "@/components/UI/ViewportModal";
import type { ReservationInvoice } from "@/components/reservations/ReservationInvoiceModal";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import type { RefundDestinationStatus, RefundRoute } from "@/components/refunds/RefundRecipientForm";
import DateTimeField from "@/components/forms/DateTimeField";
import ImageUploadField from "@/components/UI/ImageUploadField";
import type { UploadedImage } from "@/lib/image-upload";
import { useDashboardRole } from "@/hooks/use-dashboard-role";
import {
  clearIdempotencyKey,
  getOrCreateIdempotencyKey,
} from "@/lib/idempotency";
import {
  DashboardFilterPanel,
  DashboardSearchField,
  DashboardSelectField,
  FilterQuickButton,
} from "@/components/dashboard/DashboardFilterPanel";

const ReservationInvoiceModal = dynamic(
  () => import("@/components/reservations/ReservationInvoiceModal"),
  { ssr: false },
);

type ReservationStatus = "DRAFT" | "PAYMENT_PENDING" | "CONFIRMED" | "CANCELLATION_PENDING" | "CANCELLED" | "CHECKED_IN" | "CHECKED_OUT" | "NO_SHOW";
type PaymentFilter = "ALL" | "UNPAID" | "PARTIAL" | "PAID";

interface ReservationRoomType {
  id: number;
  roomTypeId: number;
  roomTypeName: string;
  roomTypeNameEn?: string;
  quantity: number;
  subtotal?: number;
  roomHold?: {
    id: number;
    expiresAt: string;
    status: "ACTIVE" | "CONVERTED" | "RELEASED" | "EXPIRED";
  };
}

interface ReservationItem {
  id: number;
  reservationCode: string;
  customerId?: number;
  customerName?: string;
  checkIn: string;
  checkOut: string;
  actualCheckIn?: string;
  actualCheckOut?: string;
  lateCheckoutFee?: number;
  totalAmount: number;
  plannedTotalAmount?: number;
  paidAmount?: number;
  status: ReservationStatus;
  note?: string;
  cancellationReason?: string;
  cancellationRefundPending?: boolean;
  cancellationFee?: number;
  refundableAmount?: number;
  earlyCheckoutAdjustment?: number;
  checkoutAdditionalFee?: number;
  guestCount: number;
  noShowEligible?: boolean;
  noShowEligibleAt?: string;
  refundRoute?: RefundRoute;
  refundDestinationStatus?: RefundDestinationStatus;
  refundBankSummary?: string;
  roomTypes?: ReservationRoomType[];
}

interface WalkInOperationResponse {
  reservationCreated: boolean;
  reservation: ReservationItem;
  paymentCreationStatus: "NOT_REQUESTED" | "NOT_CREATED" | "PENDING" | "SUCCESS" | "FAILED" | "CANCELLED";
  paymentInstructions?: PaymentUrlPayload;
  paymentError?: string;
}

interface RoomItem {
  id: number;
  roomName: string;
  roomTypeId: number;
  roomTypeName: string;
  roomTypeNameEn?: string;
  status: string;
  cleaningStatus: string;
  price?: number;
}

interface WalkInPriceOverrideDraft {
  enabled: boolean;
  amount: string;
  reasonCode: string;
  note: string;
}

interface FinalPayment {
  reservationId: number;
  totalAmount: number;
  roomCharge: number;
  plannedRoomCharge: number;
  paidAmount: number;
  remainingAmount: number;
  lateCheckoutFee?: number;
  refundableAmount?: number;
  earlyCheckoutAdjustment?: number;
  checkoutAdditionalFee?: number;
  fullyPaid: boolean;
  reconciliationStatus: "MATCHED" | "MISMATCH";
  blockingReasons: string[];
  reservedRefundAmount: number;
  uncoveredRefundAmount: number;
  paymentPending: boolean;
  refundPending: boolean;
}

interface CheckoutReconciliationApi {
  reservationId: number;
  requiredAmount: number;
  acceptedAmount: number;
  reservedRefundAmount: number;
  uncoveredRefundAmount: number;
  outstandingAmount: number;
  deltaAmount: number;
  lateCheckoutFee?: number;
  earlyCheckoutAdjustment?: number;
  checkoutAdditionalFee?: number;
  paymentPending: boolean;
  refundPending: boolean;
  status: "MATCHED" | "MISMATCH";
  blockingReasons: string[];
}

interface ReservationAuditItem {
  id: number;
  action: string;
  actorName: string;
  actorRole: string;
  details?: string;
  riskLevel?: "NORMAL" | "MEDIUM" | "HIGH" | "CRITICAL";
  targetType?: string;
  targetId?: string;
  oldValue?: Record<string, unknown>;
  newValue?: Record<string, unknown>;
  detail?: Record<string, unknown>;
  occurredAtUtc: string;
}

interface PendingRefund {
  refundId: string;
  transactionId: string;
  transactionReference?: string;
  providerTransactionId?: string;
  bookingId: number;
  reservationCode?: string;
  originalProvider?: "CASH" | "SEPAY" | "VNPAY";
  refundProvider?: "CASH" | "SEPAY" | "VNPAY";
  refundChannel?: "VNPAY_ORIGINAL" | "MANUAL_BANK_TRANSFER" | "CASH_AT_COUNTER";
  status: "AWAITING_CUSTOMER_INFO" | "READY_FOR_MANUAL_TRANSFER" | "REQUESTED" | "PROCESSING" | "SUCCEEDED" | "FAILED" | "MANUAL_REVIEW";
  amount: number;
  expectedAmount?: number;
  requestId?: string;
  refundCode?: string;
  refundTransactionId?: string;
  bankReferenceCode?: string;
  completionMethod?: "SEPAY_WEBHOOK" | "MANUAL_FALLBACK" | "CASH_HANDOVER" | "PROVIDER_API" | "LEGACY";
  responseCode?: string;
  transactionStatus?: string;
  message?: string;
  requestedAt?: string;
  updatedAt?: string;
  canRetry?: boolean;
  canReconcile?: boolean;
  recipientStatus?: RefundDestinationStatus | "REJECTED" | "SUPERSEDED";
  recipientBankName?: string;
  recipientBankCode?: string;
  recipientAccountMasked?: string;
  manualTransferReference?: string;
  manualTransferredAt?: string;
  recipientRequired?: boolean;
  canCompleteManually?: boolean;
  canCompleteCash?: boolean;
  awaitingBankConfirmation?: boolean;
  manualFallbackAvailableAtUtc?: string;
  manualFallbackOpenedAtUtc?: string;
  manualFallbackOpenedBy?: string;
  manualFallbackReason?: string;
}

interface SePayReviewEvent {
  eventId: string;
  providerEventId: string;
  providerReference?: string;
  bankReferenceCode?: string;
  transferType?: "in" | "out";
  amount?: number;
  paymentCode?: string;
  providerOccurredAt?: string;
  providerOccurredAtUtc?: string;
  accountNumberMasked?: string;
  message?: string;
  status?: "REVIEW_REQUIRED" | "PROCESSED" | "IGNORED";
  reviewNote?: string;
  createdAt?: string;
}

interface SePayRecoveryCandidate {
  transactionId: string;
  bookingId: number;
  reservationCode: string;
  transactionReference: string;
  status: "PENDING" | "CANCELLED" | "FAILED";
  purpose?: string;
  amount: number;
  createdAt?: string;
}

type RefundMethodContext = "CANCELLATION" | "CHECKOUT";
type SePayActionKind = "MATCH" | "RECOVER" | "REFUND" | "IGNORE";
type CancellationDecision = "approve_refund" | "approve_no_refund" | "reject";

type ConfirmAction =
  | { kind: "CONFIRM_RESERVATION"; reservation: ReservationItem }
  | { kind: "CANCELLATION_DECISION"; reservation: ReservationItem; decision: CancellationDecision }
  | { kind: "NO_SHOW"; reservation: ReservationItem }
  | { kind: "REFUND_PROVIDER"; refund: PendingRefund }
  | { kind: "CASH_REFUND"; refund: PendingRefund };

interface ManualRefundDetails {
  refundId: string;
  reservationId: number;
  reservationCode?: string;
  amount: number;
  expectedAmount: number;
  refundCode: string;
  status: PendingRefund["status"];
  canonicalStatus?: string;
  recipientId: string;
  recipientVersion: number;
  recipientStatus: RefundDestinationStatus | "REJECTED" | "SUPERSEDED";
  bankCode: string;
  bankName: string;
  accountNumber: string;
  accountHolderName: string;
  transferContent?: string;
  refundQrCodeUrl?: string;
  proofAssetId?: number;
  proofImageUrl?: string;
  proofContentType?: string;
  awaitingBankConfirmation?: boolean;
  fallbackAvailableAtUtc?: string;
  fallbackAvailable?: boolean;
  fallbackOpened?: boolean;
  fallbackOpenedBy?: string;
  fallbackReason?: string;
  completionMethod?: PendingRefund["completionMethod"];
  bankReferenceCode?: string;
}

interface PayoutConfig {
  payoutMode?: "MANUAL" | "AUTOMATIC";
  configured?: boolean;
  bankCode?: string;
  bankName?: string;
  accountName?: string;
  accountNumberMasked?: string;
  automaticTransferEnabled?: boolean;
}

interface CheckInGuestDraft {
  fullName: string;
  phone: string;
  email: string;
  idCardNumber: string;
  idCardType: "CCCD" | "CMND" | "PASSPORT";
  nationality: string;
  isPrimary: boolean;
}

interface CheckInRoomDraft {
  key: string;
  roomTypeId: number;
  roomTypeName: string;
  roomTypeNameEn?: string;
  roomId: string;
  guests: CheckInGuestDraft[];
}

interface PaymentUrlPayload {
  paymentUrl?: string;
  payment_url?: string;
  transactionId?: string;
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;

const toData = <T,>(response: { data?: unknown }): T => {
  const payload = response.data;
  return (isRecord(payload) && "data" in payload ? payload.data : payload) as T;
};

const getApiErrorMessage = (error: unknown, fallback: string) => {
  if (!isRecord(error) || !("response" in error) || !isRecord(error.response)) return fallback;
  const responseData = error.response.data;
  return isRecord(responseData) && typeof responseData.message === "string" ? responseData.message : fallback;
};

const formatVND = (value?: number) =>
  typeof value === "number"
    ? value.toLocaleString("vi-VN", { style: "currency", currency: "VND", maximumFractionDigits: 0 })
    : "-";

const formatDate = (value?: string) => {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("vi-VN", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
};

const formatAuditValue = (value: unknown): string => {
  if (value === null || value === undefined || value === "") return "—";
  if (typeof value === "boolean") return value ? "Có" : "Không";
  if (typeof value === "number") return value.toLocaleString("vi-VN");
  if (Array.isArray(value)) return value.map(formatAuditValue).join(", ");
  if (typeof value === "object") return Object.entries(value as Record<string, unknown>)
    .map(([key, item]) => `${key}: ${formatAuditValue(item)}`)
    .join(" · ");
  return String(value);
};

const formatDateTimeLocal = (date: Date) => {
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
};

const todayDate = () => formatDateTimeLocal(new Date());

const tomorrowDate = () => {
  const date = new Date();
  date.setDate(date.getDate() + 1);
  return formatDateTimeLocal(date);
};

const emptyGuest: CheckInGuestDraft = {
  fullName: "",
  phone: "",
  email: "",
  idCardNumber: "",
  idCardType: "CCCD",
  nationality: "Vietnam",
  isPrimary: true,
};

const reservationPriority: Record<ReservationStatus, number> = {
  CANCELLATION_PENDING: 0,
  CHECKED_IN: 1,
  CONFIRMED: 2,
  PAYMENT_PENDING: 3,
  DRAFT: 4,
  NO_SHOW: 5,
  CHECKED_OUT: 6,
  CANCELLED: 7,
};

const ACTION_LABELS: Record<string, string> = {
  CONFIRM: "Xác nhận đặt phòng", CHECK_IN: "Nhận phòng", CHECK_OUT: "Trả phòng",
  CANCEL: "Hủy đặt phòng", MARK_NO_SHOW: "Ghi nhận khách không đến",
  UPDATE_CHECKOUT_FEE: "Điều chỉnh phụ phí", REFUND: "Xử lý hoàn tiền",
  PAYMENT_RECEIVED: "Ghi nhận thanh toán", PAYMENT_SESSION_CREATED: "Tạo phiên thanh toán",
  ROOM_HOLD_RELEASED_MANUALLY: "Nhả giữ phòng thủ công",
  ROOM_HOLD_AUTO_EXPIRED: "Giữ phòng tự hết hạn",
  RESERVATION_AUTO_CANCELLED: "Hệ thống tự hủy reservation",
  CHECKOUT_RECONCILIATION_REQUESTED: "Yêu cầu ADMIN đối soát",
  CHECKOUT_RECONCILIATION_PASSED: "Đối soát checkout khớp",
  CHECKOUT_RECONCILIATION_OVERRIDDEN: "ADMIN duyệt điều chỉnh đối soát",
  PAYMENT_MARKED_PAID_MANUALLY: "ADMIN khôi phục payment từ event SePay",
  PROVIDER_EVENT_REVIEW_REQUIRED: "Event SePay cần đối soát",
  PRICE_OVERRIDDEN: "Điều chỉnh giá walk-in",
  PASSWORD_RESET_BY_ADMIN: "ADMIN đặt lại mật khẩu",
};

function RefundChannelSelector({
  value,
  onChange,
  localize,
}: {
  value: "MANUAL_BANK_TRANSFER" | "CASH_AT_COUNTER";
  onChange: (value: "MANUAL_BANK_TRANSFER" | "CASH_AT_COUNTER") => void;
  localize: (vi: string, en: string) => string;
}) {
  return (
    <fieldset>
      <legend className="text-sm font-semibold text-[#0F2A43]">{localize("Chọn kênh hoàn tiền", "Choose refund channel")}</legend>
      <div className="mt-3 grid gap-3 sm:grid-cols-2">
        <label className={`cursor-pointer rounded-xl border p-4 transition ${value === "CASH_AT_COUNTER" ? "border-emerald-500 bg-emerald-50 ring-1 ring-emerald-500/30" : "border-[#0F2A43]/10 bg-white"}`}>
          <input type="radio" name="refundChannel" checked={value === "CASH_AT_COUNTER"} onChange={() => onChange("CASH_AT_COUNTER")} className="sr-only" />
          <span className="block text-sm font-bold text-emerald-900">{localize("Tiền mặt tại quầy", "Cash at counter")}</span>
          <span className="mt-1 block text-xs leading-5 text-[#66727C]">{localize("Tạo khoản hoàn chờ xử lý; chỉ hoàn tất sau khi staff xác nhận đã giao tiền trực tiếp.", "Create a pending refund; complete it only after staff confirms the cash handover.")}</span>
        </label>
        <label className={`cursor-pointer rounded-xl border p-4 transition ${value === "MANUAL_BANK_TRANSFER" ? "border-sky-500 bg-sky-50 ring-1 ring-sky-500/30" : "border-[#0F2A43]/10 bg-white"}`}>
          <input type="radio" name="refundChannel" checked={value === "MANUAL_BANK_TRANSFER"} onChange={() => onChange("MANUAL_BANK_TRANSFER")} className="sr-only" />
          <span className="block text-sm font-bold text-sky-900">QR</span>
          <span className="mt-1 block text-xs leading-5 text-[#66727C]">{localize("Dùng tài khoản khách đã cung cấp, quét QR và chờ ngân hàng xác nhận tự động.", "Use the customer's saved bank account, scan the QR code, and wait for automatic bank confirmation.")}</span>
        </label>
      </div>
    </fieldset>
  );
}

export default function ReservationsManagement() {
  const { locale, localize } = useLanguage();
  const { isAdmin } = useDashboardRole();
  const [reservations, setReservations] = useState<ReservationItem[]>([]);
  const [availableRooms, setAvailableRooms] = useState<RoomItem[]>([]);
  const [selectedReservation, setSelectedReservation] = useState<ReservationItem | null>(null);
  const [finalPayment, setFinalPayment] = useState<FinalPayment | null>(null);
  const [checkInDrafts, setCheckInDrafts] = useState<CheckInRoomDraft[]>([]);
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedStatus, setSelectedStatus] = useState<"ALL" | ReservationStatus>("ALL");
  const [paymentFilter, setPaymentFilter] = useState<PaymentFilter>("ALL");
  const [stayDate, setStayDate] = useState("");
  const [isLoading, setIsLoading] = useState(true);
  const [isActionLoading, setIsActionLoading] = useState(false);
  const [toast, setToast] = useState<{ message: string; type: "success" | "error" | "info" } | null>(null);
  const [pendingFinalPaymentId, setPendingFinalPaymentId] = useState<number | null>(null);
  const [isWalkInOpen, setIsWalkInOpen] = useState(false);
  const [walkInRooms, setWalkInRooms] = useState<RoomItem[]>([]);
  const [selectedWalkInRoomIds, setSelectedWalkInRoomIds] = useState<number[]>([]);
  const [walkInGuestsByRoom, setWalkInGuestsByRoom] = useState<Record<number, CheckInGuestDraft[]>>({});
  const [walkInPriceOverrides, setWalkInPriceOverrides] = useState<Record<number, WalkInPriceOverrideDraft>>({});
  const [walkInNow, setWalkInNow] = useState(new Date());
  const [walkInPaymentMethod, setWalkInPaymentMethod] = useState<"NONE" | "CASH" | "SEPAY">("NONE");
  const [isRefundFeeOpen, setIsRefundFeeOpen] = useState(false);
  const [refundAdditionalFee, setRefundAdditionalFee] = useState("0");
  const [refundFeeReasonCode, setRefundFeeReasonCode] = useState("LATE_SERVICE_CHARGE");
  const [refundFeeReason, setRefundFeeReason] = useState("");
  const [refundFeeError, setRefundFeeError] = useState("");
  const [isCheckoutEscalationOpen, setIsCheckoutEscalationOpen] = useState(false);
  const [checkoutEscalationForm, setCheckoutEscalationForm] = useState({
    reasonCode: "PROVIDER_EVENT_UNMATCHED",
    note: "",
  });
  const [checkoutEscalationError, setCheckoutEscalationError] = useState("");
  const [pendingRefunds, setPendingRefunds] = useState<PendingRefund[]>([]);
  const [refundReservationTarget, setRefundReservationTarget] = useState<ReservationItem | null>(null);
  const [sePayReviewEvents, setSePayReviewEvents] = useState<SePayReviewEvent[]>([]);
  const [sePayRecoveryCandidates, setSePayRecoveryCandidates] = useState<SePayRecoveryCandidate[]>([]);
  const [isRecoveryCandidatesLoading, setIsRecoveryCandidatesLoading] = useState(false);
  const [sePayActionTarget, setSePayActionTarget] = useState<{ event: SePayReviewEvent; kind: SePayActionKind } | null>(null);
  const [sePayActionForm, setSePayActionForm] = useState({
    paymentTransactionId: "",
    providerOccurredAtUtc: "",
    note: "",
    reason: "",
    reasonCode: "PROVIDER_EVENT_RECOVERY",
    evidenceReference: "",
    refundChannel: "MANUAL_BANK_TRANSFER" as "MANUAL_BANK_TRANSFER" | "CASH_AT_COUNTER",
  });
  const [sePayActionError, setSePayActionError] = useState("");
  const [confirmAction, setConfirmAction] = useState<ConfirmAction | null>(null);
  const [cancelTarget, setCancelTarget] = useState<ReservationItem | null>(null);
  const [cancelReason, setCancelReason] = useState("");
  const [cancellationPenaltyAmount, setCancellationPenaltyAmount] = useState("0");
  const [cancellationPenaltyReasonCode, setCancellationPenaltyReasonCode] = useState("NO_PENALTY");
  const [cancellationPenaltyNote, setCancellationPenaltyNote] = useState("");
  const [cancellationPenaltyError, setCancellationPenaltyError] = useState("");
  const [roomHoldReleaseTarget, setRoomHoldReleaseTarget] = useState<ReservationItem | null>(null);
  const [roomHoldReleaseReasonCode, setRoomHoldReleaseReasonCode] = useState("CUSTOMER_ABANDONED_PAYMENT");
  const [roomHoldReleaseNote, setRoomHoldReleaseNote] = useState("");
  const [roomHoldReleaseError, setRoomHoldReleaseError] = useState("");
  const [refundMethodTarget, setRefundMethodTarget] = useState<{ reservation: ReservationItem; context: RefundMethodContext } | null>(null);
  const [refundChannel, setRefundChannel] = useState<"MANUAL_BANK_TRANSFER" | "CASH_AT_COUNTER">("MANUAL_BANK_TRANSFER");
  const [manualRefundTarget, setManualRefundTarget] = useState<PendingRefund | null>(null);
  const [manualRefundDetails, setManualRefundDetails] = useState<ManualRefundDetails | null>(null);
  const [payoutConfig, setPayoutConfig] = useState<PayoutConfig | null>(null);
  const [isManualDetailsLoading, setIsManualDetailsLoading] = useState(false);
  const [manualTransferredAt, setManualTransferredAt] = useState(() => formatDateTimeLocal(new Date()));
  const [manualTransferConfirmed, setManualTransferConfirmed] = useState(false);
  const [manualTransferError, setManualTransferError] = useState("");
  const [manualTransferProof, setManualTransferProof] = useState<UploadedImage | null>(null);
  const [manualFallbackReason, setManualFallbackReason] = useState("");
  const [isManualProofUploading, setIsManualProofUploading] = useState(false);
  const [manualQrFailed, setManualQrFailed] = useState(false);
  const [invoice, setInvoice] = useState<ReservationInvoice | null>(null);
  const [reservationAuditTarget, setReservationAuditTarget] = useState<ReservationItem | null>(null);
  const [reservationAuditLogs, setReservationAuditLogs] = useState<ReservationAuditItem[]>([]);
  const [reservationAuditError, setReservationAuditError] = useState("");
  const [isReservationAuditLoading, setIsReservationAuditLoading] = useState(false);

  const showToast = useCallback((message: string, type: "success" | "error" | "info" = "info") => {
    setToast({ message, type });
  }, []);

  const [walkInForm, setWalkInForm] = useState({
    customerProfileId: "",
    customerName: "",
    customerPhone: "",
    customerEmail: "",
    customerIdCard: "",
    customerIdCardType: "CCCD" as CheckInGuestDraft["idCardType"],
    guestCount: "1",
    checkIn: "",
    checkOut: "",
    note: "Walk-in booking",
  });

  const selectedWalkInRoomTypes = useMemo(() => {
    const grouped = new Map<number, { roomTypeId: number; name: string; nameEn?: string; basePrice?: number; quantity: number }>();
    walkInRooms.filter((room) => selectedWalkInRoomIds.includes(room.id)).forEach((room) => {
      const current = grouped.get(room.roomTypeId);
      if (current) current.quantity += 1;
      else grouped.set(room.roomTypeId, {
        roomTypeId: room.roomTypeId,
        name: room.roomTypeName,
        nameEn: room.roomTypeNameEn,
        basePrice: room.price,
        quantity: 1,
      });
    });
    return Array.from(grouped.values());
  }, [selectedWalkInRoomIds, walkInRooms]);

  const openWalkInModal = useCallback(async (preselectedRoomId?: number) => {
    const actualCheckIn = new Date();
    const defaultCheckOut = new Date(actualCheckIn);
    defaultCheckOut.setHours(defaultCheckOut.getHours() + 2);
    setIsWalkInOpen(true);
    setWalkInNow(actualCheckIn);
    setWalkInForm((current) => ({
      ...current,
      checkIn: formatDateTimeLocal(actualCheckIn),
      checkOut: formatDateTimeLocal(defaultCheckOut),
    }));
    setWalkInPaymentMethod("NONE");
    setSelectedWalkInRoomIds([]);
    setWalkInGuestsByRoom({});
    setWalkInPriceOverrides({});
    try {
      const res = await apiClient.get("/api/rooms/search?status=AVAILABLE&cleaningStatus=CLEAN");
      const data = toData<RoomItem[]>(res);
      const cleanRooms = Array.isArray(data) ? data : [];
      setWalkInRooms(cleanRooms);

      if (preselectedRoomId) {
        const requestedRoom = cleanRooms.find((room) => room.id === preselectedRoomId);
        if (requestedRoom) {
          setSelectedWalkInRoomIds([requestedRoom.id]);
          setWalkInGuestsByRoom({
            [requestedRoom.id]: [{
              ...emptyGuest,
            }],
          });
        } else {
          showToast("Phòng đã chọn không còn ở trạng thái sẵn sàng và sạch.", "error");
        }
      }
    } catch (error: unknown) {
      setWalkInRooms([]);
      showToast(getApiErrorMessage(error, "Không thể tải danh sách phòng trống sạch"), "error");
    }
  }, [showToast]);

  useEffect(() => {
    if (!isWalkInOpen) return;
    setWalkInNow(new Date());
    // datetime-local chỉ dùng độ chính xác đến phút; cập nhật mỗi 30 giây tránh
    // render lại toàn bộ màn hình reservation mỗi giây khi modal đang mở.
    const timer = window.setInterval(() => setWalkInNow(new Date()), 30_000);
    return () => window.clearInterval(timer);
  }, [isWalkInOpen]);

  const toggleWalkInRoom = (roomId: number) => {
    const selected = selectedWalkInRoomIds.includes(roomId);
    setSelectedWalkInRoomIds((current) => selected ? current.filter((id) => id !== roomId) : [...current, roomId]);
    setWalkInGuestsByRoom((current) => {
      if (selected) {
        const next = { ...current };
        delete next[roomId];
        return next;
      }
      const isFirstRoom = selectedWalkInRoomIds.length === 0;
      return {
        ...current,
        [roomId]: [{
          ...emptyGuest,
          fullName: isFirstRoom ? walkInForm.customerName : "",
          phone: isFirstRoom ? walkInForm.customerPhone : "",
          email: isFirstRoom ? walkInForm.customerEmail : "",
          idCardNumber: isFirstRoom ? walkInForm.customerIdCard : "",
          idCardType: isFirstRoom ? walkInForm.customerIdCardType : "CCCD",
        }],
      };
    });
  };

  const updateWalkInGuest = (roomId: number, guestIndex: number, patch: Partial<CheckInGuestDraft>) =>
    setWalkInGuestsByRoom((current) => ({
      ...current,
      [roomId]: (current[roomId] || []).map((guest, index) => index === guestIndex ? { ...guest, ...patch } : guest),
    }));

  const addWalkInGuest = (roomId: number) => setWalkInGuestsByRoom((current) => ({
    ...current,
    [roomId]: [...(current[roomId] || []), { ...emptyGuest, isPrimary: false }],
  }));

  const removeWalkInGuest = (roomId: number, guestIndex: number) => setWalkInGuestsByRoom((current) => ({
    ...current,
    [roomId]: (current[roomId] || []).filter((_, index) => index !== guestIndex),
  }));

  const closeReservationModal = () => {
    setSelectedReservation(null);
    setFinalPayment(null);
    setCheckInDrafts([]);
    setAvailableRooms([]);
  };

  const loadReservations = useCallback(async (showLoading = false) => {
    if (showLoading) setIsLoading(true);
    try {
      const res = await cachedGet("/api/reservations");
      const data = toData<ReservationItem[]>(res);
      setReservations(Array.isArray(data) ? data : []);
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể tải danh sách reservation"), "error");
      setReservations([]);
    } finally {
      setIsLoading(false);
    }
  }, [showToast]);

  const loadPendingRefunds = useCallback(async () => {
    try {
      const response = await cachedGet("/api/payments/refunds/pending");
      const data = toData<PendingRefund[]>(response);
      const nextRefunds = Array.isArray(data) ? data : [];
      setPendingRefunds(nextRefunds);
      return nextRefunds;
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể tải trạng thái hoàn tiền của các đơn"), "error");
      setPendingRefunds([]);
      return [];
    }
  }, [showToast]);

  const loadSePayReviewEvents = useCallback(async () => {
    try {
      const response = await cachedGet("/api/payments/sepay/events/review");
      const data = toData<SePayReviewEvent[]>(response);
      setSePayReviewEvents(Array.isArray(data) ? data : []);
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể tải hàng đợi đối soát SePay"), "error");
    }
  }, [showToast]);

  const loadSePayRecoveryCandidates = useCallback(async () => {
    if (!isAdmin) return;
    setIsRecoveryCandidatesLoading(true);
    try {
      const response = await apiClient.get("/api/payments/sepay/recovery-candidates");
      const data = toData<SePayRecoveryCandidate[]>(response);
      setSePayRecoveryCandidates(Array.isArray(data) ? data : []);
    } catch (error: unknown) {
      setSePayRecoveryCandidates([]);
      setSePayActionError(getApiErrorMessage(error, "Không thể tải danh sách payment có thể khôi phục"));
    } finally {
      setIsRecoveryCandidatesLoading(false);
    }
  }, [isAdmin]);

  const openSePayAction = (event: SePayReviewEvent, kind: SePayActionKind) => {
    setSePayActionTarget({ event, kind });
    setSePayActionForm({
      paymentTransactionId: "",
      providerOccurredAtUtc: "",
      note: "",
      reason: "",
      reasonCode: "PROVIDER_EVENT_RECOVERY",
      evidenceReference: "",
      refundChannel: "MANUAL_BANK_TRANSFER",
    });
    setSePayActionError("");
    if (kind === "RECOVER") void loadSePayRecoveryCandidates();
  };

  const closeSePayAction = () => {
    if (isActionLoading) return;
    setSePayActionTarget(null);
    setSePayActionError("");
  };

  const submitSePayAction = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!sePayActionTarget) return;
    const { event: reviewEvent, kind } = sePayActionTarget;
    const paymentTransactionId = sePayActionForm.paymentTransactionId.trim();
    const reason = sePayActionForm.reason.trim();
    let providerOccurredAtUtc: string | undefined;

    if (kind === "MATCH") {
      if (!paymentTransactionId) {
        setSePayActionError("Vui lòng nhập payment transaction ID nội bộ.");
        return;
      }
      if (!reviewEvent.providerOccurredAtUtc) {
        const parsed = new Date(sePayActionForm.providerOccurredAtUtc);
        if (!sePayActionForm.providerOccurredAtUtc || Number.isNaN(parsed.getTime())) {
          setSePayActionError("Thời điểm ngân hàng không hợp lệ.");
          return;
        }
        providerOccurredAtUtc = parsed.toISOString();
      }
    } else if (kind === "RECOVER") {
      if (!paymentTransactionId) {
        setSePayActionError("Vui lòng chọn payment cần khôi phục.");
        return;
      }
      if (!sePayActionForm.reasonCode.trim() || !sePayActionForm.note.trim()) {
        setSePayActionError("Mã lý do và ghi chú đối soát là bắt buộc.");
        return;
      }
    } else if (!reason) {
      setSePayActionError(kind === "IGNORE" ? "Vui lòng nhập lý do bỏ qua." : "Vui lòng nhập lý do tạo hoàn tiền.");
      return;
    }

    const action = kind.toLowerCase();
    const scope = `sepay-review:${reviewEvent.eventId}:${action}`;
    setIsActionLoading(true);
    setSePayActionError("");
    try {
      if (kind === "MATCH") {
        await apiClient.patch(`/api/payments/sepay/events/${reviewEvent.eventId}/match`, {
          paymentTransactionId,
          providerOccurredAtUtc,
          note: sePayActionForm.note.trim() || undefined,
        }, { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } });
        showToast("Đã ghép giao dịch SePay và cập nhật payment ledger", "success");
        await Promise.all([loadSePayReviewEvents(), loadReservations(), loadPendingRefunds()]);
      } else if (kind === "RECOVER") {
        await apiClient.patch(`/api/payments/sepay/events/${reviewEvent.eventId}/manual-reconcile`, {
          paymentTransactionId,
          reasonCode: sePayActionForm.reasonCode.trim(),
          note: sePayActionForm.note.trim(),
          ...(sePayActionForm.evidenceReference.trim()
            ? { evidenceReference: sePayActionForm.evidenceReference.trim() }
            : {}),
        }, { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } });
        showToast("Đã khôi phục payment bằng event SePay gốc và state machine chuẩn", "success");
        await Promise.all([loadSePayReviewEvents(), loadReservations(), loadPendingRefunds()]);
      } else if (kind === "IGNORE") {
        await apiClient.patch(`/api/payments/sepay/events/${reviewEvent.eventId}/ignore`, { reason }, {
          headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) },
        });
        showToast("Đã lưu quyết định bỏ qua và audit", "success");
        await loadSePayReviewEvents();
      } else {
        await apiClient.post(`/api/payments/sepay/events/${reviewEvent.eventId}/refund`, {
          refundChannel: sePayActionForm.refundChannel,
          reason,
        }, { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } });
        showToast("Đã tạo nghĩa vụ hoàn tiền từ giao dịch chưa ghép", "success");
        await Promise.all([loadSePayReviewEvents(), loadPendingRefunds()]);
      }
      clearIdempotencyKey(scope);
      setSePayActionTarget(null);
    } catch (error: unknown) {
      setSePayActionError(getApiErrorMessage(error, `Không thể xử lý giao dịch SePay (${action})`));
    } finally {
      setIsActionLoading(false);
    }
  };

  useEffect(() => {
    void loadReservations(true);
    loadPendingRefunds();
    loadSePayReviewEvents();
    const params = new URLSearchParams(window.location.search);
    const finalPaymentId = Number(params.get("finalPaymentId"));
    if (Number.isFinite(finalPaymentId) && finalPaymentId > 0) {
      setPendingFinalPaymentId(finalPaymentId);
    }
    const reservationId = params.get("reservationId");
    if (reservationId) {
      setSearchQuery(reservationId);
    }
    const walkInRoomId = Number(params.get("walkInRoomId"));
    if (Number.isFinite(walkInRoomId) && walkInRoomId > 0) {
      void openWalkInModal(walkInRoomId);
      params.delete("walkInRoomId");
      const nextQuery = params.toString();
      window.history.replaceState(null, "", `${window.location.pathname}${nextQuery ? `?${nextQuery}` : ""}`);
    }
    const reloadOperationalData = () => {
      void loadReservations();
      void loadPendingRefunds();
      void loadSePayReviewEvents();
    };
    window.addEventListener("focus", reloadOperationalData);
    return () => window.removeEventListener("focus", reloadOperationalData);
  }, [loadPendingRefunds, loadReservations, loadSePayReviewEvents, openWalkInModal]);

  const pendingRefundsByReservation = useMemo(() => {
    const grouped = new Map<number, PendingRefund[]>();
    pendingRefunds.forEach((refund) => {
      grouped.set(refund.bookingId, [...(grouped.get(refund.bookingId) || []), refund]);
    });
    return grouped;
  }, [pendingRefunds]);

  const unmatchedPendingRefunds = useMemo(() => {
    const reservationIds = new Set(reservations.map((reservation) => reservation.id));
    return pendingRefunds.filter((refund) => !reservationIds.has(refund.bookingId));
  }, [pendingRefunds, reservations]);

  const filteredReservations = useMemo(() => {
    const keyword = searchQuery.trim().toLowerCase();
    const selectedDayStart = stayDate ? new Date(`${stayDate}T00:00:00`).getTime() : null;
    const selectedDayEnd = selectedDayStart === null ? null : new Date(`${stayDate}T00:00:00`).setDate(new Date(`${stayDate}T00:00:00`).getDate() + 1);
    const matched = reservations.filter((reservation) => {
      const matchesStatus = selectedStatus === "ALL" || reservation.status === selectedStatus;
      const total = Number(reservation.plannedTotalAmount ?? reservation.totalAmount ?? 0);
      const paid = Number(reservation.paidAmount ?? 0);
      const matchesPayment = paymentFilter === "ALL"
        || (paymentFilter === "UNPAID" && paid <= 0)
        || (paymentFilter === "PARTIAL" && paid > 0 && paid < total)
        || (paymentFilter === "PAID" && paid >= total);
      const checkInTime = new Date(reservation.checkIn).getTime();
      const checkOutTime = new Date(reservation.checkOut).getTime();
      const matchesStayDate = selectedDayStart === null || selectedDayEnd === null
        || (checkInTime < selectedDayEnd && checkOutTime > selectedDayStart);
      const matchesSearch =
        !keyword ||
        reservation.reservationCode?.toLowerCase().includes(keyword) ||
        reservation.customerName?.toLowerCase().includes(keyword) ||
        String(reservation.id).includes(keyword);
      return matchesStatus && matchesPayment && matchesStayDate && matchesSearch;
    });
    return [...matched].sort((left, right) => {
      const refundPriority = Number(pendingRefundsByReservation.has(right.id)) - Number(pendingRefundsByReservation.has(left.id));
      if (refundPriority !== 0) return refundPriority;
      if (selectedStatus !== "ALL") {
        return new Date(left.checkIn).getTime() - new Date(right.checkIn).getTime();
      }

      const priorityDifference = reservationPriority[left.status] - reservationPriority[right.status];
      if (priorityDifference !== 0) return priorityDifference;

      const leftTime = new Date(
        left.status === "CHECKED_OUT" || left.status === "CANCELLED"
          ? left.actualCheckOut || left.checkOut
          : left.status === "CHECKED_IN"
            ? left.checkOut
            : left.checkIn,
      ).getTime();
      const rightTime = new Date(
        right.status === "CHECKED_OUT" || right.status === "CANCELLED"
          ? right.actualCheckOut || right.checkOut
          : right.status === "CHECKED_IN"
            ? right.checkOut
            : right.checkIn,
      ).getTime();

      if (left.status === "CHECKED_OUT" || left.status === "CANCELLED") {
        return rightTime - leftTime;
      }
      return leftTime - rightTime;
    });
  }, [paymentFilter, pendingRefundsByReservation, reservations, searchQuery, selectedStatus, stayDate]);

  const stats = useMemo(() => {
    return {
      total: reservations.length,
      draft: reservations.filter((item) => item.status === "DRAFT").length,
      confirmed: reservations.filter((item) => item.status === "CONFIRMED").length,
      checkedIn: reservations.filter((item) => item.status === "CHECKED_IN").length,
    };
  }, [reservations]);

  const openCheckIn = async (reservation: ReservationItem) => {
    setSelectedReservation(reservation);
    setFinalPayment(null);
    setIsActionLoading(true);
    try {
      const [detailRes, roomsRes] = await Promise.all([
        apiClient.get(`/api/reservations/${reservation.id}`),
        apiClient.get(`/api/rooms/available-for-reservation?reservationId=${reservation.id}`),
      ]);
      const detail = toData<ReservationItem>(detailRes);
      const rooms = toData<RoomItem[]>(roomsRes);
      setSelectedReservation(detail);
      setAvailableRooms(Array.isArray(rooms) ? rooms : []);

      const drafts: CheckInRoomDraft[] = [];
      (detail.roomTypes || []).forEach((roomType) => {
        const quantity = Math.max(1, Number(roomType.quantity || 1));
        for (let index = 0; index < quantity; index += 1) {
          drafts.push({
            key: `${roomType.roomTypeId}-${index}`,
            roomTypeId: roomType.roomTypeId,
            roomTypeName: roomType.roomTypeName,
            roomTypeNameEn: roomType.roomTypeNameEn,
            roomId: "",
            guests: [{ ...emptyGuest, fullName: index === 0 ? detail.customerName || "" : "" }],
          });
        }
      });
      setCheckInDrafts(drafts);
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể tải phòng trống cho reservation"), "error");
    } finally {
      setIsActionLoading(false);
    }
  };

  const updateDraft = (key: string, patch: Partial<CheckInRoomDraft>) => {
    setCheckInDrafts((prev) => prev.map((item) => (item.key === key ? { ...item, ...patch } : item)));
  };

  const updateGuestDraft = (key: string, guestIndex: number, patch: Partial<CheckInGuestDraft>) => {
    setCheckInDrafts((prev) =>
      prev.map((item) => item.key === key ? {
        ...item,
        guests: item.guests.map((guest, index) => index === guestIndex ? { ...guest, ...patch } : guest),
      } : item)
    );
  };

  const addGuestDraft = (key: string) => setCheckInDrafts((prev) => prev.map((item) =>
    item.key === key ? { ...item, guests: [...item.guests, { ...emptyGuest, isPrimary: false }] } : item
  ));

  const removeGuestDraft = (key: string, guestIndex: number) => setCheckInDrafts((prev) => prev.map((item) =>
    item.key === key ? { ...item, guests: item.guests.filter((_, index) => index !== guestIndex) } : item
  ));

  const handleConfirmReservation = async (reservation: ReservationItem) => {
    const operationScope = `reservation:${reservation.id}:CONFIRM`;
    setIsActionLoading(true);
    try {
      await apiClient.patch(
        `/api/reservations/confirm/${reservation.id}`,
        undefined,
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(operationScope) } },
      );
      clearIdempotencyKey(operationScope);
      showToast("Đã xác nhận reservation", "success");
      await loadReservations();
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể confirm reservation"), "error");
    } finally {
      setIsActionLoading(false);
    }
  };

  const handleCancellationDecision = async (reservation: ReservationItem, decision: CancellationDecision, confirmed = false) => {
    if (decision === "approve_refund" && !confirmed) {
      setRefundChannel("MANUAL_BANK_TRANSFER");
      setCancellationPenaltyAmount("0");
      setCancellationPenaltyReasonCode("NO_PENALTY");
      setCancellationPenaltyNote("");
      setCancellationPenaltyError("");
      setRefundMethodTarget({ reservation, context: "CANCELLATION" });
      return;
    }
    const isApprove = decision !== "reject";
    const refundPayment = decision === "approve_refund";
    const paidAmount = Math.max(0, reservation.paidAmount || 0);
    const penaltyAmount = refundPayment ? Number(cancellationPenaltyAmount) : paidAmount;
    if (refundPayment && (!/^\d+$/.test(cancellationPenaltyAmount) || !Number.isSafeInteger(penaltyAmount)
      || penaltyAmount < 0 || penaltyAmount > paidAmount)) {
      setCancellationPenaltyError(`Tiền phạt phải là số nguyên từ 0 đến ${formatVND(paidAmount)}.`);
      return;
    }
    if (refundPayment && penaltyAmount > 0
      && (!cancellationPenaltyReasonCode.trim() || !cancellationPenaltyNote.trim())) {
      setCancellationPenaltyError("Khoản phạt lớn hơn 0 bắt buộc có loại chính sách và căn cứ áp dụng.");
      return;
    }
    const cancellationRefundChannel = "MANUAL_BANK_TRANSFER" as const;
    const actionLabel = decision === "approve_refund" ? "xác nhận hủy và hoàn toàn bộ tiền đã trả" : decision === "approve_no_refund" ? "xác nhận hủy không hoàn tiền" : "từ chối yêu cầu hủy";
    if (!confirmed) {
      setConfirmAction({ kind: "CANCELLATION_DECISION", reservation, decision });
      return;
    }
    const operationScope = `reservation:${reservation.id}:CANCEL:${decision}:${penaltyAmount}:${refundPayment ? cancellationRefundChannel : "NONE"}`;
    setIsActionLoading(true);
    try {
      await apiClient.patch(
        `/api/reservations/cancel/${reservation.id}/${isApprove ? "approve" : "reject"}`,
        isApprove ? {
          refundPayment,
          cancellationPenaltyAmount: penaltyAmount,
          penaltyReasonCode: refundPayment ? cancellationPenaltyReasonCode.trim() : "FULL_FORFEITURE",
          ...(refundPayment && cancellationPenaltyNote.trim()
            ? { penaltyNote: cancellationPenaltyNote.trim() }
            : {}),
          ...(refundPayment ? { refundChannel: cancellationRefundChannel } : {}),
        } : undefined,
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(operationScope) } },
      );
      clearIdempotencyKey(operationScope);
      setRefundMethodTarget(null);
      await loadReservations();
      if (refundPayment) {
        const state = await openReadyManualRefundForReservation(reservation.id);
        showToast(state === "AWAITING_CUSTOMER_INFO"
          ? "Đã gửi yêu cầu khách nhập tài khoản nhận tiền. Reservation chưa bị hủy; staff chỉ tiếp tục được sau khi khách gửi đủ thông tin."
          : state === "PENDING_BANK_CONFIRMATION"
            ? "Đã nhận thông tin tài khoản; mở QR và chờ ngân hàng xác nhận tự động."
            : "Đã tạo nghĩa vụ hoàn QR; reservation chưa bị chốt hủy.", "success");
      } else {
        await loadPendingRefunds();
        showToast(isApprove ? (refundPayment
          ? "Đã hủy và tạo yêu cầu hoàn theo kênh đã chọn"
          : "Đã hủy không hoàn tiền") : "Đã từ chối yêu cầu hủy", "success");
      }
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, `Không thể ${actionLabel}`), "error");
    } finally {
      setIsActionLoading(false);
    }
  };

  const openStaffCancel = (reservation: ReservationItem) => {
    setCancelTarget(reservation);
    setCancelReason("");
    setCancellationPenaltyAmount("0");
    setCancellationPenaltyReasonCode("NO_PENALTY");
    setCancellationPenaltyNote("");
    setCancellationPenaltyError("");
    setRefundChannel("MANUAL_BANK_TRANSFER");
  };

  const handleStaffCancel = async () => {
    if (!cancelTarget || !cancelReason.trim()) return;
    const paidAmount = Math.max(0, cancelTarget.paidAmount || 0);
    const penaltyAmount = Number(cancellationPenaltyAmount);
    if (!/^\d+$/.test(cancellationPenaltyAmount) || !Number.isSafeInteger(penaltyAmount)
      || penaltyAmount < 0 || penaltyAmount > paidAmount) {
      setCancellationPenaltyError(`Tiền phạt phải là số nguyên từ 0 đến ${formatVND(paidAmount)}.`);
      return;
    }
    if (penaltyAmount > 0 && (!cancellationPenaltyReasonCode.trim() || !cancellationPenaltyNote.trim())) {
      setCancellationPenaltyError("Khoản phạt lớn hơn 0 bắt buộc có loại chính sách và căn cứ áp dụng.");
      return;
    }
    const refundAmount = paidAmount - penaltyAmount;
    const refundPayment = refundAmount > 0;
    const operationScope = `reservation:${cancelTarget.id}:CANCEL_STAFF:${penaltyAmount}:${refundPayment ? refundChannel : "NONE"}`;
    setIsActionLoading(true);
    try {
      await apiClient.patch(`/api/reservations/cancel/${cancelTarget.id}/staff`, {
        cancellationReason: cancelReason.trim(),
        refundPayment,
        cancellationPenaltyAmount: penaltyAmount,
        penaltyReasonCode: penaltyAmount > 0 ? cancellationPenaltyReasonCode.trim() : "NO_PENALTY",
        ...(cancellationPenaltyNote.trim() ? { penaltyNote: cancellationPenaltyNote.trim() } : {}),
        ...(refundPayment ? { refundChannel } : {}),
      }, {
        headers: { "Idempotency-Key": getOrCreateIdempotencyKey(operationScope) },
      });
      clearIdempotencyKey(operationScope);
      const reservationId = cancelTarget.id;
      setCancelTarget(null);
      await loadReservations();
      if (refundPayment && refundChannel === "MANUAL_BANK_TRANSFER") {
        const state = await openReadyManualRefundForReservation(reservationId);
        showToast(state === "AWAITING_CUSTOMER_INFO"
          ? "Đã gửi yêu cầu khách nhập tài khoản nhận tiền. Đơn giữ nguyên trạng thái và staff chưa thể mở QR hoặc hoàn tiền."
          : state === "PENDING_BANK_CONFIRMATION"
            ? "Khách đã có thông tin tài khoản hợp lệ; mở QR và chờ ngân hàng xác nhận tự động."
            : "Đã tạo nghĩa vụ hoàn QR; đơn chưa bị chốt hủy.", "success");
      } else if (refundPayment && refundChannel === "CASH_AT_COUNTER") {
        const opened = await openPendingCashRefundForReservation(reservationId);
        showToast(opened
          ? "Đã tạo khoản hoàn tiền mặt đang chờ xác nhận. Đơn giữ nguyên trạng thái cho đến khi staff thực sự giao tiền."
          : "Đã tạo nghĩa vụ hoàn tiền mặt; đơn chưa bị chốt hủy.", "success");
      } else {
        await loadPendingRefunds();
        showToast(refundPayment
          ? "Đã hủy và tạo yêu cầu hoàn theo kênh đã chọn"
          : "Đã hủy không hoàn tiền", "success");
      }
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể hủy reservation"), "error");
    } finally {
      setIsActionLoading(false);
    }
  };

  const openRoomHoldRelease = (reservation: ReservationItem) => {
    setRoomHoldReleaseTarget(reservation);
    setRoomHoldReleaseReasonCode("CUSTOMER_ABANDONED_PAYMENT");
    setRoomHoldReleaseNote("");
    setRoomHoldReleaseError("");
  };

  const submitRoomHoldRelease = async () => {
    if (!roomHoldReleaseTarget) return;
    if (!roomHoldReleaseReasonCode.trim() || !roomHoldReleaseNote.trim()) {
      setRoomHoldReleaseError("Mã lý do và ghi chú là bắt buộc.");
      return;
    }
    const operationScope = `reservation:${roomHoldReleaseTarget.id}:ROOM_HOLD_RELEASE`;
    setIsActionLoading(true);
    setRoomHoldReleaseError("");
    try {
      await apiClient.post(`/api/reservations/${roomHoldReleaseTarget.id}/room-holds/release`, {
        reasonCode: roomHoldReleaseReasonCode.trim(),
        note: roomHoldReleaseNote.trim(),
      }, { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(operationScope) } });
      clearIdempotencyKey(operationScope);
      setRoomHoldReleaseTarget(null);
      await loadReservations();
      showToast("Đã nhả RoomHold và ghi lại người thao tác cùng lý do", "success");
    } catch (error: unknown) {
      setRoomHoldReleaseError(getApiErrorMessage(error, "Không thể nhả RoomHold"));
    } finally {
      setIsActionLoading(false);
    }
  };

  const handleMarkNoShow = async (reservation: ReservationItem) => {
    const operationScope = `reservation:${reservation.id}:NO_SHOW`;
    setIsActionLoading(true);
    try {
      await apiClient.patch(
        `/api/reservations/no-show/${reservation.id}`,
        undefined,
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(operationScope) } },
      );
      clearIdempotencyKey(operationScope);
      showToast("Đã đánh dấu khách không đến", "success");
      await loadReservations();
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể đánh dấu khách không đến"), "error");
    } finally {
      setIsActionLoading(false);
    }
  };

  const openInvoice = async (reservation: ReservationItem) => {
    setIsActionLoading(true);
    try {
      const response = await apiClient.get(`/api/reservations/${reservation.id}/invoice`);
      setInvoice(toData<ReservationInvoice>(response));
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể tải hóa đơn reservation"), "error");
    } finally {
      setIsActionLoading(false);
    }
  };

  const handleSubmitCheckIn = async () => {
    if (!selectedReservation) return;
    const invalid = checkInDrafts.some((draft) => !draft.roomId || draft.guests.length === 0
      || draft.guests.some((guest) => {
        const phoneDigits = guest.phone.replace(/[\s().+-]/g, "");
        return guest.fullName.trim().length < 2
          || guest.fullName.trim().length > 100
          || (Boolean(guest.phone.trim()) && !/^\d{8,15}$/.test(phoneDigits))
          || (Boolean(guest.email.trim()) && (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(guest.email.trim()) || guest.email.trim().length > 254))
          || guest.idCardNumber.trim().length > 50;
      })
      || draft.guests.filter((guest) => guest.isPrimary).length !== 1);
    if (invalid) {
      showToast("Mỗi phòng cần có khách lưu trú và đúng một khách đại diện", "error");
      return;
    }
    const assignedRoomIds = checkInDrafts.map((draft) => draft.roomId);
    if (new Set(assignedRoomIds).size !== assignedRoomIds.length) {
      showToast(localize("Mỗi phòng thực tế chỉ được gán một lần trong lượt nhận phòng.", "Each physical room can only be assigned once during check-in."), "error");
      return;
    }
    const totalGuests = checkInDrafts.reduce((total, draft) => total + draft.guests.length, 0);
    if (totalGuests !== selectedReservation.guestCount) {
      showToast(`Reservation yêu cầu ${selectedReservation.guestCount} khách, hiện đã nhập ${totalGuests}`, "error");
      return;
    }

    const operationScope = `reservation:${selectedReservation.id}:CHECKIN:${checkInDrafts.map((draft) => draft.roomId).join("-")}`;
    setIsActionLoading(true);
    try {
      await apiClient.patch(
        `/api/reservations/check-in/${selectedReservation.id}`,
        checkInDrafts.map((draft) => ({
          roomId: Number(draft.roomId),
          guests: draft.guests.map((guest) => ({
            ...guest,
            email: guest.email || undefined,
            idCardNumber: guest.idCardNumber || undefined,
          })),
        })),
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(operationScope) } },
      );
      clearIdempotencyKey(operationScope);
      showToast("Check-in thành công", "success");
      setSelectedReservation(null);
      setCheckInDrafts([]);
      setAvailableRooms([]);
      await loadReservations();
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể check-in reservation"), "error");
    } finally {
      setIsActionLoading(false);
    }
  };

  const openFinalPayment = useCallback(async (reservation: ReservationItem) => {
    setSelectedReservation(reservation);
    setCheckInDrafts([]);
    setAvailableRooms([]);
    setIsActionLoading(true);
    try {
      const [reconciliationRes, detailRes] = await Promise.all([
        apiClient.get(`/api/reservations/${reservation.id}/checkout-reconciliation`),
        apiClient.get(`/api/reservations/${reservation.id}`),
      ]);
      const reconciliation = toData<CheckoutReconciliationApi>(reconciliationRes);
      const earlyAdjustment = reconciliation.earlyCheckoutAdjustment || 0;
      const lateFee = reconciliation.lateCheckoutFee || 0;
      const additionalFee = reconciliation.checkoutAdditionalFee || 0;
      const roomCharge = Math.max(0, reconciliation.requiredAmount - lateFee - additionalFee);
      setSelectedReservation(toData<ReservationItem>(detailRes));
      setFinalPayment({
        reservationId: reconciliation.reservationId,
        totalAmount: reconciliation.requiredAmount,
        roomCharge,
        plannedRoomCharge: roomCharge + earlyAdjustment,
        paidAmount: reconciliation.acceptedAmount,
        remainingAmount: reconciliation.outstandingAmount,
        lateCheckoutFee: lateFee,
        earlyCheckoutAdjustment: earlyAdjustment,
        checkoutAdditionalFee: additionalFee,
        refundableAmount: reconciliation.uncoveredRefundAmount,
        fullyPaid: reconciliation.status === "MATCHED",
        reconciliationStatus: reconciliation.status,
        blockingReasons: reconciliation.blockingReasons || [],
        reservedRefundAmount: reconciliation.reservedRefundAmount || 0,
        uncoveredRefundAmount: reconciliation.uncoveredRefundAmount || 0,
        paymentPending: reconciliation.paymentPending,
        refundPending: reconciliation.refundPending,
      });
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể tải đối soát checkout"), "error");
    } finally {
      setIsActionLoading(false);
    }
  }, [showToast]);

  const openReservationAudit = async (reservation: ReservationItem) => {
    if (!isAdmin) return;
    setReservationAuditTarget(reservation);
    setReservationAuditLogs([]);
    setReservationAuditError("");
    setIsReservationAuditLoading(true);
    try {
      const response = await apiClient.get(`/api/reservations/${reservation.id}/audit-logs`);
      setReservationAuditLogs(toData<ReservationAuditItem[]>(response) || []);
    } catch (error: unknown) {
      setReservationAuditError(getApiErrorMessage(error, "Không thể tải lịch sử hoạt động"));
    } finally {
      setIsReservationAuditLoading(false);
    }
  };

  const handleCashPayment = async () => {
    if (!selectedReservation) return;
    const operationScope = `payment:${selectedReservation.id}:CASH_FINAL`;
    setIsActionLoading(true);
    try {
      await apiClient.post("/api/payments/cash", {
        bookingId: selectedReservation.id,
      }, {
        headers: {
          "Idempotency-Key": getOrCreateIdempotencyKey(operationScope),
        },
      });
      clearIdempotencyKey(operationScope);
      showToast("Đã ghi nhận thanh toán tiền mặt", "success");
      await openFinalPayment(selectedReservation);
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể ghi nhận tiền mặt"), "error");
    } finally {
      setIsActionLoading(false);
    }
  };

  const handleOnlinePayment = async () => {
    if (!selectedReservation) return;
    setIsActionLoading(true);
    try {
      const res = await apiClient.post("/api/payments/create", {
        bookingId: selectedReservation.id,
        provider: "SEPAY",
        purpose: "FINAL_PAYMENT",
        orderInfo: `Thanh toan phan con lai reservation ${selectedReservation.reservationCode || selectedReservation.id}`,
      }, {
        headers: {
          "Idempotency-Key": getOrCreateIdempotencyKey(
            `payment:${selectedReservation.id}:FINAL_PAYMENT`,
          ),
        },
      });
      const paymentPayload = toData<PaymentUrlPayload>(res);
      const paymentUrl = paymentPayload?.paymentUrl || paymentPayload?.payment_url
        || (paymentPayload?.transactionId
          ? `/booking/payment-result?transactionId=${encodeURIComponent(paymentPayload.transactionId)}`
          : "");
      if (paymentUrl) {
        sessionStorage.setItem("staff_final_payment_reservation_id", String(selectedReservation.id));
        window.location.href = paymentUrl;
        return;
      }
      showToast("Backend không trả về trang thanh toán SePay VietQR", "error");
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể tạo thanh toán SePay VietQR"), "error");
    } finally {
      setIsActionLoading(false);
    }
  };

  const handleCheckOut = async () => {
    if (!selectedReservation) return;
    const operationScope = `reservation:${selectedReservation.id}:CHECKOUT`;
    setIsActionLoading(true);
    try {
      await apiClient.patch(
        `/api/reservations/check-out/${selectedReservation.id}`,
        undefined,
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(operationScope) } },
      );
      clearIdempotencyKey(operationScope);
      showToast("Check-out thành công", "success");
      setSelectedReservation(null);
      setFinalPayment(null);
      await loadReservations();
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể check-out reservation"), "error");
    } finally {
      setIsActionLoading(false);
    }
  };

  const openCheckoutAdditionalFee = () => {
    setRefundAdditionalFee(String(finalPayment?.checkoutAdditionalFee || 0));
    setRefundFeeReasonCode("LATE_SERVICE_CHARGE");
    setRefundFeeReason("");
    setRefundFeeError("");
    setIsRefundFeeOpen(true);
  };

  const handleCheckoutAdditionalFee = async () => {
    if (!selectedReservation || !finalPayment) return;
    const additionalFee = Number(refundAdditionalFee.replace(/[^0-9]/g, ""));
    if (!Number.isSafeInteger(additionalFee) || additionalFee < 0) {
      setRefundFeeError("Phụ phí phải là số nguyên không âm.");
      return;
    }
    if (!refundFeeReasonCode.trim() || !refundFeeReason.trim()) {
      setRefundFeeError("Vui lòng chọn mã lý do và ghi chú điều chỉnh phụ phí.");
      return;
    }
    const operationScope = `reservation:${selectedReservation.id}:CHECKOUT_FEE:${additionalFee}`;
    setIsActionLoading(true);
    try {
      await apiClient.patch(
        `/api/reservations/check-out/${selectedReservation.id}/additional-fee`,
        {
          additionalFee,
          reasonCode: refundFeeReasonCode.trim(),
          reason: refundFeeReason.trim(),
        },
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(operationScope) } },
      );
      clearIdempotencyKey(operationScope);
      showToast("Đã cập nhật phụ phí checkout", "success");
      setIsRefundFeeOpen(false);
      await loadReservations();
      await openFinalPayment(selectedReservation);
    } catch (error: unknown) {
      setRefundFeeError(getApiErrorMessage(error, "Không thể cập nhật phụ phí"));
    } finally {
      setIsActionLoading(false);
    }
  };

  const openCheckoutEscalation = () => {
    setCheckoutEscalationForm({ reasonCode: "PROVIDER_EVENT_UNMATCHED", note: "" });
    setCheckoutEscalationError("");
    setIsCheckoutEscalationOpen(true);
  };

  const submitCheckoutEscalation = async () => {
    if (!selectedReservation || !checkoutEscalationForm.note.trim()) {
      setCheckoutEscalationError("Vui lòng mô tả lỗi đã kiểm tra và chưa thể xử lý bằng nghiệp vụ bình thường.");
      return;
    }
    const scope = `reservation:${selectedReservation.id}:CHECKOUT_RECONCILIATION_REQUEST`;
    setIsActionLoading(true);
    setCheckoutEscalationError("");
    try {
      await apiClient.post(
        `/api/reservations/${selectedReservation.id}/checkout-reconciliation-requests`,
        {
          reasonCode: checkoutEscalationForm.reasonCode,
          note: checkoutEscalationForm.note.trim(),
        },
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } },
      );
      clearIdempotencyKey(scope);
      setIsCheckoutEscalationOpen(false);
      showToast("Đã gửi snapshot đối soát lệch cho ADMIN. Reservation chưa được checkout.", "success");
    } catch (error: unknown) {
      setCheckoutEscalationError(getApiErrorMessage(error, "Không thể gửi yêu cầu ADMIN đối soát"));
    } finally {
      setIsActionLoading(false);
    }
  };

  const handleCheckoutRefund = () => {
    if (!selectedReservation || !finalPayment || (finalPayment.refundableAmount || 0) <= 0) return;
    setRefundChannel("MANUAL_BANK_TRANSFER");
    setRefundMethodTarget({ reservation: selectedReservation, context: "CHECKOUT" });
  };

  const executeCheckoutRefund = async () => {
    if (!selectedReservation || !finalPayment || (finalPayment.refundableAmount || 0) <= 0) return;
    const operationScope = `reservation:${selectedReservation.id}:CHECKOUT_REFUND:${refundChannel}`;
    setIsActionLoading(true);
    try {
      await apiClient.patch(
        `/api/reservations/check-out/${selectedReservation.id}/refund`,
        { refundChannel },
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(operationScope) } },
      );
      clearIdempotencyKey(operationScope);
      setRefundMethodTarget(null);
      await loadReservations();
      if (refundChannel === "MANUAL_BANK_TRANSFER") {
        await openReadyManualRefundForReservation(selectedReservation.id);
        showToast("Đã tạo yêu cầu hoàn QR; hệ thống sẽ chờ ngân hàng xác nhận tự động.", "success");
      } else {
        const opened = await openPendingCashRefundForReservation(selectedReservation.id);
        showToast(opened
          ? "Đã tạo khoản hoàn tiền mặt đang chờ xác nhận đã giao tiền."
          : "Đã tạo nghĩa vụ hoàn tiền mặt.", "success");
      }
      await openFinalPayment(selectedReservation);
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể tạo yêu cầu hoàn tiền"), "error");
    } finally {
      setIsActionLoading(false);
    }
  };

  const confirmRefundMethod = async () => {
    if (!refundMethodTarget) return;
    if (refundMethodTarget.context === "CANCELLATION") {
      await handleCancellationDecision(refundMethodTarget.reservation, "approve_refund", true);
      return;
    }
    await executeCheckoutRefund();
  };

  const handleRefundProviderAction = async (refund: PendingRefund) => {
    const isVnpayOriginal = refund.refundChannel === "VNPAY_ORIGINAL"
      || (!refund.refundChannel && refund.refundProvider === "VNPAY");
    if (!isVnpayOriginal) return;
    const action = refund.canRetry ? "gửi lại" : "đối soát";
    const operationScope = `refund:${refund.refundId}:${refund.canRetry ? "RETRY" : "RECONCILE"}`;
    setIsActionLoading(true);
    try {
      const response = await apiClient.patch(
        `/api/payments/refund/${refund.refundId}/${refund.canRetry ? "retry" : "reconcile"}`,
        undefined,
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(operationScope) } },
      );
      clearIdempotencyKey(operationScope);
      const updated = toData<PendingRefund>(response);
      const toastType = updated.status === "SUCCEEDED"
        ? "success"
        : updated.status === "FAILED" || updated.status === "MANUAL_REVIEW"
          ? "error"
          : "info";
      showToast(updated.message || (refund.canRetry ? "Đã xử lý yêu cầu gửi lại refund" : "Đã đối soát trạng thái refund lịch sử"), toastType);
      await Promise.all([loadPendingRefunds(), loadReservations()]);
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, `Không thể ${action} refund lịch sử`), "error");
    } finally {
      setIsActionLoading(false);
    }
  };

  const completeCashRefund = async (refund: PendingRefund) => {
    const operationScope = `refund:${refund.refundId}:CASH_COMPLETE`;
    setIsActionLoading(true);
    try {
      await apiClient.patch(`/api/payments/refund/${refund.refundId}/cash-complete`, {
        confirmed: true,
      }, {
        headers: { "Idempotency-Key": getOrCreateIdempotencyKey(operationScope) },
      });
      clearIdempotencyKey(operationScope);
      showToast("Đã xác nhận hoàn tiền mặt tại quầy", "success");
      await Promise.all([loadPendingRefunds(), loadReservations()]);
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể xác nhận hoàn tiền mặt"), "error");
    } finally {
      setIsActionLoading(false);
    }
  };

  const executeConfirmAction = async () => {
    if (!confirmAction) return;
    const action = confirmAction;
    if (action.kind === "CONFIRM_RESERVATION") {
      await handleConfirmReservation(action.reservation);
    } else if (action.kind === "CANCELLATION_DECISION") {
      await handleCancellationDecision(action.reservation, action.decision, true);
    } else if (action.kind === "NO_SHOW") {
      await handleMarkNoShow(action.reservation);
    } else if (action.kind === "REFUND_PROVIDER") {
      await handleRefundProviderAction(action.refund);
    } else {
      await completeCashRefund(action.refund);
    }
    setConfirmAction(null);
  };

  const closeManualRefundDetails = () => {
    setManualRefundTarget(null);
    setManualRefundDetails(null);
    setPayoutConfig(null);
    setManualTransferredAt(formatDateTimeLocal(new Date()));
    setManualTransferConfirmed(false);
    setManualTransferError("");
    setManualTransferProof(null);
    setManualFallbackReason("");
    setIsManualProofUploading(false);
    setManualQrFailed(false);
  };

  const openManualRefundDetails = async (refund: PendingRefund) => {
    if (refund.refundChannel !== "MANUAL_BANK_TRANSFER") return;
    setManualRefundTarget(refund);
    setManualRefundDetails(null);
    setPayoutConfig(null);
    setManualTransferredAt(formatDateTimeLocal(new Date()));
    setManualTransferConfirmed(false);
    setManualTransferError("");
    setManualTransferProof(null);
    setManualFallbackReason("");
    setIsManualProofUploading(false);
    setManualQrFailed(false);
    setIsManualDetailsLoading(true);
    try {
      const [detailsResponse, configResponse] = await Promise.all([
        apiClient.get(`/api/payments/refund/${refund.refundId}/manual-details`),
        apiClient.get("/api/payments/refunds/payout-config"),
      ]);
      const details = toData<ManualRefundDetails>(detailsResponse);
      setManualRefundDetails(details);
      setManualFallbackReason(details.fallbackReason || "");
      setPayoutConfig(toData<PayoutConfig>(configResponse));
      if (details.proofAssetId && details.proofImageUrl) {
        setManualTransferProof({
          assetId: details.proofAssetId,
          url: details.proofImageUrl,
          contentType: details.proofContentType,
        });
      }
    } catch (error: unknown) {
      setManualTransferError(getApiErrorMessage(error, "Không thể tải chi tiết người nhận hoặc tài khoản chi hoàn"));
    } finally {
      setIsManualDetailsLoading(false);
    }
  };

  const openReadyManualRefundForReservation = async (reservationId: number) => {
    const refunds = await loadPendingRefunds();
    const manualRefund = refunds.find((refund) => refund.bookingId === reservationId
      && refund.refundChannel === "MANUAL_BANK_TRANSFER"
      && ["AWAITING_CUSTOMER_INFO", "READY_FOR_MANUAL_TRANSFER", "REQUESTED", "PROCESSING"].includes(refund.status));
    const readyRefund = manualRefund && manualRefund.status !== "AWAITING_CUSTOMER_INFO" ? manualRefund : null;
    if (readyRefund) {
      await openManualRefundDetails(readyRefund);
      return "PENDING_BANK_CONFIRMATION" as const;
    }
    return manualRefund?.status === "AWAITING_CUSTOMER_INFO"
      ? "AWAITING_CUSTOMER_INFO" as const
      : "NOT_FOUND" as const;
  };

  useEffect(() => {
    if (!manualRefundTarget || !manualRefundDetails?.awaitingBankConfirmation) return;
    const refundId = manualRefundTarget.refundId;
    const timer = window.setInterval(async () => {
      try {
        const response = await apiClient.get(`/api/payments/refund/${refundId}/manual-details`);
        const details = toData<ManualRefundDetails>(response);
        if (details.status === "SUCCEEDED") {
          closeManualRefundDetails();
          showToast("Ngân hàng đã xác nhận giao dịch hoàn tiền thành công.", "success");
          await Promise.all([loadPendingRefunds(), loadReservations()]);
          return;
        }
        setManualRefundDetails(details);
      } catch {
        // Polling is best-effort; the explicit refresh/open action still reports errors.
      }
    }, 5_000);
    return () => window.clearInterval(timer);
  }, [manualRefundDetails?.awaitingBankConfirmation, manualRefundTarget, loadPendingRefunds, loadReservations, showToast]);

  const openPendingCashRefundForReservation = async (reservationId: number) => {
    const refunds = await loadPendingRefunds();
    const cashRefund = refunds.find((refund) => refund.bookingId === reservationId
      && refund.refundChannel === "CASH_AT_COUNTER"
      && refund.status === "REQUESTED"
      && refund.canCompleteCash !== false);
    if (!cashRefund) return false;
    setConfirmAction({ kind: "CASH_REFUND", refund: cashRefund });
    return true;
  };

  const completeManualRefund = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!manualRefundTarget || !manualRefundDetails) return;

    const transferredAt = new Date(manualTransferredAt);
    if (!manualTransferredAt || Number.isNaN(transferredAt.getTime())) {
      setManualTransferError("Thời điểm chuyển khoản không hợp lệ.");
      return;
    }
    if (transferredAt.getTime() > Date.now() + 5 * 60_000) {
      setManualTransferError("Thời điểm chuyển khoản không được nằm trong tương lai.");
      return;
    }
    if (!payoutConfig?.configured) {
      setManualTransferError("Chưa cấu hình tài khoản ngân hàng chi hoàn trong môi trường chạy thực tế.");
      return;
    }
    if (!manualRefundDetails.fallbackAvailable) {
      setManualTransferError("Hệ thống vẫn đang chờ xác nhận tự động từ ngân hàng. Admin có thể mở fallback nếu cần xử lý sớm.");
      return;
    }
    if (!manualTransferConfirmed) {
      setManualTransferError("Vui lòng xác nhận đã đối chiếu đúng người nhận và giao dịch ngân hàng.");
      return;
    }
    if (!manualFallbackReason.trim()) {
      setManualTransferError("Vui lòng chọn lý do xác nhận thủ công.");
      return;
    }

    const operationScope = `refund:${manualRefundTarget.refundId}:BANK_COMPLETE`;
    setIsActionLoading(true);
    setManualTransferError("");
    try {
      await apiClient.patch(`/api/payments/refund/${manualRefundTarget.refundId}/manual-complete`, {
        recipientId: manualRefundDetails.recipientId,
        recipientVersion: manualRefundDetails.recipientVersion,
        transferredAt: manualTransferredAt.length === 16 ? `${manualTransferredAt}:00` : manualTransferredAt,
        ...(manualTransferProof?.assetId ? { proofAssetId: manualTransferProof.assetId } : {}),
        fallbackReason: manualFallbackReason.trim(),
      }, {
        headers: { "Idempotency-Key": getOrCreateIdempotencyKey(operationScope) },
      });
      clearIdempotencyKey(operationScope);
      showToast("Đã hoàn tất refund bằng xác nhận thủ công dự phòng", "success");
      closeManualRefundDetails();
      await Promise.all([loadPendingRefunds(), loadReservations()]);
    } catch (error: unknown) {
      if (getApiErrorStatus(error) === 409) {
        setManualRefundDetails(null);
        setPayoutConfig(null);
        setManualTransferredAt(formatDateTimeLocal(new Date()));
        setManualTransferConfirmed(false);
        setManualTransferProof(null);
        setIsManualDetailsLoading(true);
        try {
          const [detailsResponse, configResponse] = await Promise.all([
            apiClient.get(`/api/payments/refund/${manualRefundTarget.refundId}/manual-details`),
            apiClient.get("/api/payments/refunds/payout-config"),
          ]);
          const details = toData<ManualRefundDetails>(detailsResponse);
          setManualRefundDetails(details);
          setManualFallbackReason(details.fallbackReason || "");
          setPayoutConfig(toData<PayoutConfig>(configResponse));
          if (details.proofAssetId && details.proofImageUrl) {
            setManualTransferProof({
              assetId: details.proofAssetId,
              url: details.proofImageUrl,
              contentType: details.proofContentType,
            });
          }
          setManualTransferError("Thông tin người nhận đã thay đổi. Dữ liệu mới đã được tải lại; vui lòng đối chiếu trước khi xác nhận.");
        } catch (reloadError: unknown) {
          setManualTransferError(getApiErrorMessage(reloadError, "Thông tin người nhận đã thay đổi và không thể tải lại. Vui lòng đóng rồi mở lại yêu cầu hoàn tiền."));
        } finally {
          setIsManualDetailsLoading(false);
        }
        return;
      }
      setManualTransferError(getApiErrorMessage(error, "Không thể xác nhận giao dịch chuyển khoản"));
    } finally {
      setIsActionLoading(false);
    }
  };

  const openManualFallbackEarly = async () => {
    if (!manualRefundTarget || !manualRefundDetails || !isAdmin) return;
    if (!manualFallbackReason.trim()) {
      setManualTransferError("Vui lòng chọn lý do trước khi mở fallback thủ công.");
      return;
    }
    const operationScope = `refund:${manualRefundTarget.refundId}:OPEN_MANUAL_FALLBACK`;
    setIsActionLoading(true);
    setManualTransferError("");
    try {
      await apiClient.patch(
        `/api/payments/refund/${manualRefundTarget.refundId}/manual-fallback/open`,
        { reason: manualFallbackReason.trim() },
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(operationScope) } },
      );
      clearIdempotencyKey(operationScope);
      const response = await apiClient.get(`/api/payments/refund/${manualRefundTarget.refundId}/manual-details`);
      setManualRefundDetails(toData<ManualRefundDetails>(response));
      showToast("Đã mở fallback thủ công; refund vẫn chưa hoàn tất.", "info");
      await loadPendingRefunds();
    } catch (error: unknown) {
      setManualTransferError(getApiErrorMessage(error, "Không thể mở fallback thủ công"));
    } finally {
      setIsActionLoading(false);
    }
  };

  const handleCreateWalkIn = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const expectedCheckOut = new Date(walkInForm.checkOut);
    if (!walkInForm.checkOut || Number.isNaN(expectedCheckOut.getTime()) || expectedCheckOut <= new Date()) {
      showToast(localize("Thời gian trả phòng phải sau thời gian nhận phòng hiện tại", "Check-out must be after the current check-in time"), "error");
      return;
    }
    const customerPhoneDigits = walkInForm.customerPhone.replace(/[\s().+-]/g, "");
    if (walkInForm.customerName.trim().length < 2 || walkInForm.customerName.trim().length > 100
      || walkInForm.customerIdCard.trim().length < 4 || walkInForm.customerIdCard.trim().length > 50
      || !/^\d{8,15}$/.test(customerPhoneDigits)
      || (Boolean(walkInForm.customerEmail.trim()) && (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(walkInForm.customerEmail.trim()) || walkInForm.customerEmail.trim().length > 254))) {
      showToast(localize("Vui lòng kiểm tra họ tên, giấy tờ, số điện thoại và email của khách đại diện.", "Check the primary guest's name, ID, phone number, and email."), "error");
      return;
    }
    if (selectedWalkInRoomIds.length === 0) {
      showToast("Vui lòng chọn ít nhất một phòng trống sạch", "error");
      return;
    }
    const walkInGuestGroups = selectedWalkInRoomIds.map((roomId) => walkInGuestsByRoom[roomId] || []);
    if (walkInGuestGroups.some((guests) => guests.length === 0
      || guests.some((guest) => {
        const phoneDigits = guest.phone.replace(/[\s().+-]/g, "");
        return guest.fullName.trim().length < 2
          || guest.fullName.trim().length > 100
          || (Boolean(guest.phone.trim()) && !/^\d{8,15}$/.test(phoneDigits))
          || (Boolean(guest.email.trim()) && (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(guest.email.trim()) || guest.email.trim().length > 254))
          || guest.idCardNumber.trim().length > 50;
      })
      || guests.filter((guest) => guest.isPrimary).length !== 1)) {
      showToast("Mỗi phòng cần có khách lưu trú và đúng một khách đại diện", "error");
      return;
    }
    const enteredGuestCount = walkInGuestGroups.reduce((total, guests) => total + guests.length, 0);
    if (enteredGuestCount !== Number(walkInForm.guestCount)) {
      showToast(`Số khách khai báo là ${walkInForm.guestCount}, nhưng danh sách phòng có ${enteredGuestCount} khách`, "error");
      return;
    }

    const selectedRooms = walkInRooms.filter((room) => selectedWalkInRoomIds.includes(room.id));
    const selectedRoomTypeIds = new Set(selectedRooms.map((room) => room.roomTypeId));
    const activePriceOverrides = Object.entries(walkInPriceOverrides)
      .filter(([roomTypeId, draft]) => draft.enabled && selectedRoomTypeIds.has(Number(roomTypeId)))
      .map(([roomTypeId, draft]) => ({ roomTypeId: Number(roomTypeId), draft }));
    const invalidOverride = activePriceOverrides.find(({ draft }) => {
      const amount = Number(draft.amount.replace(/[^0-9]/g, ""));
      return !Number.isSafeInteger(amount) || amount < 0 || !draft.reasonCode.trim() || !draft.note.trim();
    });
    if (invalidOverride) {
      showToast(localize("Giá thay thế phải là số VND nguyên không âm và có đầy đủ lý do.", "An override must be a non-negative whole-VND amount with a documented reason."), "error");
      return;
    }

    setIsActionLoading(true);
    let createdReservation: ReservationItem | null = null;
    let checkedIn = false;
    try {
      const walkInOperationScope = "walk-in:create-current";
      const createRes = await apiClient.post("/api/reservations/walk-in/v2", {
        customer: {
          fullName: walkInForm.customerName.trim(),
          phone: walkInForm.customerPhone.trim(),
          email: walkInForm.customerEmail.trim() || undefined,
          idCardNumber: walkInForm.customerIdCard.trim(),
        },
        checkOut: `${walkInForm.checkOut}:00`,
        guestCount: Number(walkInForm.guestCount),
        note: walkInForm.note,
        rooms: selectedRooms.map((room) => ({
          roomId: room.id,
          guests: (walkInGuestsByRoom[room.id] || []).map((guest) => ({
            ...guest,
            email: guest.email || undefined,
            idCardNumber: guest.idCardNumber || undefined,
          })),
        })),
        priceOverrides: activePriceOverrides.map(({ roomTypeId, draft }) => ({
          roomTypeId,
          newUnitPrice: Number(draft.amount.replace(/[^0-9]/g, "")),
          reasonCode: draft.reasonCode.trim(),
          note: draft.note.trim(),
        })),
        paymentOption: walkInPaymentMethod === "NONE" ? "UNPAID" : walkInPaymentMethod,
      }, {
        headers: { "Idempotency-Key": getOrCreateIdempotencyKey(walkInOperationScope) },
      });
      clearIdempotencyKey(walkInOperationScope);
      const walkInResult = toData<WalkInOperationResponse>(createRes);
      createdReservation = walkInResult.reservation;
      checkedIn = walkInResult.reservationCreated;

      if (walkInPaymentMethod === "SEPAY") {
        if (walkInResult.paymentCreationStatus === "FAILED" || !walkInResult.paymentInstructions) {
          showToast(
            `Đã tạo và check-in đơn ${createdReservation.reservationCode}, nhưng chưa tạo được QR: ${walkInResult.paymentError || "Không xác định"}`,
            "error",
          );
          setIsWalkInOpen(false);
          setSelectedWalkInRoomIds([]);
          await loadReservations();
          await openFinalPayment({ ...createdReservation, status: "CHECKED_IN" });
          return;
        }
        const payment = walkInResult.paymentInstructions;
        const paymentUrl = payment?.paymentUrl || payment?.payment_url
          || (payment?.transactionId
            ? `/booking/payment-result?transactionId=${encodeURIComponent(payment.transactionId)}`
            : "");
        if (!paymentUrl) throw new Error("Backend không trả về paymentUrl");
        sessionStorage.setItem("staff_final_payment_reservation_id", String(createdReservation.id));
        window.location.assign(paymentUrl);
        return;
      }

      showToast(
        walkInPaymentMethod === "NONE"
          ? `Đã check-in ${selectedRooms.length} phòng, chưa thu tiền`
          : `Đã tạo, check-in và xử lý thanh toán ${selectedRooms.length} phòng`,
        "success"
      );
      setIsWalkInOpen(false);
      setSelectedWalkInRoomIds([]);
      await loadReservations();
    } catch (error: unknown) {
      const errorMessage = getApiErrorMessage(error, "Không thể tạo walk-in reservation");
      if (createdReservation && checkedIn) {
        // Create/check-in là các request đã commit. Không giữ form ở trạng
        // thái có thể submit lại và tạo trùng reservation khi bước thu
        // tiền phía sau thất bại.
        showToast(
          `Đã tạo và check-in đơn ${createdReservation.reservationCode}, nhưng chưa ghi nhận thanh toán: ${errorMessage}`,
          "error"
        );
        setIsWalkInOpen(false);
        setSelectedWalkInRoomIds([]);
        await loadReservations();
        await openFinalPayment({ ...createdReservation, status: "CHECKED_IN" });
      } else {
        showToast(errorMessage, "error");
      }
    } finally {
      setIsActionLoading(false);
    }
  };

  useEffect(() => {
    if (!pendingFinalPaymentId || reservations.length === 0) return;
    const reservation = reservations.find((item) => item.id === pendingFinalPaymentId);
    if (!reservation) return;
    setPendingFinalPaymentId(null);
    window.history.replaceState(null, "", "/dashboard/reservations");
    openFinalPayment(reservation);
  }, [openFinalPayment, pendingFinalPaymentId, reservations]);

  const getStatusClass = (status: ReservationStatus) => {
    switch (status) {
      case "DRAFT":
        return "bg-amber-50 text-amber-700 border-amber-200";
      case "PAYMENT_PENDING":
        return "bg-sky-50 text-sky-700 border-sky-200";
      case "CONFIRMED":
        return "bg-blue-50 text-blue-700 border-blue-200";
      case "CHECKED_IN":
        return "bg-emerald-50 text-emerald-700 border-emerald-200";
      case "CHECKED_OUT":
        return "bg-gray-50 text-gray-700 border-gray-200";
      case "CANCELLED":
        return "bg-rose-50 text-rose-700 border-rose-200";
      case "NO_SHOW":
        return "bg-orange-50 text-orange-700 border-orange-200";
      case "CANCELLATION_PENDING":
        return "bg-violet-50 text-violet-700 border-violet-200";
    }
  };

  const getStatusLabel = (status: "ALL" | ReservationStatus) => {
    const labels = locale === "vi"
      ? { ALL: "Tất cả", DRAFT: "Chờ xác nhận", PAYMENT_PENDING: "Chờ thanh toán", CONFIRMED: "Đã xác nhận", CANCELLATION_PENDING: "Chờ duyệt hủy", CANCELLED: "Đã hủy", CHECKED_IN: "Đang lưu trú", CHECKED_OUT: "Đã trả phòng", NO_SHOW: "Không đến" }
      : { ALL: "All", DRAFT: "Draft", PAYMENT_PENDING: "Payment pending", CONFIRMED: "Confirmed", CANCELLATION_PENDING: "Cancellation pending", CANCELLED: "Cancelled", CHECKED_IN: "Checked in", CHECKED_OUT: "Checked out", NO_SHOW: "No-show" };
    return labels[status];
  };

  const isPastCheckInDate = (checkIn: string) => {
    const planned = new Date(checkIn);
    const now = new Date();
    return new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
      > new Date(planned.getFullYear(), planned.getMonth(), planned.getDate()).getTime();
  };

  const renderRefundItem = (refund: PendingRefund) => {
    const isManualTransfer = refund.refundChannel === "MANUAL_BANK_TRANSFER";
    const isCashAtCounter = refund.refundChannel === "CASH_AT_COUNTER";
    const isVnpayOriginal = refund.refundChannel === "VNPAY_ORIGINAL"
      || (!refund.refundChannel && refund.refundProvider === "VNPAY");
    const hasSubmittedRecipient = refund.recipientStatus === "SUBMITTED"
      || refund.recipientStatus === "VERIFIED"
      || Boolean(refund.recipientAccountMasked);
    const waitingForCustomer = isManualTransfer
      && (refund.status === "AWAITING_CUSTOMER_INFO" || (Boolean(refund.recipientRequired) && !hasSubmittedRecipient));
    const canOpenManualDetails = isManualTransfer
      && !waitingForCustomer
      && ["READY_FOR_MANUAL_TRANSFER", "REQUESTED", "PROCESSING"].includes(refund.status);
    const statusTone = refund.status === "FAILED"
      ? "bg-rose-100 text-rose-800"
      : refund.status === "MANUAL_REVIEW"
        ? "bg-violet-100 text-violet-800"
        : ["READY_FOR_MANUAL_TRANSFER", "REQUESTED", "PROCESSING"].includes(refund.status)
          ? "bg-sky-100 text-sky-800"
          : "bg-amber-100 text-amber-800";

    return (
      <article key={refund.refundId} className="rounded-xl border border-amber-200 bg-white p-4 shadow-sm">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <span className={`rounded-full px-2 py-0.5 text-[10px] font-bold ${statusTone}`}>{refund.status}</span>
              <span className="rounded-full bg-[#E5E9ED] px-2 py-0.5 text-[10px] font-bold text-[#66727C]">
                {isManualTransfer ? "QR" : isCashAtCounter ? localize("Tiền mặt", "Cash") : localize("Giao dịch cũ", "Legacy transaction")}
              </span>
            </div>
            <p className="mt-2 text-sm font-semibold leading-5 text-[#66727C]">
              {isManualTransfer
                ? waitingForCustomer
                  ? localize("Chờ khách bổ sung tài khoản nhận tiền", "Waiting for customer bank details")
                  : [refund.recipientBankName || refund.recipientBankCode, refund.recipientAccountMasked].filter(Boolean).join(" · ") || localize("Thông tin người nhận đã được bảo vệ", "Recipient details are protected")
                : refund.message || (isCashAtCounter ? localize("Chờ giao tiền mặt và xác nhận phiếu nhận.", "Awaiting cash handover and receipt confirmation.") : localize("Chờ xử lý với cổng thanh toán.", "Awaiting provider processing."))}
            </p>
          </div>
          <strong className="shrink-0 text-lg tabular-nums text-amber-800">{formatVND(refund.amount || 0)}</strong>
        </div>
        <div className="mt-4 flex flex-wrap justify-end gap-2">
          {isManualTransfer ? (
            <button
              type="button"
              disabled={isActionLoading || !canOpenManualDetails}
              onClick={() => {
                setRefundReservationTarget(null);
                void openManualRefundDetails(refund);
              }}
              className="min-h-10 rounded-lg bg-[#0F2A43] px-4 text-xs font-bold text-white disabled:cursor-not-allowed disabled:opacity-50"
            >
              {waitingForCustomer ? localize("Chờ khách bổ sung", "Awaiting customer") : localize("Mở QR & theo dõi", "Open QR & track")}
            </button>
          ) : isCashAtCounter ? (
            <button
              type="button"
              disabled={isActionLoading || !refund.canCompleteCash}
              onClick={() => {
                setRefundReservationTarget(null);
                setConfirmAction({ kind: "CASH_REFUND", refund });
              }}
              className="min-h-10 rounded-lg bg-emerald-700 px-4 text-xs font-bold text-white disabled:cursor-not-allowed disabled:opacity-50"
            >
              {localize("Xác nhận đã trả tiền mặt", "Confirm cash refund")}
            </button>
          ) : refund.status === "MANUAL_REVIEW" && isVnpayOriginal ? (
            <span className="inline-flex min-h-10 items-center rounded-lg border border-violet-200 bg-violet-50 px-4 text-xs font-bold text-violet-800">{localize("Kiểm tra merchant portal", "Check merchant portal")}</span>
          ) : (
            <button
              type="button"
              disabled={isActionLoading || !isVnpayOriginal || (!refund.canRetry && !refund.canReconcile)}
              onClick={() => {
                setRefundReservationTarget(null);
                setConfirmAction({ kind: "REFUND_PROVIDER", refund });
              }}
              className="min-h-10 rounded-lg bg-[#0F2A43] px-4 text-xs font-bold text-white disabled:cursor-not-allowed disabled:opacity-50"
            >
              {refund.canRetry ? localize("Gửi / thử lại", "Send / retry") : localize("Đối soát giao dịch cũ", "Reconcile legacy transaction")}
            </button>
          )}
        </div>
      </article>
    );
  };

  const renderReservationActions = (reservation: ReservationItem) => {
    const reservationRefunds = pendingRefundsByReservation.get(reservation.id) || [];
    const cancellationRefundPending = Boolean(reservation.cancellationRefundPending);
    const hasActiveRoomHold = Boolean(reservation.roomTypes?.some(
      (roomType) => roomType.roomHold?.status === "ACTIVE",
    ));
    return (
    <div className="flex flex-wrap items-center justify-end gap-2">
      {reservationRefunds.length > 0 && (
        <button type="button" onClick={() => setRefundReservationTarget(reservation)} className="min-h-10 rounded-lg border border-amber-300 bg-amber-50 px-3 text-xs font-bold text-amber-900 hover:bg-amber-100">
          {localize("Xử lý hoàn tiền", "Process refund")} ({reservationRefunds.length})
        </button>
      )}
      {hasActiveRoomHold && (
        <button disabled={isActionLoading} type="button" onClick={() => openRoomHoldRelease(reservation)} className="min-h-10 rounded-lg border border-orange-300 bg-orange-50 px-3 text-xs font-bold text-orange-900 hover:bg-orange-100 disabled:opacity-50">
          {localize("Nhả giữ phòng", "Release hold")}
        </button>
      )}
      {reservation.status === "DRAFT" && !cancellationRefundPending && (
        <button type="button" onClick={() => setConfirmAction({ kind: "CONFIRM_RESERVATION", reservation })} className="min-h-10 rounded-lg bg-emerald-700 px-3 text-xs font-bold text-white hover:bg-emerald-800">
          {localize("Xác nhận", "Confirm")}
        </button>
      )}
      {(reservation.status === "DRAFT" || reservation.status === "CONFIRMED") && !cancellationRefundPending && (
        <button disabled={isActionLoading} type="button" onClick={() => openStaffCancel(reservation)} className="min-h-10 rounded-lg border border-rose-200 bg-white px-3 text-xs font-bold text-rose-700 hover:bg-rose-50 disabled:opacity-50">
          {localize("Hủy đơn", "Cancel")}
        </button>
      )}
      {reservation.status === "CANCELLATION_PENDING" && !cancellationRefundPending && (
        <>
          <button disabled={isActionLoading} type="button" onClick={() => handleCancellationDecision(reservation, "approve_refund")} className="min-h-10 rounded-lg bg-emerald-700 px-3 text-xs font-bold text-white hover:bg-emerald-800 disabled:opacity-50">
            {localize("Hủy & hoàn tiền", "Cancel & refund")}
          </button>
          <button disabled={isActionLoading} type="button" onClick={() => setConfirmAction({ kind: "CANCELLATION_DECISION", reservation, decision: "approve_no_refund" })} className="min-h-10 rounded-lg bg-rose-700 px-3 text-xs font-bold text-white hover:bg-rose-800 disabled:opacity-50">
            {localize("Hủy không hoàn", "Cancel without refund")}
          </button>
          <button disabled={isActionLoading} type="button" onClick={() => setConfirmAction({ kind: "CANCELLATION_DECISION", reservation, decision: "reject" })} className="min-h-10 rounded-lg border border-[#0F2A43]/20 bg-white px-3 text-xs font-bold text-[#0F2A43] hover:bg-[#E5E9ED] disabled:opacity-50">
            {localize("Từ chối hủy", "Reject")}
          </button>
        </>
      )}
      {reservation.status === "CONFIRMED" && !cancellationRefundPending && !isPastCheckInDate(reservation.checkIn) && (
        <button type="button" onClick={() => openCheckIn(reservation)} className="min-h-10 rounded-lg bg-[#0F2A43] px-3 text-xs font-bold text-white hover:bg-[#091E30]">
          {localize("Nhận phòng", "Check in")}
        </button>
      )}
      {reservation.status === "CONFIRMED" && !cancellationRefundPending && reservation.noShowEligible && (
        <button disabled={isActionLoading} type="button" onClick={() => setConfirmAction({ kind: "NO_SHOW", reservation })} className="min-h-10 rounded-lg border border-orange-200 bg-orange-50 px-3 text-xs font-bold text-orange-800 hover:bg-orange-100 disabled:opacity-50">
          {localize("Không đến", "No-show")}
        </button>
      )}
      {reservation.status === "CHECKED_IN" && (
        <button type="button" onClick={() => openFinalPayment(reservation)} className="min-h-10 rounded-lg bg-[#0F2A43] px-3 text-xs font-bold text-white hover:bg-[#091E30]">
          {localize("Đối soát & trả phòng", "Settle & check out")}
        </button>
      )}
      {reservation.status === "CHECKED_OUT" && (
        <button disabled={isActionLoading} type="button" onClick={() => openInvoice(reservation)} className="min-h-10 rounded-lg border border-[#0F2A43]/20 bg-white px-3 text-xs font-bold text-[#0F2A43] hover:bg-[#E5E9ED] disabled:opacity-50">
          {localize("In hóa đơn", "Print invoice")}
        </button>
      )}
      {isAdmin && (
        <button disabled={isActionLoading} type="button" onClick={() => void openReservationAudit(reservation)} className="min-h-10 rounded-lg border border-[#B8944F]/60 bg-[#F1F0EA] px-3 text-xs font-bold text-[#0F2A43] hover:bg-white disabled:opacity-50">
          {localize("Lịch sử", "Activity")}
        </button>
      )}
    </div>
    );
  };

  const checkInModalWidth = checkInDrafts.length <= 1
    ? "max-w-2xl"
    : checkInDrafts.length <= 2
      ? "max-w-4xl"
      : "max-w-6xl";
  const checkInGridColumns = checkInDrafts.length <= 1 ? "grid-cols-1" : "lg:grid-cols-2";

  return (
    <div className="ops-page mx-auto w-full max-w-[1600px] space-y-6 p-4 sm:p-6 lg:p-8">
      <div className="flex flex-col gap-4 border-b border-[#0F2A43]/5 pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-xs font-bold uppercase tracking-[0.25em] text-[#80632F]">{localize("Vận hành lễ tân", "Front desk operations")}</p>
          <h1 className="mt-2 text-3xl font-bold leading-tight tracking-tight text-[#0F2A43]">{localize("Quản lý đặt phòng", "Reservation management")}</h1>
          <p className="mt-1.5 text-sm font-semibold text-[#66727C]">{localize("Xác nhận, nhận phòng, thanh toán và trả phòng theo dữ liệu backend.", "Confirm, check in, settle payments and check out using backend data.")}</p>
        </div>
        <button
          type="button"
          onClick={() => void Promise.all([
            loadReservations(),
            loadPendingRefunds(),
            loadSePayReviewEvents(),
          ])}
          className="self-start rounded-xl border border-[#0F2A43]/10 bg-white px-5 py-2.5 text-sm font-bold text-[#0F2A43] shadow-sm transition hover:border-[#B8944F] hover:bg-[#FBFAF6] hover:shadow-md active:scale-[0.98]"
        >
          {localize("Làm mới dữ liệu", "Refresh data")}
        </button>
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {(locale === "vi" ? [
          ["Tổng số", stats.total], ["Chờ xác nhận", stats.draft], ["Đã xác nhận", stats.confirmed], ["Đang lưu trú", stats.checkedIn],
        ] : [
          ["Total", stats.total], ["Draft", stats.draft], ["Confirmed", stats.confirmed], ["Checked in", stats.checkedIn],
        ]).map(([label, value]) => (
          <div key={label} className="rounded-xl border border-[#0F2A43]/10 bg-white p-4">
            <p className="text-xs font-semibold text-[#66727C]">{label}</p>
            <p className="mt-2 text-3xl font-bold tabular-nums text-[#0F2A43]">{value}</p>
          </div>
        ))}
      </div>

      <div className="flex flex-col gap-4 rounded-xl border border-[#0F2A43]/10 bg-[#E5E9ED] p-5 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <p className="text-lg font-bold text-[#0F2A43]">{localize("Khách walk-in tại quầy", "Front desk walk-in")}</p>
          <p className="mt-1 text-sm font-medium text-[#66727C]">{localize("Tạo đơn, gán phòng trống sạch và nhận phòng trong một thao tác.", "Create a reservation, assign clean available rooms and check in in one flow.")}</p>
        </div>
        <button type="button" onClick={() => openWalkInModal()} className="min-h-11 shrink-0 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white hover:bg-[#091E30]">
          {localize("Đặt phòng & nhận phòng", "Reserve & check in")}
        </button>
      </div>

      {sePayReviewEvents.length > 0 && (
        <section className="rounded-xl border border-rose-200 bg-rose-50/70 p-5" aria-labelledby="sepay-review-title">
          <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
            <div>
              <h2 id="sepay-review-title" className="text-lg font-bold text-[#0F2A43]">{localize("Giao dịch ngân hàng cần đối soát", "Bank transactions requiring review")}</h2>
              <p className="mt-1 max-w-4xl text-sm leading-6 text-[#66727C]">{localize("Giao dịch tiền vào hoặc tiền ra chưa khớp đúng mã nghiệp vụ. Hệ thống không tự gán theo số tiền gần đúng; Staff/Admin phải kiểm tra mã tham chiếu và nội dung ngân hàng.", "An incoming or outgoing transaction did not match an exact business code. The system never assigns it by an approximate amount; Staff/Admin must review the bank reference and content.")}</p>
            </div>
            <span className="rounded-full bg-rose-100 px-3 py-1 text-xs font-bold text-rose-800">{sePayReviewEvents.length} {localize("giao dịch", "transactions")}</span>
          </div>
          <div className="grid gap-3 lg:grid-cols-2">
            {sePayReviewEvents.map((event) => (
              <article key={event.eventId} className="rounded-lg border border-rose-200 bg-white p-4">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div>
                    <p className="text-xs font-bold uppercase tracking-wider text-rose-700">SePay review</p>
                    <p className="mt-1 break-all font-mono text-xs font-semibold text-[#0F2A43]">{event.providerReference || event.providerEventId}</p>
                  </div>
                  <p className="font-bold tabular-nums text-rose-800">{formatVND(event.amount || 0)}</p>
                </div>
                <dl className="mt-3 grid gap-1 text-xs text-[#66727C]">
                  <div className="flex justify-between gap-4"><dt>{localize("Mã trích xuất", "Extracted code")}</dt><dd className="font-mono font-semibold">{event.paymentCode || "—"}</dd></div>
                  <div className="flex justify-between gap-4"><dt>{localize("Loại giao dịch", "Transfer type")}</dt><dd className="font-semibold">{event.transferType === "out" ? localize("Tiền ra", "Outgoing") : localize("Tiền vào", "Incoming")}</dd></div>
                  <div className="flex justify-between gap-4"><dt>{localize("Tài khoản nhận", "Receiving account")}</dt><dd className="font-semibold">{event.accountNumberMasked || "—"}</dd></div>
                  <div className="flex justify-between gap-4"><dt>{localize("Thời điểm ngân hàng", "Bank time")}</dt><dd className="text-right font-semibold">{event.providerOccurredAt || event.createdAt || "—"}</dd></div>
                </dl>
                <p className="mt-3 text-xs font-medium leading-5 text-[#66727C]">{event.message || localize("Chờ đối soát thủ công.", "Awaiting manual reconciliation.")}</p>
                <div className="mt-4 grid gap-2 sm:grid-cols-2">
                  {event.transferType !== "out" && <button type="button" disabled={isActionLoading} onClick={() => openSePayAction(event, "MATCH")} className="min-h-10 rounded-lg bg-[#0F2A43] px-3 text-xs font-bold text-white disabled:opacity-50">
                    {localize("Ghép payment", "Match payment")}
                  </button>}
                  {isAdmin && event.transferType !== "out" && <button type="button" disabled={isActionLoading} onClick={() => openSePayAction(event, "RECOVER")} className="min-h-10 rounded-lg bg-rose-800 px-3 text-xs font-bold text-white disabled:opacity-50">
                    {localize("Khôi phục có kiểm soát", "Controlled recovery")}
                  </button>}
                  {event.transferType !== "out" && <button type="button" disabled={isActionLoading} onClick={() => openSePayAction(event, "REFUND")} className="min-h-10 rounded-lg bg-amber-700 px-3 text-xs font-bold text-white disabled:opacity-50">
                    {localize("Tạo hoàn tiền", "Create refund")}
                  </button>}
                  <button type="button" disabled={isActionLoading} onClick={() => openSePayAction(event, "IGNORE")} className={`min-h-10 rounded-lg border border-rose-200 bg-white px-3 text-xs font-bold text-rose-800 disabled:opacity-50 ${event.transferType === "out" ? "sm:col-span-2" : ""}`}>
                    {event.transferType === "out" ? localize("Đã đối soát thủ công", "Mark manually reviewed") : localize("Bỏ qua", "Ignore")}
                  </button>
                </div>
              </article>
            ))}
          </div>
        </section>
      )}

      {unmatchedPendingRefunds.length > 0 && (
        <section className="rounded-xl border border-violet-200 bg-violet-50/60 p-5" aria-labelledby="unmatched-refunds-title">
          <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
            <div>
              <h2 id="unmatched-refunds-title" className="text-lg font-bold text-[#0F2A43]">{localize("Hoàn tiền giao dịch chưa ghép đơn", "Refunds without a matched reservation")}</h2>
              <p className="mt-1 max-w-4xl text-sm leading-6 text-[#66727C]">{localize("Ngoại lệ này không thể đặt trong một đơn cụ thể. Staff/Admin vẫn phải xử lý để không làm mất nghĩa vụ hoàn khỏi ledger.", "These exceptions cannot be placed inside a reservation. Staff/Admin must still process them so no refund obligation disappears from the ledger.")}</p>
            </div>
            <span className="rounded-full bg-violet-100 px-3 py-1 text-xs font-bold text-violet-800">{unmatchedPendingRefunds.length} {localize("ngoại lệ", "exceptions")}</span>
          </div>
          <div className="grid gap-3 lg:grid-cols-2">{unmatchedPendingRefunds.map(renderRefundItem)}</div>
        </section>
      )}

      <DashboardFilterPanel
        title={localize("Bộ lọc đơn đặt", "Reservation filters")}
        description={localize("Tìm theo khách hàng, trạng thái thanh toán và ngày sử dụng phòng", "Search by guest, payment status and stay date")}
        resultCount={filteredReservations.length}
        resultLabel={localize("đơn phù hợp", "matching reservations")}
        resultNote={localize("sắp xếp theo mức độ cần xử lý", "prioritized by required action")}
        hasActiveFilters={Boolean(searchQuery || selectedStatus !== "ALL" || paymentFilter !== "ALL" || stayDate)}
        activeFilterCount={Number(Boolean(searchQuery)) + Number(selectedStatus !== "ALL") + Number(paymentFilter !== "ALL") + Number(Boolean(stayDate))}
        activeFilterLabel={localize("bộ lọc đang dùng", "active filters")}
        onReset={() => {
          setSearchQuery("");
          setSelectedStatus("ALL");
          setPaymentFilter("ALL");
          setStayDate("");
        }}
        resetLabel={localize("Xóa toàn bộ bộ lọc", "Clear all filters")}
        actions={(
          <>
            <FilterQuickButton active={stayDate === todayDate().slice(0, 10)} onClick={() => setStayDate((current) => current === todayDate().slice(0, 10) ? "" : todayDate().slice(0, 10))}>
              {localize("Hôm nay", "Today")}
            </FilterQuickButton>
            <FilterQuickButton active={stayDate === tomorrowDate().slice(0, 10)} onClick={() => setStayDate((current) => current === tomorrowDate().slice(0, 10) ? "" : tomorrowDate().slice(0, 10))}>
              {localize("Ngày mai", "Tomorrow")}
            </FilterQuickButton>
          </>
        )}
      >
        <DashboardSearchField
          id="reservation-search"
          label={localize("Tìm kiếm", "Search")}
          value={searchQuery}
          onChange={setSearchQuery}
          placeholder={localize("Mã đơn, tên khách hoặc ID...", "Reservation code, guest name or ID...")}
          clearLabel={localize("Xóa từ khóa", "Clear search")}
        />
        <div className="grid gap-4 md:grid-cols-3">
          <DashboardSelectField
            id="reservation-status"
            label={localize("Trạng thái đơn", "Reservation status")}
            value={selectedStatus}
            onChange={(event) => setSelectedStatus(event.target.value as "ALL" | ReservationStatus)}
          >
            {(["ALL", "DRAFT", "PAYMENT_PENDING", "CONFIRMED", "CANCELLATION_PENDING", "CHECKED_IN", "CHECKED_OUT", "NO_SHOW", "CANCELLED"] as const).map((status) => (
              <option key={status} value={status}>{getStatusLabel(status)}</option>
            ))}
          </DashboardSelectField>
          <DashboardSelectField
            id="reservation-payment"
            label={localize("Thanh toán", "Payment")}
            value={paymentFilter}
            onChange={(event) => setPaymentFilter(event.target.value as PaymentFilter)}
          >
            <option value="ALL">{localize("Tất cả", "All")}</option>
            <option value="UNPAID">{localize("Chưa thanh toán", "Unpaid")}</option>
            <option value="PARTIAL">{localize("Đã thanh toán một phần", "Partially paid")}</option>
            <option value="PAID">{localize("Đã thanh toán đủ", "Paid in full")}</option>
          </DashboardSelectField>
          <div>
            <label htmlFor="reservation-stay-date" className="mb-2 block text-xs font-bold text-[#66727C]">{localize("Ngày sử dụng", "Stay date")}</label>
            <input
              id="reservation-stay-date"
              type="date"
              value={stayDate}
              onChange={(event) => setStayDate(event.target.value)}
              className="min-h-11 w-full rounded-lg border border-[#0F2A43]/18 bg-white px-3 py-2.5 text-sm font-semibold text-[#27445F] outline-none transition hover:border-[#0F2A43]/30 focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20"
            />
          </div>
        </div>
      </DashboardFilterPanel>

      <section className="overflow-hidden rounded-xl border border-[#0F2A43]/10 bg-white" aria-labelledby="reservation-list-title">

        <div className="flex flex-col gap-2 border-b border-[#0F2A43]/10 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <div><h2 id="reservation-list-title" className="font-bold text-[#0F2A43]">{localize("Danh sách đặt phòng", "Reservation list")}</h2><p className="mt-0.5 text-xs text-[#66727C]">{filteredReservations.length} {localize("kết quả", "results")}</p></div>
          <span className="text-xs font-semibold text-[#66727C] sm:text-right">{localize("Chọn một dòng để xem chi tiết và thao tác", "Select a row to review details and actions")}</span>
        </div>

        {isLoading ? (
          <div className="space-y-3 p-4" role="status" aria-label={localize("Đang tải đặt phòng", "Loading reservations")}>{[1, 2, 3].map((item) => <div key={item} className="h-20 animate-pulse rounded-lg bg-[#F1F0EA]" />)}</div>
        ) : filteredReservations.length === 0 ? (
          <div className="px-6 py-14 text-center"><p className="font-bold text-[#0F2A43]">{localize("Không có đặt phòng phù hợp", "No matching reservations")}</p><p className="mt-2 text-sm text-[#66727C]">{localize("Thử thay đổi từ khóa hoặc bộ lọc trạng thái.", "Try a different keyword or status filter.")}</p></div>
        ) : (
          <>
          <div className="hidden overflow-x-auto lg:block">
            <table className="w-full min-w-[1080px] text-left text-sm">
              <thead className="sticky top-0 bg-[#F1F0EA] text-xs font-bold text-[#66727C]">
                <tr>
                  <th className="px-5 py-3">{localize("Đơn & khách hàng", "Reservation & customer")}</th>
                  <th className="px-5 py-3">{localize("Lưu trú & phòng", "Stay & rooms")}</th>
                  <th className="px-5 py-3 text-right">{localize("Thanh toán", "Payment")}</th>
                  <th className="px-4 py-3">{localize("Trạng thái", "Status")}</th>
                  <th className="w-[290px] px-5 py-3 text-right">{localize("Hành động tiếp theo", "Next action")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[#0F2A43]/5">
                {filteredReservations.map((reservation) => (
                  <tr key={reservation.id} className="align-top hover:bg-[#F1F0EA]/70">
                    <td className="px-5 py-4">
                      <p className="font-bold text-[#0F2A43]">{reservation.customerName || localize("Chưa có tên khách", "Unnamed customer")}</p>
                      <p className="mt-1 font-mono text-xs font-bold text-[#0F2A43]">{reservation.reservationCode || `#${reservation.id}`}</p>
                      <p className="mt-1 text-xs text-[#66727C]">{reservation.guestCount} {localize("khách", "guests")}</p>
                    </td>
                    <td className="px-5 py-4 text-xs text-[#66727C]">
                      <p className="font-semibold text-[#0F2A43]">{formatDate(reservation.checkIn)} <span className="mx-1 text-[#A39B92]">→</span> {formatDate(reservation.checkOut)}</p>
                      <p className="mt-1.5 max-w-md leading-5">{(reservation.roomTypes || []).length ? reservation.roomTypes?.map((roomType) => `${localize(roomType.roomTypeName, roomType.roomTypeNameEn)} × ${roomType.quantity}`).join(" · ") : localize("Chưa có thông tin loại phòng", "No room type information")}</p>
                      {reservation.actualCheckIn && <p className="mt-1 font-semibold text-emerald-700">{localize("Nhận thực tế", "Actual check-in")}: {formatDate(reservation.actualCheckIn)}</p>}
                      {reservation.actualCheckOut && <p className="font-semibold text-blue-700">{localize("Trả thực tế", "Actual check-out")}: {formatDate(reservation.actualCheckOut)}</p>}
                    </td>
                    <td className="px-5 py-4 text-right tabular-nums"><p className="font-bold text-[#0F2A43]">{formatVND(Number(reservation.plannedTotalAmount ?? reservation.totalAmount ?? 0))}</p><p className="mt-1 text-xs font-semibold text-emerald-700">{localize("Đã trả", "Paid")}: {formatVND(Number(reservation.paidAmount || 0))}</p></td>
                    <td className="px-4 py-4">
                      <span className={`inline-flex rounded-lg border px-2.5 py-1 text-xs font-bold ${reservation.cancellationRefundPending ? "border-amber-200 bg-amber-50 text-amber-800" : getStatusClass(reservation.status)}`}>{reservation.cancellationRefundPending ? reservation.refundRoute === "CASH_AT_COUNTER" ? localize("Chờ giao tiền mặt", "Cash handover pending") : localize("Chờ hoàn QR", "QR refund pending") : getStatusLabel(reservation.status)}</span>
                    </td>
                    <td className="px-5 py-4">{renderReservationActions(reservation)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="divide-y divide-[#0F2A43]/10 lg:hidden">
            {filteredReservations.map((reservation) => (
              <article key={reservation.id} className="p-4">
                <div className="flex items-start justify-between gap-3">
                  <div><p className="font-bold text-[#0F2A43]">{reservation.customerName || localize("Chưa có tên khách", "Unnamed customer")}</p><p className="mt-1 font-mono text-xs font-bold text-[#0F2A43]">{reservation.reservationCode || `#${reservation.id}`}</p></div>
                  <span className={`inline-flex shrink-0 rounded-lg border px-2.5 py-1 text-[11px] font-bold ${reservation.cancellationRefundPending ? "border-amber-200 bg-amber-50 text-amber-800" : getStatusClass(reservation.status)}`}>{reservation.cancellationRefundPending ? reservation.refundRoute === "CASH_AT_COUNTER" ? localize("Chờ giao tiền mặt", "Cash handover pending") : localize("Chờ hoàn QR", "QR refund pending") : getStatusLabel(reservation.status)}</span>
                </div>
                <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-3 text-xs">
                  <div className="col-span-2"><dt className="text-[#66727C]">{localize("Thời gian dự kiến", "Planned stay")}</dt><dd className="mt-1 font-semibold text-[#0F2A43]">{formatDate(reservation.checkIn)} → {formatDate(reservation.checkOut)}</dd></div>
                  <div className="col-span-2"><dt className="text-[#66727C]">{localize("Loại phòng", "Room types")}</dt><dd className="mt-1 font-semibold text-[#0F2A43]">{(reservation.roomTypes || []).length ? reservation.roomTypes?.map((roomType) => `${localize(roomType.roomTypeName, roomType.roomTypeNameEn)} × ${roomType.quantity}`).join(" · ") : "—"}</dd></div>
                  <div><dt className="text-[#66727C]">{localize("Tổng dự kiến", "Planned total")}</dt><dd className="mt-1 font-bold tabular-nums text-[#0F2A43]">{formatVND(Number(reservation.plannedTotalAmount ?? reservation.totalAmount ?? 0))}</dd></div>
                  <div><dt className="text-[#66727C]">{localize("Đã thanh toán", "Paid")}</dt><dd className="mt-1 font-bold tabular-nums text-emerald-700">{formatVND(Number(reservation.paidAmount || 0))}</dd></div>
                </dl>
                <div className="mt-4 border-t border-[#0F2A43]/10 pt-3">{renderReservationActions(reservation)}</div>
              </article>
            ))}
          </div>
          </>
        )}
      </section>

      <ViewportModal
        open={Boolean(refundReservationTarget)}
        onClose={() => setRefundReservationTarget(null)}
        labelledBy="reservation-refunds-title"
        busy={isActionLoading}
        panelClassName="max-w-2xl"
        testId="reservation-refunds-modal"
      >
        {refundReservationTarget && (
          <>
            <header className="border-b border-[#0F2A43]/10 bg-[#FBFAF6] px-5 py-4 sm:px-6">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <p className="text-xs font-bold uppercase tracking-[0.16em] text-amber-700">{localize("Nghĩa vụ tài chính của đơn", "Reservation financial obligation")}</p>
                  <h2 id="reservation-refunds-title" className="mt-1 text-xl font-bold text-[#0F2A43]">{localize("Xử lý hoàn tiền", "Process refund")} · {refundReservationTarget.reservationCode}</h2>
                  <p className="mt-1 text-sm leading-5 text-[#66727C]">{localize("Chỉ xác nhận sau khi tiền đã được giao hoặc ngân hàng/provider xác nhận thành công.", "Confirm only after cash was handed over or the bank/provider confirmed success.")}</p>
                </div>
                <button type="button" onClick={() => setRefundReservationTarget(null)} aria-label={localize("Đóng", "Close")} className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full border border-[#0F2A43]/15 text-lg font-bold text-[#0F2A43] hover:bg-white">×</button>
              </div>
            </header>
            <div className="lux-scrollbar min-h-0 flex-1 space-y-3 overflow-y-auto bg-amber-50/40 p-4 sm:p-6">
              {(pendingRefundsByReservation.get(refundReservationTarget.id) || []).map(renderRefundItem)}
              {(pendingRefundsByReservation.get(refundReservationTarget.id) || []).length === 0 && (
                <p className="rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-sm font-semibold text-emerald-800">{localize("Đơn không còn khoản hoàn chờ xử lý.", "This reservation has no remaining refund to process.")}</p>
              )}
            </div>
          </>
        )}
      </ViewportModal>

      <ViewportModal open={Boolean(isAdmin && reservationAuditTarget)} onClose={() => setReservationAuditTarget(null)} labelledBy="reservation-audit-title" panelClassName="max-w-2xl" busy={isReservationAuditLoading} testId="reservation-audit-modal">
        {reservationAuditTarget && <section className="flex min-h-0 flex-1 flex-col"><header className="flex items-start justify-between gap-4 border-b border-[#0F2A43]/10 bg-[#FBFAF6] px-5 py-4 sm:px-6"><div><p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#80632F]">{localize("Chỉ ADMIN", "ADMIN only")}</p><h2 id="reservation-audit-title" className="mt-1 text-xl font-bold text-[#0F2A43]">{localize("Lịch sử hoạt động", "Activity history")} · {reservationAuditTarget.reservationCode}</h2><p className="mt-1 text-sm text-[#66727C]">{localize("Chỉ hiển thị các hành động liên quan đến reservation này.", "Only actions related to this reservation are shown.")}</p></div><button type="button" onClick={() => setReservationAuditTarget(null)} aria-label={localize("Đóng", "Close")} className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full border text-lg text-[#66727C] hover:bg-white">×</button></header><div className="lux-scrollbar min-h-0 flex-1 overflow-y-auto p-5 sm:p-6">{reservationAuditError && <p role="alert" className="rounded-lg bg-rose-50 p-3 text-sm font-semibold text-rose-700">{reservationAuditError}</p>}{isReservationAuditLoading ? <div className="space-y-3">{[1, 2, 3].map((item) => <div key={item} className="h-16 animate-pulse rounded-lg bg-[#F1F0EA]" />)}</div> : <ol className="space-y-3">{reservationAuditLogs.map((log) => <li key={log.id} className={`rounded-xl border p-4 ${log.riskLevel === "HIGH" || log.riskLevel === "CRITICAL" ? "border-orange-300 bg-orange-50" : "border-[#0F2A43]/10 bg-white"}`}><div className="flex flex-wrap items-start justify-between gap-2"><div><p className="font-bold text-[#0F2A43]">{ACTION_LABELS[log.action] || log.action}</p><p className="mt-1 text-xs font-semibold text-[#80632F]">{log.actorName || localize("Hệ thống", "System")} · {log.actorRole}</p></div><time className="font-mono text-[11px] text-[#66727C]">{formatDate(log.occurredAtUtc)}</time></div>{log.details && <p className="mt-2 text-sm leading-5 text-[#66727C]">{log.details}</p>}{log.targetType && <p className="mt-2 font-mono text-[10px] font-semibold text-[#80632F]">{log.targetType} #{log.targetId || "—"}</p>}{log.detail && Object.keys(log.detail).length > 0 && <dl className="mt-3 grid gap-2 rounded-lg bg-[#F1F0EA] p-3 sm:grid-cols-2">{Object.entries(log.detail).map(([key, value]) => <div key={key}><dt className="text-[9px] font-bold uppercase tracking-wider text-[#66727C]">{key}</dt><dd className="mt-0.5 break-words text-xs font-semibold text-[#0F2A43]">{formatAuditValue(value)}</dd></div>)}</dl>}</li>)}</ol>}{!isReservationAuditLoading && !reservationAuditError && reservationAuditLogs.length === 0 && <p className="py-10 text-center text-sm text-[#66727C]">{localize("Chưa có hoạt động được ghi nhận.", "No recorded activity yet.")}</p>}</div></section>}
      </ViewportModal>

      <ViewportModal
        open={Boolean(sePayActionTarget)}
        onClose={closeSePayAction}
        labelledBy="sepay-action-title"
        describedBy="sepay-action-description"
        busy={isActionLoading}
        panelClassName="max-w-lg"
        testId="sepay-action-modal"
      >
        {sePayActionTarget && (
          <form onSubmit={submitSePayAction} className="flex min-h-0 flex-1 flex-col" noValidate>
            <header className="border-b border-[#0F2A43]/10 bg-[#FBFAF6] px-5 py-4 sm:px-6">
              <p className="text-xs font-bold uppercase tracking-[0.16em] text-[#80632F]">SePay review</p>
              <h2 id="sepay-action-title" className="mt-1 text-xl font-bold text-[#0F2A43]">
                {sePayActionTarget.kind === "MATCH" ? localize("Ghép với payment", "Match payment") : sePayActionTarget.kind === "RECOVER" ? localize("Khôi phục payment có kiểm soát", "Controlled payment recovery") : sePayActionTarget.kind === "REFUND" ? localize("Tạo nghĩa vụ hoàn tiền", "Create refund obligation") : localize("Bỏ qua giao dịch", "Ignore transaction")}
              </h2>
              <p id="sepay-action-description" className="mt-1 break-all font-mono text-xs text-[#66727C]">{sePayActionTarget.event.providerReference || sePayActionTarget.event.providerEventId} · {formatVND(sePayActionTarget.event.amount || 0)}</p>
            </header>
            <div className="lux-scrollbar min-h-0 flex-1 space-y-4 overflow-y-auto px-5 py-5 sm:px-6">
              {sePayActionTarget.kind === "MATCH" ? (
                <>
                  <label className="block text-sm font-semibold text-[#0F2A43]">Payment transaction ID *
                    <input data-modal-autofocus value={sePayActionForm.paymentTransactionId} onChange={(event) => { setSePayActionForm((current) => ({ ...current, paymentTransactionId: event.target.value.slice(0, 100) })); setSePayActionError(""); }} className="mt-2 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-3 font-mono text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25" />
                  </label>
                  {!sePayActionTarget.event.providerOccurredAtUtc && (
                    <DateTimeField tone="operations" label={localize("Thời điểm ngân hàng *", "Bank occurrence time *")} value={sePayActionForm.providerOccurredAtUtc} max={formatDateTimeLocal(new Date())} onValueChange={(value) => { setSePayActionForm((current) => ({ ...current, providerOccurredAtUtc: value })); setSePayActionError(""); }} />
                  )}
                  <label className="block text-sm font-semibold text-[#0F2A43]">{localize("Ghi chú đối soát", "Reconciliation note")}
                    <textarea rows={2} maxLength={500} value={sePayActionForm.note} onChange={(event) => setSePayActionForm((current) => ({ ...current, note: event.target.value }))} className="mt-2 w-full resize-none rounded-lg border border-[#0F2A43]/15 px-3 py-2 text-sm outline-none focus:border-[#B8944F]" />
                  </label>
                </>
              ) : sePayActionTarget.kind === "RECOVER" ? (
                <>
                  <div className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-xs leading-5 text-rose-950">{localize("Chỉ ADMIN. Hệ thống dùng nguyên số tiền, provider time và mã giao dịch từ event SePay đã lưu; form không cho nhập hoặc sửa các dữ kiện tài chính này.", "ADMIN only. The system uses the amount, provider time and transaction identifiers from the stored SePay event; this form cannot edit those financial facts.")}</div>
                  <label className="block text-sm font-semibold text-[#0F2A43]">{localize("Payment có thể khôi phục", "Recoverable payment")} *
                    <select data-modal-autofocus value={sePayActionForm.paymentTransactionId} onChange={(event) => { setSePayActionForm((current) => ({ ...current, paymentTransactionId: event.target.value })); setSePayActionError(""); }} disabled={isRecoveryCandidatesLoading} className="mt-2 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm outline-none focus:border-[#B8944F] disabled:opacity-60">
                      <option value="">{isRecoveryCandidatesLoading ? localize("Đang tải...", "Loading...") : localize("Chọn theo mã đơn và giao dịch", "Choose by reservation and transaction")}</option>
                      {sePayRecoveryCandidates.map((candidate) => <option key={candidate.transactionId} value={candidate.transactionId}>{candidate.reservationCode} · {candidate.transactionReference} · {candidate.status} · {formatVND(candidate.amount)}</option>)}
                    </select>
                  </label>
                  {!isRecoveryCandidatesLoading && sePayRecoveryCandidates.length === 0 && <p className="rounded-lg bg-[#F1F0EA] p-3 text-xs font-semibold text-[#66727C]">{localize("Không có payment PENDING/CANCELLED/FAILED phù hợp. Không thể dùng đường recovery với payment đã SUCCESS.", "No eligible PENDING/CANCELLED/FAILED payment exists. Recovery cannot target a SUCCESS payment.")}</p>}
                  <label className="block text-sm font-semibold text-[#0F2A43]">{localize("Nhóm lý do", "Reason code")} *<select value={sePayActionForm.reasonCode} onChange={(event) => setSePayActionForm((current) => ({ ...current, reasonCode: event.target.value }))} className="mt-2 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm"><option value="PROVIDER_EVENT_RECOVERY">Event SePay có thật nhưng tự động match thất bại</option><option value="LEGACY_PAYMENT_REFERENCE_MISMATCH">Sai lệch mã payment dữ liệu cũ</option><option value="RECONCILIATION_AFTER_DOWNTIME">Đối soát sau downtime</option></select></label>
                  <label className="block text-sm font-semibold text-[#0F2A43]">{localize("Kết quả kiểm tra", "Investigation note")} *<textarea rows={3} maxLength={500} value={sePayActionForm.note} onChange={(event) => { setSePayActionForm((current) => ({ ...current, note: event.target.value })); setSePayActionError(""); }} placeholder={localize("Nêu event và payment đã đối chiếu", "Describe the event/payment verification")} className="mt-2 w-full resize-none rounded-lg border border-[#0F2A43]/15 px-3 py-2 text-sm" /></label>
                  <label className="block text-sm font-semibold text-[#0F2A43]">{localize("Tham chiếu minh chứng", "Evidence reference")} <span className="font-normal text-[#66727C]">({localize("không bắt buộc", "optional")})</span><input maxLength={255} value={sePayActionForm.evidenceReference} onChange={(event) => setSePayActionForm((current) => ({ ...current, evidenceReference: event.target.value }))} placeholder={localize("Ví dụ: ticket đối soát nội bộ", "Example: internal reconciliation ticket")} className="mt-2 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-3 text-sm" /></label>
                </>
              ) : (
                <>
                  {sePayActionTarget.kind === "REFUND" && <RefundChannelSelector value={sePayActionForm.refundChannel} onChange={(value) => setSePayActionForm((current) => ({ ...current, refundChannel: value }))} localize={localize} />}
                  <label className="block text-sm font-semibold text-[#0F2A43]">{localize("Lý do", "Reason")} *
                    <textarea data-modal-autofocus rows={3} maxLength={500} value={sePayActionForm.reason} onChange={(event) => { setSePayActionForm((current) => ({ ...current, reason: event.target.value })); setSePayActionError(""); }} className="mt-2 w-full resize-none rounded-lg border border-[#0F2A43]/15 px-3 py-2 text-sm outline-none focus:border-[#B8944F]" />
                  </label>
                </>
              )}
              {sePayActionError && <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm font-medium text-rose-700">{sePayActionError}</p>}
            </div>
            <footer className="flex flex-col-reverse gap-2 border-t border-[#0F2A43]/10 bg-white px-5 py-4 sm:flex-row sm:justify-end sm:px-6">
              <button type="button" disabled={isActionLoading} onClick={closeSePayAction} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43] disabled:opacity-50">{localize("Đóng", "Close")}</button>
              <button type="submit" disabled={isActionLoading || isRecoveryCandidatesLoading} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white disabled:opacity-50">{isActionLoading ? localize("Đang xử lý...", "Processing...") : localize("Xác nhận", "Confirm")}</button>
            </footer>
          </form>
        )}
      </ViewportModal>

      <ViewportModal
        open={Boolean(confirmAction)}
        onClose={() => setConfirmAction(null)}
        labelledBy="dashboard-confirm-title"
        describedBy="dashboard-confirm-description"
        busy={isActionLoading}
        panelClassName="max-w-md"
        testId="dashboard-confirm-modal"
      >
        {confirmAction && (() => {
          const reservation = "reservation" in confirmAction ? confirmAction.reservation : null;
          const refund = "refund" in confirmAction ? confirmAction.refund : null;
          const copy = confirmAction.kind === "CONFIRM_RESERVATION"
            ? { eyebrow: localize("Xác nhận vận hành", "Operations confirmation"), title: localize("Xác nhận đơn đặt phòng?", "Confirm reservation?"), description: localize(`Đơn ${reservation?.reservationCode} sẽ chuyển sang trạng thái đã xác nhận.`, `Reservation ${reservation?.reservationCode} will become confirmed.`), action: localize("Xác nhận đơn", "Confirm reservation"), tone: "bg-emerald-700 hover:bg-emerald-800" }
            : confirmAction.kind === "NO_SHOW"
              ? { eyebrow: localize("Không đến", "No-show"), title: localize("Đánh dấu khách không đến?", "Mark guest as no-show?"), description: localize(`Đơn ${reservation?.reservationCode} sẽ được xử lý theo chính sách no-show và không thể check-in.`, `Reservation ${reservation?.reservationCode} will follow the no-show policy and cannot be checked in.`), action: localize("Xác nhận không đến", "Confirm no-show"), tone: "bg-orange-700 hover:bg-orange-800" }
              : confirmAction.kind === "CASH_REFUND"
                ? { eyebrow: localize("Hoàn tiền mặt", "Cash refund"), title: localize("Xác nhận đã giao tiền mặt cho khách?", "Confirm the cash handover?"), description: localize(`Chỉ xác nhận sau khi đã giao trực tiếp ${formatVND(refund?.amount || 0)} cho khách. Không cần ảnh minh chứng hoặc mã giao dịch. Nếu đóng cửa sổ này, refund và reservation vẫn giữ trạng thái chờ.`, `Confirm only after ${formatVND(refund?.amount || 0)} has been handed directly to the guest. No proof image or transaction code is required. Closing this dialog keeps both the refund and reservation pending.`), action: localize("Xác nhận đã giao tiền mặt", "Confirm cash handover"), tone: "bg-emerald-700 hover:bg-emerald-800" }
                : confirmAction.kind === "REFUND_PROVIDER"
                  ? { eyebrow: localize("Đối soát cũ", "Legacy reconciliation"), title: refund?.canRetry ? localize("Gửi lại yêu cầu hoàn?", "Retry refund request?") : localize("Đối soát khoản hoàn?", "Reconcile refund?"), description: localize(`Khoản ${formatVND(refund?.amount || 0)} của đơn ${refund?.reservationCode || refund?.bookingId} thuộc dữ liệu giao dịch cũ và sẽ được xử lý theo cơ chế tương thích.`, `The ${formatVND(refund?.amount || 0)} refund for reservation ${refund?.reservationCode || refund?.bookingId} belongs to legacy transaction data and will use the compatibility workflow.`), action: localize("Tiếp tục", "Continue"), tone: "bg-[#0F2A43] hover:bg-[#091E30]" }
                  : confirmAction.decision === "reject"
                    ? { eyebrow: localize("Yêu cầu hủy", "Cancellation request"), title: localize("Từ chối yêu cầu hủy?", "Reject cancellation request?"), description: localize(`Đơn ${reservation?.reservationCode} sẽ giữ nguyên và quyết định được lưu audit.`, `Reservation ${reservation?.reservationCode} remains active and the decision is audited.`), action: localize("Từ chối hủy", "Reject cancellation"), tone: "bg-[#0F2A43] hover:bg-[#091E30]" }
                    : { eyebrow: localize("Yêu cầu hủy", "Cancellation request"), title: localize("Hủy không hoàn tiền?", "Cancel without refund?"), description: localize(`Đơn ${reservation?.reservationCode} sẽ bị hủy và số tiền đã trả được ghi nhận theo chính sách không hoàn.`, `Reservation ${reservation?.reservationCode} will be cancelled without creating a refund.`), action: localize("Hủy không hoàn", "Cancel without refund"), tone: "bg-rose-700 hover:bg-rose-800" };
          return (
            <>
              <header className="border-b border-[#0F2A43]/10 bg-[#FBFAF6] px-5 py-4 sm:px-6"><p className="text-xs font-bold uppercase tracking-[0.16em] text-[#80632F]">{copy.eyebrow}</p><h2 id="dashboard-confirm-title" className="mt-1 text-xl font-bold text-[#0F2A43]">{copy.title}</h2></header>
              <div className="px-5 py-5 sm:px-6"><p id="dashboard-confirm-description" className="text-sm leading-6 text-[#66727C]">{copy.description}</p></div>
              <footer className="flex flex-col-reverse gap-2 border-t border-[#0F2A43]/10 px-5 py-4 sm:flex-row sm:justify-end sm:px-6"><button type="button" disabled={isActionLoading} onClick={() => setConfirmAction(null)} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43] disabled:opacity-50">{localize("Quay lại", "Go back")}</button><button type="button" disabled={isActionLoading} onClick={() => void executeConfirmAction()} className={`min-h-11 rounded-lg px-5 text-sm font-bold text-white disabled:opacity-50 ${copy.tone}`}>{isActionLoading ? localize("Đang xử lý...", "Processing...") : copy.action}</button></footer>
            </>
          );
        })()}
      </ViewportModal>

      <ViewportModal
        open={isWalkInOpen}
        onClose={() => setIsWalkInOpen(false)}
        labelledBy="walk-in-modal-title"
        busy={isActionLoading}
        panelClassName="max-w-6xl"
        testId="walk-in-modal"
      >
          <form onSubmit={handleCreateWalkIn} className="flex min-h-0 flex-1 flex-col">
            <header className="bg-[#0F2A43] px-5 py-4 text-center text-white sm:px-6">
              <h2 id="walk-in-modal-title" className="font-serif text-2xl font-bold tracking-tight">{localize("Đặt phòng & nhận phòng khách vãng lai", "Walk-in reservation & check-in")}</h2>
              <p className="mt-1 text-[10px] font-bold uppercase tracking-[0.08em] text-[#F6C96B]">{localize("Hệ thống dùng thời gian hiện tại khi nhận và trả phòng", "The system uses the current time for check-in and check-out")}</p>
            </header>

            <div className="grid min-h-0 flex-1 overflow-y-auto lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)] lg:overflow-hidden">
              <div className="lux-scrollbar space-y-5 border-b border-[#D8DDE1] px-5 py-5 lg:overflow-y-auto lg:border-b-0 lg:border-r sm:px-6">
              <fieldset className="space-y-3">
                <legend className="w-full border-b border-[#0F2A43]/55 pb-2 text-[11px] font-bold uppercase tracking-wider text-[#66727C]">{localize("Thông tin khách đại diện", "Primary guest information")}</legend>
                <label className="grid gap-1.5 text-[10px] font-bold uppercase tracking-wider text-[#66727C]">
                  Họ tên khách hàng *
                  <input required minLength={2} maxLength={100} autoComplete="name" value={walkInForm.customerName} onChange={(e) => setWalkInForm({ ...walkInForm, customerName: e.target.value.slice(0, 100) })} placeholder="Nguyễn Văn A" className="rounded-xl border border-[#D8DDE1] px-3.5 py-2.5 text-sm font-medium normal-case outline-none transition focus:border-[#0F2A43] focus:ring-2 focus:ring-[#0F2A43]/10" />
                </label>
                <div className="grid gap-3 md:grid-cols-3">
                  <label className="grid gap-1.5 text-[10px] font-bold uppercase tracking-wider text-[#66727C]">Loại giấy tờ *
                    <select value={walkInForm.customerIdCardType} onChange={(e) => setWalkInForm({ ...walkInForm, customerIdCardType: e.target.value as CheckInGuestDraft["idCardType"] })} className="rounded-xl border border-[#D8DDE1] px-3.5 py-2.5 text-sm font-medium normal-case outline-none focus:border-[#0F2A43]">
                      <option value="CCCD">CCCD</option><option value="CMND">CMND</option><option value="PASSPORT">Hộ chiếu</option>
                    </select>
                  </label>
                  <label className="grid gap-1.5 text-[10px] font-bold uppercase tracking-wider text-[#66727C]">Số giấy tờ *
                    <input required minLength={4} maxLength={50} value={walkInForm.customerIdCard} onChange={(e) => setWalkInForm({ ...walkInForm, customerIdCard: e.target.value.slice(0, 50) })} placeholder="Nhập số giấy tờ" className="rounded-xl border border-[#D8DDE1] px-3.5 py-2.5 text-sm font-medium normal-case outline-none focus:border-[#0F2A43]" />
                  </label>
                  <label className="grid gap-1.5 text-[10px] font-bold uppercase tracking-wider text-[#66727C]">Số điện thoại *
                    <input required type="tel" inputMode="tel" maxLength={24} autoComplete="tel" value={walkInForm.customerPhone} onChange={(e) => setWalkInForm({ ...walkInForm, customerPhone: e.target.value.slice(0, 24) })} placeholder="0900000000" className="rounded-xl border border-[#D8DDE1] px-3.5 py-2.5 text-sm font-medium normal-case outline-none focus:border-[#0F2A43]" />
                  </label>
                </div>
                <label className="grid gap-1.5 text-[10px] font-bold uppercase tracking-wider text-[#66727C]">
                  Email (không bắt buộc)
                  <input type="email" maxLength={254} autoComplete="email" value={walkInForm.customerEmail} onChange={(e) => setWalkInForm({ ...walkInForm, customerEmail: e.target.value.slice(0, 254) })} placeholder="khach@example.com" className="rounded-xl border border-[#D8DDE1] px-3.5 py-2.5 text-sm font-medium normal-case outline-none transition focus:border-[#0F2A43] focus:ring-2 focus:ring-[#0F2A43]/10" />
                </label>
              </fieldset>

              <fieldset className="space-y-3">
                <legend className="w-full border-b border-[#0F2A43]/55 pb-2 text-[11px] font-bold uppercase tracking-wider text-[#66727C]">Thông tin lưu trú</legend>
                <div className="grid gap-3 md:grid-cols-2">
                  <label className="grid gap-1.5 text-[10px] font-bold uppercase tracking-wider text-[#66727C]">Số khách *
                    <input type="number" min="1" value={walkInForm.guestCount} onChange={(e) => setWalkInForm({ ...walkInForm, guestCount: e.target.value })} className="rounded-xl border border-[#D8DDE1] px-3.5 py-2.5 text-sm font-medium normal-case outline-none focus:border-[#0F2A43]" />
                  </label>
                  <div className="grid gap-1.5 text-[10px] font-bold uppercase tracking-wider text-[#66727C]">Nguồn đơn
                    <div className="rounded-xl border border-[#D8DDE1] bg-[#FBFAF6] px-3.5 py-2.5 text-center text-sm font-bold normal-case text-[#B0003A]">Walk-in</div>
                  </div>
                  <div className="grid gap-1.5 text-[10px] font-bold uppercase tracking-wider text-[#66727C]">Thời gian hiện tại
                    <div className="rounded-xl border border-[#D8DDE1] bg-[#FBFAF6] px-3.5 py-2.5 text-sm font-semibold normal-case tabular-nums text-[#0F2A43]">{walkInNow.toLocaleString("vi-VN")}</div>
                  </div>
                  <DateTimeField
                    tone="operations"
                    label={localize("Giờ dự kiến trả phòng *", "Expected check-out time *")}
                    value={walkInForm.checkOut}
                    min={formatDateTimeLocal(walkInNow)}
                    onValueChange={(value) => setWalkInForm({ ...walkInForm, checkOut: value })}
                  />
                </div>
              </fieldset>

              </div>
              <div className="lux-scrollbar px-5 py-5 lg:overflow-y-auto sm:px-6">

              <fieldset className="space-y-3">
                <legend className="w-full border-b border-[#0F2A43]/55 pb-2 text-[11px] font-bold uppercase tracking-wider text-[#66727C]">Thanh toán khi tạo walk-in</legend>
                <div className="grid gap-2 sm:grid-cols-3">
                  {([
                    ["NONE", "Chưa thu tiền", "Có thể thanh toán khi trả phòng"],
                    ["CASH", "Tiền mặt", "Ghi nhận thanh toán tại quầy"],
                    ["SEPAY", "SePay VietQR", "Quét QR và chờ ngân hàng xác nhận"],
                  ] as const).map(([value, title, description]) => (
                    <label key={value} className={`cursor-pointer rounded-xl border p-3 transition ${walkInPaymentMethod === value ? "border-[#0F2A43] bg-[#E5E9ED]" : "border-[#D8DDE1] hover:border-[#66727C]"}`}>
                      <input type="radio" name="walkInPayment" value={value} checked={walkInPaymentMethod === value} onChange={() => setWalkInPaymentMethod(value)} className="mr-2 accent-[#0F2A43]" />
                      <span className="text-sm font-bold text-[#0F2A43]">{title}</span>
                      <span className="mt-1 block pl-5 text-[10px] font-medium text-[#66727C]">{description}</span>
                    </label>
                  ))}
                </div>
              </fieldset>

              {selectedWalkInRoomTypes.length > 0 && <fieldset className="space-y-3 rounded-xl border border-orange-200 bg-orange-50/60 p-4">
                <legend className="px-2 text-[11px] font-bold uppercase tracking-wider text-orange-800">{localize("Ngoại lệ giá walk-in", "Walk-in price exception")}</legend>
                <p className="text-xs leading-5 text-orange-900">{localize("Giữ tắt để dùng giá hệ thống. Chỉ bật khi có phê duyệt nghiệp vụ; giá nhập là tổng giá lưu trú cho một phòng và bắt buộc ghi lý do.", "Leave off to use system pricing. Enable only for an approved exception; enter the total stay price per room and document the reason.")}</p>
                <div className="space-y-3">{selectedWalkInRoomTypes.map((roomType) => {
                  const draft = walkInPriceOverrides[roomType.roomTypeId] || { enabled: false, amount: "", reasonCode: "APPROVED_WALK_IN_RATE", note: "" };
                  const updateDraft = (value: Partial<WalkInPriceOverrideDraft>) => setWalkInPriceOverrides((current) => ({ ...current, [roomType.roomTypeId]: { ...draft, ...value } }));
                  return <div key={roomType.roomTypeId} className="rounded-lg border border-orange-200 bg-white p-3">
                    <div className="flex flex-wrap items-center justify-between gap-3"><div><p className="text-sm font-bold text-[#0F2A43]">{localize(roomType.name, roomType.nameEn)} × {roomType.quantity}</p><p className="text-[11px] text-[#66727C]">{localize("Giá giờ đầu tham khảo", "Base first-hour reference")}: {formatVND(roomType.basePrice || 0)}</p></div><label className="flex cursor-pointer items-center gap-2 text-xs font-bold text-orange-800"><input type="checkbox" checked={draft.enabled} onChange={(event) => updateDraft({ enabled: event.target.checked })} className="h-4 w-4 accent-orange-700" />{localize("Nhập giá khác", "Override")}</label></div>
                    {draft.enabled && <div className="mt-3 grid gap-3 sm:grid-cols-2"><label className="grid gap-1 text-[10px] font-bold uppercase text-[#66727C]">{localize("Giá lưu trú / phòng (VND)", "Stay price / room (VND)")} *<input inputMode="numeric" value={draft.amount} onChange={(event) => updateDraft({ amount: event.target.value.replace(/[^0-9]/g, "") })} className="min-h-10 rounded-lg border px-3 text-right text-sm font-bold normal-case" /></label><label className="grid gap-1 text-[10px] font-bold uppercase text-[#66727C]">{localize("Loại phê duyệt", "Approval type")} *<select value={draft.reasonCode} onChange={(event) => updateDraft({ reasonCode: event.target.value })} className="min-h-10 rounded-lg border bg-white px-3 text-sm normal-case"><option value="APPROVED_WALK_IN_RATE">{localize("Giá walk-in đã duyệt", "Approved walk-in rate")}</option><option value="SERVICE_RECOVERY">{localize("Bù trải nghiệm dịch vụ", "Service recovery")}</option><option value="CONTRACTED_RATE">{localize("Giá hợp đồng", "Contracted rate")}</option></select></label><label className="grid gap-1 text-[10px] font-bold uppercase text-[#66727C] sm:col-span-2">{localize("Căn cứ / người phê duyệt", "Basis / approver")} *<textarea rows={2} maxLength={500} value={draft.note} onChange={(event) => updateDraft({ note: event.target.value })} className="resize-none rounded-lg border px-3 py-2 text-sm normal-case" /></label></div>}
                  </div>;
                })}</div>
              </fieldset>}

              <fieldset className="space-y-3">
                <legend className="w-full border-b border-[#0F2A43]/55 pb-2 text-[11px] font-bold uppercase tracking-wider text-[#66727C]">Chọn phòng trống sạch *</legend>
                {walkInRooms.length === 0 ? <p className="rounded-xl bg-[#FBFAF6] p-4 text-center text-sm font-medium text-[#66727C]">Không có phòng trống sạch phù hợp.</p> : (
                  <div className="grid gap-2 md:grid-cols-2">
                    {walkInRooms.map((room) => {
                      const selected = selectedWalkInRoomIds.includes(room.id);
                      return <label key={room.id} className={`flex cursor-pointer items-center gap-3 rounded-xl border px-3 py-2.5 transition ${selected ? "border-[#0F2A43] bg-[#E5E9ED]" : "border-[#D8DDE1] bg-white hover:border-[#66727C]"}`}>
                        <input type="checkbox" checked={selected} onChange={() => toggleWalkInRoom(room.id)} className="h-4 w-4 accent-[#0F2A43]" />
                        <span><span className="block text-sm font-bold text-[#0F2A43]">{localize("Phòng", "Room")} #{room.roomName}</span><span className="block text-[10px] font-medium text-[#66727C]">{localize(room.roomTypeName, room.roomTypeNameEn)}</span></span>
                      </label>;
                    })}
                  </div>
                )}
                <div className="space-y-3">
                  {walkInRooms.filter((room) => selectedWalkInRoomIds.includes(room.id)).map((room) => (
                    <div key={`guests-${room.id}`} className="rounded-xl border border-[#D8DDE1] bg-[#FBFAF6] p-3">
                      <div className="mb-3 flex items-center justify-between"><p className="text-sm font-bold text-[#0F2A43]">Khách phòng #{room.roomName}</p><button type="button" onClick={() => addWalkInGuest(room.id)} className="text-xs font-bold text-[#80632F]">+ Thêm khách</button></div>
                      <div className="space-y-2">
                        {(walkInGuestsByRoom[room.id] || []).map((guest, guestIndex) => (
                          <div key={guestIndex} className="grid gap-2 rounded-lg border border-[#D8DDE1] bg-white p-2 md:grid-cols-5">
                            <input value={guest.fullName} onChange={(e) => updateWalkInGuest(room.id, guestIndex, { fullName: e.target.value })} placeholder="Họ tên *" className="rounded-lg border px-2 py-2 text-xs md:col-span-2" />
                            <input value={guest.phone} onChange={(e) => updateWalkInGuest(room.id, guestIndex, { phone: e.target.value })} placeholder="Điện thoại" className="rounded-lg border px-2 py-2 text-xs" />
                            <input value={guest.idCardNumber} onChange={(e) => updateWalkInGuest(room.id, guestIndex, { idCardNumber: e.target.value })} placeholder="Số giấy tờ" className="rounded-lg border px-2 py-2 text-xs" />
                            <div className="flex items-center justify-between gap-2"><label className="text-[10px] font-bold"><input type="radio" name={`primary-${room.id}`} checked={guest.isPrimary} onChange={() => setWalkInGuestsByRoom((current) => ({ ...current, [room.id]: current[room.id].map((item, index) => ({ ...item, isPrimary: index === guestIndex })) }))} className="mr-1" />Đại diện</label>{(walkInGuestsByRoom[room.id] || []).length > 1 && <button type="button" onClick={() => removeWalkInGuest(room.id, guestIndex)} className="text-xs font-bold text-rose-700">Xóa</button>}</div>
                          </div>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </fieldset>
              </div>
            </div>

            <footer className="flex items-center justify-end gap-3 border-t border-[#D8DDE1] bg-[#FBFAF6] px-6 py-4">
              <button type="button" onClick={() => setIsWalkInOpen(false)} className="rounded-xl border border-[#0F2A43] bg-white px-5 py-2.5 text-xs font-bold uppercase transition hover:bg-[#F1F0EA] active:scale-[0.98]">Hủy</button>
              <button disabled={isActionLoading || selectedWalkInRoomIds.length === 0} className="rounded-xl bg-[#0F2A43] px-5 py-2.5 text-xs font-bold uppercase text-white transition hover:bg-[#091E30] active:scale-[0.98] disabled:cursor-not-allowed disabled:bg-[#9AA4B5]">{isActionLoading ? "Đang xử lý..." : `Hoàn tất check-in (${selectedWalkInRoomIds.length} phòng)`}</button>
            </footer>
          </form>
      </ViewportModal>

      {selectedReservation && checkInDrafts.length > 0 && (
        <ViewportModal open onClose={closeReservationModal} labelledBy="check-in-modal-title" busy={isActionLoading} panelClassName={checkInModalWidth} testId="check-in-modal">
          <section className="lux-scrollbar relative min-h-0 flex-1 overflow-y-auto p-5">
            <button
              type="button"
              onClick={closeReservationModal}
              aria-label={localize("Đóng hộp thoại nhận phòng", "Close check-in modal")}
              className="absolute right-4 top-4 flex h-9 w-9 items-center justify-center rounded-full border border-[#0F2A43]/10 bg-[#F1F0EA] text-sm font-black text-[#66727C] shadow-sm hover:border-[#B8944F] hover:bg-white hover:text-[#0F2A43]"
            >
              x
            </button>
            <div className="mb-5 border-b border-[#0F2A43]/10 pb-5 pr-12">
              <div className="flex flex-wrap items-center gap-3">
                <h2 id="check-in-modal-title" className="font-serif text-2xl font-bold text-[#0F2A43]">{localize("Nhận phòng", "Check in")} {selectedReservation.reservationCode}</h2>
                <span className="rounded-full bg-[#E5E9ED] px-3 py-1 text-xs font-bold text-[#0F2A43]">{localize(`${checkInDrafts.length} phòng`, `${checkInDrafts.length} rooms`)}</span>
              </div>
              <p className="text-sm font-semibold text-[#66727C]">{localize("Chọn phòng thực tế; hệ thống sẽ đối chiếu đúng loại phòng trong đơn.", "Select a physical room; the system maps it to the correct reserved room type.")}</p>
            </div>
            <div className={`grid gap-4 ${checkInGridColumns}`}>
              {checkInDrafts.map((draft) => {
                const roomsForType = availableRooms.filter((room) => room.roomTypeId === draft.roomTypeId);
                return (
                  <div key={draft.key} className="rounded-[1.25rem] border border-[#0F2A43]/10 bg-[#F1F0EA] p-4">
                    <p className="mb-3 text-xs font-bold uppercase tracking-wider text-[#80632F]">{localize(draft.roomTypeName, draft.roomTypeNameEn)}</p>
                    <div className="grid gap-3 md:grid-cols-2">
                      <select value={draft.roomId} onChange={(e) => updateDraft(draft.key, { roomId: e.target.value })} className="rounded-xl border border-[#0F2A43]/10 px-3 py-2.5 text-sm outline-none focus:border-[#B8944F] md:col-span-2">
                        <option value="">{localize("Chọn phòng còn trống", "Select available room")}</option>
                        {roomsForType.map((room) => (
                          <option key={room.id} value={room.id}>#{room.roomName} - {{ CLEAN: localize("Sạch", "Clean"), DIRTY: localize("Bẩn", "Dirty"), IN_PROGRESS: localize("Đang dọn", "Cleaning") }[room.cleaningStatus] || room.cleaningStatus}</option>
                        ))}
                      </select>
                      <div className="space-y-2 md:col-span-2">
                        {draft.guests.map((guest, guestIndex) => (
                          <div key={guestIndex} className="grid gap-2 rounded-xl border border-[#0F2A43]/10 bg-white p-3 md:grid-cols-2">
                            <input required minLength={2} maxLength={100} value={guest.fullName} onChange={(e) => updateGuestDraft(draft.key, guestIndex, { fullName: e.target.value.slice(0, 100) })} placeholder={localize("Họ tên khách *", "Guest full name *")} className="rounded-lg border px-3 py-2 text-sm" />
                            <input type="tel" inputMode="tel" maxLength={24} value={guest.phone} onChange={(e) => updateGuestDraft(draft.key, guestIndex, { phone: e.target.value.slice(0, 24) })} placeholder={localize("Số điện thoại", "Phone")} className="rounded-lg border px-3 py-2 text-sm" />
                            <input type="email" maxLength={254} value={guest.email} onChange={(e) => updateGuestDraft(draft.key, guestIndex, { email: e.target.value.slice(0, 254) })} placeholder={localize("Email (không bắt buộc)", "Email (optional)")} className="rounded-lg border px-3 py-2 text-sm" />
                            <input maxLength={50} value={guest.idCardNumber} onChange={(e) => updateGuestDraft(draft.key, guestIndex, { idCardNumber: e.target.value.slice(0, 50) })} placeholder={localize("Số giấy tờ (không bắt buộc)", "ID number (optional)")} className="rounded-lg border px-3 py-2 text-sm" />
                            <label className="text-xs font-bold"><input type="radio" name={`online-primary-${draft.key}`} checked={guest.isPrimary} onChange={() => setCheckInDrafts((current) => current.map((item) => item.key === draft.key ? { ...item, guests: item.guests.map((value, index) => ({ ...value, isPrimary: index === guestIndex })) } : item))} className="mr-2" />{localize("Khách chính", "Primary guest")}</label>
                            {draft.guests.length > 1 && <button type="button" onClick={() => removeGuestDraft(draft.key, guestIndex)} className="justify-self-end text-xs font-bold text-rose-700">{localize("Xóa", "Remove")}</button>}
                          </div>
                        ))}
                        <button type="button" onClick={() => addGuestDraft(draft.key)} className="text-xs font-bold text-[#80632F]">+ {localize("Thêm khách vào phòng này", "Add guest to this room")}</button>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
            <div className="mt-5 flex justify-end">
              <button disabled={isActionLoading} onClick={handleSubmitCheckIn} className="rounded-xl bg-[#0F2A43] px-5 py-3 text-xs font-bold uppercase tracking-wider text-white shadow-sm hover:bg-[#091E30] hover:shadow-md active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-60">
                {localize("Xác nhận nhận phòng", "Submit check-in")}
              </button>
            </div>
          </section>
        </ViewportModal>
      )}

      {selectedReservation && finalPayment && (
        <ViewportModal open onClose={closeReservationModal} labelledBy="checkout-reconciliation-title" busy={isActionLoading} panelClassName="max-w-3xl" testId="checkout-modal">
          <section className="lux-scrollbar relative min-h-0 flex-1 overflow-y-auto">
            <button
              type="button"
              onClick={closeReservationModal}
              aria-label={localize("Đóng hộp thoại đối soát", "Close reconciliation modal")}
              className="absolute right-4 top-4 flex h-9 w-9 items-center justify-center rounded-full border border-[#0F2A43]/10 bg-[#F1F0EA] text-sm font-black text-[#66727C] shadow-sm hover:border-[#B8944F] hover:bg-white hover:text-[#0F2A43]"
            >
              x
            </button>
            <div className="border-b border-[#0F2A43]/10 px-6 py-5 pr-16">
              <p className="text-xs font-bold uppercase tracking-[0.14em] text-[#66727C]">{localize("Đối soát trả phòng", "Checkout reconciliation")}</p>
              <h2 id="checkout-reconciliation-title" className="mt-1 text-2xl font-bold text-[#0F2A43]">{localize("Đơn đặt phòng", "Reservation")} {selectedReservation.reservationCode}</h2>
              <p className="mt-1 text-sm text-[#66727C]">{localize("Kiểm tra tiền phòng, phụ phí và khoản đã thanh toán trước khi trả phòng.", "Review room charges, fees and payments before checkout.")}</p>
            </div>
            <div className={`mx-6 mt-5 rounded-xl border p-4 ${finalPayment.reconciliationStatus === "MATCHED" ? "border-emerald-200 bg-emerald-50" : "border-amber-300 bg-amber-50"}`}>
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <p className={`text-xs font-black uppercase tracking-wider ${finalPayment.reconciliationStatus === "MATCHED" ? "text-emerald-700" : "text-amber-800"}`}>{finalPayment.reconciliationStatus === "MATCHED" ? localize("Đối soát đã khớp", "Reconciliation matched") : localize("Đối soát đang lệch", "Reconciliation mismatch")}</p>
                  <p className="mt-1 text-lg font-bold tabular-nums text-[#0F2A43]">{localize("Đã thu", "Collected")}: {formatVND(finalPayment.paidAmount)} <span className="px-1 text-[#A39B92]">/</span> {localize("Cần thu", "Required")}: {formatVND(finalPayment.totalAmount)}</p>
                </div>
                <span className={`rounded-full px-3 py-1 text-xs font-bold ${finalPayment.reconciliationStatus === "MATCHED" ? "bg-emerald-700 text-white" : "bg-amber-700 text-white"}`}>{finalPayment.reconciliationStatus}</span>
              </div>
              {finalPayment.blockingReasons.length > 0 && <ul className="mt-3 list-disc space-y-1 pl-5 text-xs font-medium text-amber-900">{finalPayment.blockingReasons.map((reason) => <li key={reason}>{reason}</li>)}</ul>}
            </div>
            <div className="grid gap-5 p-6 md:grid-cols-[1.15fr_0.85fr]">
              <div className="rounded-xl border border-[#0F2A43]/10 bg-[#F1F0EA] p-5">
                <h3 className="text-sm font-bold text-[#0F2A43]">{localize("Chi tiết chi phí", "Charge details")}</h3>
                <div className="mt-4 space-y-3 text-sm tabular-nums">
                  <div className="flex justify-between gap-4"><span className="text-[#66727C]">{localize("Tiền phòng dự kiến", "Planned room charge")}</span><span className="font-semibold">{formatVND(finalPayment.plannedRoomCharge)}</span></div>
                  {(finalPayment.earlyCheckoutAdjustment || 0) > 0 && <div className="flex justify-between gap-4 text-blue-700"><span>{localize("Giảm do trả phòng sớm", "Early checkout adjustment")}</span><span className="font-semibold">− {formatVND(finalPayment.earlyCheckoutAdjustment)}</span></div>}
                  <div className="flex justify-between gap-4 border-t border-[#0F2A43]/10 pt-3"><span className="font-semibold text-[#0F2A43]">{localize("Tiền phòng thực tế", "Actual room charge")}</span><span className="font-bold text-[#0F2A43]">{formatVND(finalPayment.roomCharge)}</span></div>
                  <div className="flex justify-between gap-4"><span className="text-[#66727C]">{localize("Phụ phí trả muộn", "Late checkout fee")}</span><span className="font-semibold">+ {formatVND(finalPayment.lateCheckoutFee || 0)}</span></div>
                  <div className="flex justify-between gap-4"><span className="text-[#66727C]">{localize("Phụ phí khác", "Additional fee")}</span><span className="font-semibold">+ {formatVND(finalPayment.checkoutAdditionalFee || 0)}</span></div>
                  <div className="flex justify-between gap-4 border-t-2 border-[#0F2A43]/15 pt-4"><span className="font-bold text-[#0F2A43]">{localize("Tổng phải trả", "Total due")}</span><span className="text-xl font-extrabold text-[#0F2A43]">{formatVND(finalPayment.totalAmount)}</span></div>
                </div>
              </div>
              <div className="space-y-4">
                <div className="rounded-xl border border-[#0F2A43]/10 p-5"><p className="text-sm text-[#66727C]">{localize("Khách đã thanh toán", "Guest paid")}</p><p className="mt-1 text-2xl font-extrabold tabular-nums text-[#0F2A43]">{formatVND(finalPayment.paidAmount)}</p></div>
                <div className={`rounded-xl border p-5 ${finalPayment.remainingAmount > 0 ? "border-rose-200 bg-rose-50" : (finalPayment.refundableAmount || 0) > 0 ? "border-emerald-200 bg-emerald-50" : "border-emerald-200 bg-emerald-50"}`}>
                  <p className={`text-sm font-bold ${finalPayment.remainingAmount > 0 ? "text-rose-700" : "text-emerald-700"}`}>{finalPayment.remainingAmount > 0 ? localize("Khách cần thanh toán thêm", "Additional payment required") : (finalPayment.refundableAmount || 0) > 0 ? localize("Khách được hoàn lại", "Refund due to guest") : localize("Đã thanh toán đủ", "Paid in full")}</p>
                  <p className={`mt-1 text-3xl font-extrabold tabular-nums ${finalPayment.remainingAmount > 0 ? "text-rose-700" : "text-emerald-700"}`}>{formatVND(finalPayment.remainingAmount > 0 ? finalPayment.remainingAmount : finalPayment.refundableAmount || 0)}</p>
                  <p className="mt-2 text-xs leading-5 text-[#66727C]">{finalPayment.remainingAmount > 0 ? localize("Thu đủ khoản còn thiếu trước khi trả phòng.", "Collect the remaining balance before checkout.") : (finalPayment.refundableAmount || 0) > 0 ? localize("Xử lý hoàn tiền trước khi trả phòng.", "Process the refund before checkout.") : localize("Không còn khoản phải thu hoặc phải hoàn.", "No payment or refund remains.")}</p>
                </div>
                {finalPayment.reservedRefundAmount > 0 && <div className="rounded-xl border border-sky-200 bg-sky-50 p-4 text-sm text-sky-900"><p className="font-bold">{localize("Đang chờ hoàn tiền", "Refund pending")}: {formatVND(finalPayment.reservedRefundAmount)}</p><p className="mt-1 text-xs">{localize("Chỉ checkout sau khi refund được xác nhận COMPLETED.", "Checkout is allowed only after the refund reaches COMPLETED.")}</p></div>}
              </div>
            </div>
            <div className="flex flex-wrap justify-end gap-3 border-t border-[#0F2A43]/10 px-6 py-4">
              <button disabled={isActionLoading} onClick={openCheckoutAdditionalFee} className="min-h-11 rounded-lg border border-orange-300 bg-orange-50 px-5 py-3 text-xs font-bold uppercase tracking-wider text-orange-800 hover:bg-orange-100 disabled:opacity-50">{localize("Thêm / sửa phụ phí", "Add / edit fee")}</button>
              {(finalPayment.refundableAmount || 0) > 0 && (
                <button disabled={isActionLoading} onClick={handleCheckoutRefund} className="min-h-11 rounded-lg bg-emerald-700 px-5 py-3 text-xs font-bold uppercase tracking-wider text-white shadow-sm hover:bg-emerald-800 disabled:opacity-50">{localize("Hoàn tiền", "Refund")}</button>
              )}
              {finalPayment.remainingAmount > 0 && (
                <>
                  <button disabled={isActionLoading} onClick={handleCashPayment} className="min-h-11 rounded-lg bg-[#B8944F] px-5 text-xs font-bold uppercase tracking-wider text-[#0F2A43] disabled:opacity-60">{localize("Thu tiền mặt", "Cash payment")}</button>
                  <button disabled={isActionLoading} onClick={handleOnlinePayment} className="min-h-11 rounded-lg border border-[#0F2A43] bg-white px-5 text-xs font-bold uppercase tracking-wider text-[#0F2A43] hover:bg-[#0F2A43] hover:text-white disabled:opacity-60">{localize("Thanh toán SePay VietQR", "SePay VietQR payment")}</button>
                </>
              )}
              {finalPayment.reconciliationStatus === "MATCHED" ? <button disabled={isActionLoading} onClick={handleCheckOut} className="rounded-xl bg-rose-700 px-5 py-3 text-xs font-bold uppercase tracking-wider text-white shadow-sm hover:bg-rose-800 hover:shadow-md active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-50">{localize("Hoàn tất trả phòng", "Complete checkout")}</button> : <button disabled={isActionLoading} onClick={openCheckoutEscalation} className="rounded-xl border border-amber-700 bg-amber-50 px-5 py-3 text-xs font-bold uppercase tracking-wider text-amber-900 hover:bg-amber-100 disabled:opacity-50">{localize("Yêu cầu ADMIN xử lý lỗi", "Request ADMIN review")}</button>}
            </div>
          </section>
        </ViewportModal>
      )}

      {isRefundFeeOpen && selectedReservation && finalPayment && (
        <ViewportModal open onClose={() => setIsRefundFeeOpen(false)} labelledBy="refund-fee-title" busy={isActionLoading} panelClassName="max-w-lg" testId="refund-fee-modal">
          <section className="flex min-h-0 flex-1 flex-col">
            <header className="border-b border-[#0F2A43]/10 px-6 py-5">
              <p className="text-xs font-bold uppercase tracking-[0.16em] text-orange-700">{localize("Đối soát trước trả phòng", "Pre-checkout reconciliation")}</p>
              <h2 id="refund-fee-title" className="mt-1 text-xl font-bold text-[#0F2A43]">{localize("Thêm hoặc sửa phụ phí", "Add or edit fee")}</h2>
              <p className="mt-1 text-sm text-[#66727C]">{localize("Đơn", "Reservation")} {selectedReservation.reservationCode}. {localize("Phụ phí do Admin/Staff xác nhận sẽ được cộng vào tổng thực tế.", "Fees confirmed by Admin/Staff are added to the actual total.")}</p>
            </header>

            <div className="lux-scrollbar min-h-0 flex-1 space-y-5 overflow-y-auto px-6 py-5">
              <label className="block text-sm font-semibold text-[#0F2A43]">
                {localize("Phụ phí phát sinh", "Additional fee")} (VND)
                <div className="relative mt-2">
                  <input
                    autoFocus
                    inputMode="numeric"
                    value={refundAdditionalFee}
                    onChange={(event) => {
                      setRefundAdditionalFee(event.target.value.replace(/[^0-9]/g, ""));
                      setRefundFeeError("");
                    }}
                    className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-4 pr-14 text-right text-lg font-bold tabular-nums outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
                    aria-invalid={Boolean(refundFeeError)}
                    aria-describedby="refund-fee-help"
                  />
                  <span className="pointer-events-none absolute right-4 top-1/2 -translate-y-1/2 text-sm font-semibold text-[#66727C]">đ</span>
                </div>
                <span id="refund-fee-help" className="mt-1.5 block text-xs font-normal text-[#66727C]">{localize("Nhập 0 nếu không có phụ phí bổ sung.", "Enter 0 when there is no additional fee.")}</span>
              </label>

              <label className="block text-sm font-semibold text-[#0F2A43]">
                {localize("Loại điều chỉnh", "Adjustment reason")} *
                <select value={refundFeeReasonCode} onChange={(event) => { setRefundFeeReasonCode(event.target.value); setRefundFeeError(""); }} className="mt-2 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm outline-none focus:border-[#B8944F]">
                  <option value="LATE_SERVICE_CHARGE">{localize("Phụ phí dịch vụ phát sinh", "Additional service charge")}</option>
                  <option value="DAMAGE_CHARGE">{localize("Phí hư hỏng / thất lạc", "Damage or loss charge")}</option>
                  <option value="CORRECT_WRONG_FEE">{localize("Sửa phụ phí nhập sai", "Correct an incorrect fee")}</option>
                  <option value="OTHER_DOCUMENTED_FEE">{localize("Khoản phí khác có chứng từ", "Other documented fee")}</option>
                </select>
              </label>
              <label className="block text-sm font-semibold text-[#0F2A43]">
                {localize("Ghi chú điều chỉnh", "Adjustment note")} *
                <textarea rows={3} maxLength={500} value={refundFeeReason} onChange={(event) => { setRefundFeeReason(event.target.value); setRefundFeeError(""); }} placeholder={localize("Mô tả khoản phí, căn cứ và người xác nhận", "Describe the charge, evidence, and approver")} className="mt-2 w-full resize-none rounded-lg border border-[#0F2A43]/15 px-3 py-2 text-sm outline-none focus:border-[#B8944F]" />
              </label>

              {(() => {
                const nextTotal = finalPayment.totalAmount - (finalPayment.checkoutAdditionalFee || 0) + Number(refundAdditionalFee || 0);
                const difference = finalPayment.paidAmount - nextTotal;
                return <div className="grid grid-cols-2 gap-3 rounded-xl bg-[#F1F0EA] p-4">
                  <div><p className="text-xs text-[#66727C]">Tổng sau phụ phí</p><p className="mt-1 font-bold tabular-nums text-[#0F2A43]">{formatVND(nextTotal)}</p></div>
                  <div className="text-right"><p className="text-xs text-[#66727C]">Kết quả dự kiến</p><p className={`mt-1 font-bold tabular-nums ${difference > 0 ? "text-emerald-700" : difference < 0 ? "text-rose-700" : "text-[#0F2A43]"}`}>{difference > 0 ? `Hoàn ${formatVND(difference)}` : difference < 0 ? `Thu thêm ${formatVND(-difference)}` : "Đã cân bằng"}</p></div>
                </div>;
              })()}

              {refundFeeError && <p className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm font-medium text-rose-700" role="alert">{refundFeeError}</p>}
            </div>

            <footer className="flex flex-col-reverse gap-3 border-t border-[#0F2A43]/10 px-6 py-4 sm:flex-row sm:justify-end">
              <button type="button" disabled={isActionLoading} onClick={() => setIsRefundFeeOpen(false)} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43] hover:bg-[#F1F0EA] disabled:opacity-50">{localize("Hủy", "Cancel")}</button>
              <button type="button" disabled={isActionLoading} onClick={handleCheckoutAdditionalFee} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white hover:bg-[#091E30] disabled:opacity-50">{isActionLoading ? localize("Đang xử lý...", "Processing...") : localize("Xác nhận đối soát", "Confirm reconciliation")}</button>
            </footer>
          </section>
        </ViewportModal>
      )}

      {isCheckoutEscalationOpen && selectedReservation && finalPayment && (
        <ViewportModal open onClose={() => setIsCheckoutEscalationOpen(false)} labelledBy="checkout-escalation-title" busy={isActionLoading} panelClassName="max-w-lg" testId="checkout-escalation-modal">
          <section className="flex min-h-0 flex-1 flex-col">
            <header className="border-b border-[#0F2A43]/10 bg-amber-50 px-6 py-5">
              <p className="text-xs font-bold uppercase tracking-[0.16em] text-amber-800">{localize("Ngoại lệ kỹ thuật", "Technical exception")}</p>
              <h2 id="checkout-escalation-title" className="mt-1 text-xl font-bold text-[#0F2A43]">{localize("Yêu cầu ADMIN kiểm tra đối soát", "Request ADMIN reconciliation review")}</h2>
              <p className="mt-1 text-sm leading-5 text-[#66727C]">{localize("Yêu cầu này chỉ lưu snapshot và lý do. Reservation vẫn CHECKED_IN, không xóa công nợ và không tự checkout.", "This request stores only the snapshot and reason. The reservation remains CHECKED_IN; no debt is waived and checkout is not triggered.")}</p>
            </header>
            <div className="lux-scrollbar min-h-0 flex-1 space-y-4 overflow-y-auto px-6 py-5">
              <div className="grid grid-cols-2 gap-3 rounded-xl bg-[#F1F0EA] p-4 text-sm"><div><p className="text-xs text-[#66727C]">{localize("Đã thu", "Collected")}</p><p className="mt-1 font-bold tabular-nums text-[#0F2A43]">{formatVND(finalPayment.paidAmount)}</p></div><div className="text-right"><p className="text-xs text-[#66727C]">{localize("Cần thu", "Required")}</p><p className="mt-1 font-bold tabular-nums text-[#0F2A43]">{formatVND(finalPayment.totalAmount)}</p></div></div>
              <label className="block text-sm font-semibold text-[#0F2A43]">{localize("Nhóm lỗi", "Issue type")} *<select autoFocus value={checkoutEscalationForm.reasonCode} onChange={(event) => setCheckoutEscalationForm((current) => ({ ...current, reasonCode: event.target.value }))} className="mt-2 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm outline-none focus:border-[#B8944F]"><option value="PROVIDER_EVENT_UNMATCHED">{localize("Event SePay chưa ghép đúng", "Unmatched SePay event")}</option><option value="LEDGER_DATA_INCONSISTENCY">{localize("Dữ liệu ledger không nhất quán", "Ledger data inconsistency")}</option><option value="MIGRATION_DATA_INCONSISTENCY">{localize("Sai lệch dữ liệu migration/legacy", "Migration or legacy inconsistency")}</option><option value="OTHER_TECHNICAL_EXCEPTION">{localize("Lỗi tích hợp khác", "Other integration issue")}</option></select></label>
              <label className="block text-sm font-semibold text-[#0F2A43]">{localize("Đã kiểm tra và phát hiện", "Investigation note")} *<textarea rows={4} maxLength={1000} value={checkoutEscalationForm.note} onChange={(event) => { setCheckoutEscalationForm((current) => ({ ...current, note: event.target.value })); setCheckoutEscalationError(""); }} placeholder={localize("Nêu event/payment/refund liên quan và các bước nghiệp vụ đã thử", "Describe related events/payments/refunds and normal fixes already attempted")} className="mt-2 w-full resize-none rounded-lg border border-[#0F2A43]/15 px-3 py-2 text-sm outline-none focus:border-[#B8944F]" /></label>
              {checkoutEscalationError && <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm font-semibold text-rose-700">{checkoutEscalationError}</p>}
            </div>
            <footer className="flex flex-col-reverse gap-3 border-t border-[#0F2A43]/10 px-6 py-4 sm:flex-row sm:justify-end"><button type="button" disabled={isActionLoading} onClick={() => setIsCheckoutEscalationOpen(false)} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43] disabled:opacity-50">{localize("Quay lại đối soát", "Back")}</button><button type="button" disabled={isActionLoading || !checkoutEscalationForm.note.trim()} onClick={() => void submitCheckoutEscalation()} className="min-h-11 rounded-lg bg-amber-700 px-5 text-sm font-bold text-white hover:bg-amber-800 disabled:opacity-50">{isActionLoading ? localize("Đang gửi...", "Sending...") : localize("Gửi cho ADMIN", "Send to ADMIN")}</button></footer>
          </section>
        </ViewportModal>
      )}

      {roomHoldReleaseTarget && (
        <ViewportModal open onClose={() => setRoomHoldReleaseTarget(null)} labelledBy="room-hold-release-title" busy={isActionLoading} panelClassName="max-w-lg" testId="room-hold-release-modal">
          <section className="flex min-h-0 flex-1 flex-col">
            <header className="border-b border-[#0F2A43]/10 px-6 py-5">
              <p className="text-xs font-bold uppercase tracking-[0.14em] text-orange-700">RoomHold</p>
              <h2 id="room-hold-release-title" className="mt-1 text-xl font-bold text-[#0F2A43]">{localize("Nhả giữ phòng thủ công", "Release room hold manually")}</h2>
              <p className="mt-1 text-sm text-[#66727C]">{roomHoldReleaseTarget.reservationCode} · {localize("chỉ áp dụng cho hold ACTIVE, chưa chuyển thành booking đã thanh toán.", "only applies to an ACTIVE hold that has not been converted by payment.")}</p>
            </header>
            <div className="lux-scrollbar min-h-0 flex-1 space-y-4 overflow-y-auto px-6 py-5">
              <label className="block text-sm font-semibold text-[#0F2A43]">{localize("Mã lý do", "Reason code")} *<select data-modal-autofocus value={roomHoldReleaseReasonCode} onChange={(event) => { setRoomHoldReleaseReasonCode(event.target.value); setRoomHoldReleaseError(""); }} className="mt-2 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm outline-none focus:border-[#B8944F]"><option value="CUSTOMER_ABANDONED_PAYMENT">Khách không tiếp tục thanh toán</option><option value="DUPLICATE_RESERVATION">Reservation tạo trùng</option><option value="INCORRECT_DATES_OR_ROOM_TYPE">Sai ngày hoặc hạng phòng</option><option value="OPERATIONAL_CORRECTION">Điều chỉnh vận hành có phê duyệt</option></select></label>
              <label className="block text-sm font-semibold text-[#0F2A43]">{localize("Ghi chú cụ thể", "Detailed note")} *<textarea rows={4} maxLength={500} value={roomHoldReleaseNote} onChange={(event) => { setRoomHoldReleaseNote(event.target.value); setRoomHoldReleaseError(""); }} placeholder={localize("Nêu căn cứ đã kiểm tra trước khi nhả phòng", "Describe what was checked before releasing inventory")} className="mt-2 w-full resize-none rounded-lg border border-[#0F2A43]/15 px-3 py-2 text-sm outline-none focus:border-[#B8944F]" /></label>
              <p className="rounded-lg bg-orange-50 p-3 text-xs leading-5 text-orange-900">{localize("Hệ thống sẽ kiểm tra lại trạng thái dưới transaction. Hold đã CONVERTED sẽ bị từ chối, không thể nhả bằng nút này.", "The backend revalidates the state in a transaction. A CONVERTED hold is rejected and cannot be released here.")}</p>
              {roomHoldReleaseError && <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm font-semibold text-rose-700">{roomHoldReleaseError}</p>}
            </div>
            <footer className="flex justify-end gap-3 border-t border-[#0F2A43]/10 px-6 py-4"><button type="button" disabled={isActionLoading} onClick={() => setRoomHoldReleaseTarget(null)} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43]">{localize("Đóng", "Close")}</button><button type="button" disabled={isActionLoading || !roomHoldReleaseNote.trim()} onClick={() => void submitRoomHoldRelease()} className="min-h-11 rounded-lg bg-orange-700 px-5 text-sm font-bold text-white disabled:opacity-50">{isActionLoading ? localize("Đang xử lý...", "Processing...") : localize("Xác nhận nhả hold", "Confirm release")}</button></footer>
          </section>
        </ViewportModal>
      )}

      {cancelTarget && (
        <ViewportModal open onClose={() => setCancelTarget(null)} labelledBy="staff-cancel-title" busy={isActionLoading} panelClassName="max-w-lg" testId="staff-cancel-modal">
          <section className="flex min-h-0 flex-1 flex-col">
            <header className="border-b border-[#0F2A43]/10 px-6 py-5">
              <p className="text-xs font-bold uppercase tracking-[0.14em] text-rose-700">{localize("Hành động của nhân viên / quản trị viên", "Staff / Admin action")}</p>
              <h2 id="staff-cancel-title" className="mt-1 text-xl font-bold text-[#0F2A43]">{localize("Hủy đơn", "Cancel reservation")} {cancelTarget.reservationCode}</h2>
              <p className="mt-1 text-sm text-[#66727C]">Chọn chính sách tiền trước khi xác nhận. Quyết định này được lưu cùng đơn hủy.</p>
            </header>
            <div className="lux-scrollbar min-h-0 flex-1 space-y-5 overflow-y-auto px-6 py-5">
              <label className="block text-sm font-semibold text-[#0F2A43]">Lý do hủy
                <textarea autoFocus rows={3} value={cancelReason} onChange={(event) => setCancelReason(event.target.value)} placeholder="Nhập lý do hủy đơn..." className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 px-4 py-3 text-sm font-normal outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20" />
              </label>
              <section className="rounded-xl border border-[#0F2A43]/10 bg-[#FBFAF6] p-4">
                <div className="grid gap-3 sm:grid-cols-3">
                  <div><p className="text-xs font-bold uppercase text-[#66727C]">Đã thu</p><p className="mt-1 font-bold tabular-nums text-[#0F2A43]">{formatVND(cancelTarget.paidAmount || 0)}</p></div>
                  <label className="text-xs font-bold uppercase text-[#66727C]">Tiền phạt
                    <input type="number" min={0} max={cancelTarget.paidAmount || 0} step={1000} value={cancellationPenaltyAmount} onChange={(event) => { const value = event.target.value.replace(/\D/g, ""); setCancellationPenaltyAmount(value); setCancellationPenaltyReasonCode(Number(value || 0) > 0 ? (cancellationPenaltyReasonCode === "NO_PENALTY" ? "LATE_CANCELLATION" : cancellationPenaltyReasonCode) : "NO_PENALTY"); setCancellationPenaltyError(""); }} className="mt-1 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm font-bold tabular-nums text-[#0F2A43] outline-none focus:border-[#B8944F]" />
                  </label>
                  <div><p className="text-xs font-bold uppercase text-[#66727C]">Sẽ hoàn</p><p className="mt-1 font-bold tabular-nums text-emerald-800">{formatVND(Math.max(0, (cancelTarget.paidAmount || 0) - (Number(cancellationPenaltyAmount) || 0)))}</p></div>
                </div>
                {Number(cancellationPenaltyAmount || 0) > 0 && (
                  <div className="mt-4 grid gap-3">
                    <label className="text-sm font-semibold text-[#0F2A43]">Loại chính sách phạt *<select value={cancellationPenaltyReasonCode} onChange={(event) => { setCancellationPenaltyReasonCode(event.target.value); setCancellationPenaltyError(""); }} className="mt-2 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm outline-none focus:border-[#B8944F]"><option value="LATE_CANCELLATION">Hủy sát ngày nhận phòng</option><option value="NO_SHOW_POLICY">Chính sách không đến</option><option value="PROPERTY_DAMAGE_OR_COST">Chi phí phát sinh đã xác minh</option><option value="MANUAL_POLICY_EXCEPTION">Ngoại lệ chính sách được phê duyệt</option></select></label>
                    <label className="text-sm font-semibold text-[#0F2A43]">Căn cứ áp dụng *<textarea rows={2} maxLength={500} value={cancellationPenaltyNote} onChange={(event) => { setCancellationPenaltyNote(event.target.value); setCancellationPenaltyError(""); }} placeholder="Nêu điều khoản hoặc căn cứ tính tiền phạt" className="mt-2 w-full resize-none rounded-lg border border-[#0F2A43]/15 bg-white px-3 py-2 text-sm outline-none focus:border-[#B8944F]" /></label>
                  </div>
                )}
              </section>
              {Math.max(0, (cancelTarget.paidAmount || 0) - (Number(cancellationPenaltyAmount) || 0)) > 0 && (
                <>
                  <RefundChannelSelector value={refundChannel} onChange={setRefundChannel} localize={localize} />
                  {refundChannel === "MANUAL_BANK_TRANSFER" && (
                    <div className="rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm leading-6 text-amber-950">
                      <p className="font-bold">{localize("Bước này chỉ gửi yêu cầu nhập tài khoản", "This step only requests bank details")}</p>
                      <p className="mt-1">{localize("Đơn chưa chuyển sang CANCELLED. Khách phải nhập ngân hàng, số tài khoản và họ tên chủ tài khoản trong Đơn của tôi; sau đó staff mở QR và chờ ngân hàng xác nhận tự động.", "The reservation does not move to CANCELLED. The customer must enter their bank, account number, and account holder name in My bookings; staff then opens the QR and waits for automatic bank confirmation.")}</p>
                    </div>
                  )}
                  {refundChannel === "CASH_AT_COUNTER" && (
                    <div className="rounded-xl border border-emerald-300 bg-emerald-50 p-4 text-sm leading-6 text-emerald-950">
                      <p className="font-bold">{localize("Bước này chỉ tạo khoản hoàn tiền mặt chờ xác nhận", "This step only creates a pending cash refund")}</p>
                      <p className="mt-1">{localize("Đơn chưa chuyển sang CANCELLED. Sau khi tạo, hệ thống mở bước riêng để staff xác nhận đã giao tiền trực tiếp; đóng modal mà chưa xác nhận sẽ giữ nguyên reservation.", "The reservation does not move to CANCELLED. A separate confirmation step opens after creation; closing it without confirmation keeps the reservation unchanged.")}</p>
                    </div>
                  )}
                </>
              )}
              {cancellationPenaltyError && <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm font-semibold text-rose-700">{cancellationPenaltyError}</p>}
            </div>
            <footer className="flex justify-end gap-3 border-t border-[#0F2A43]/10 px-6 py-4"><button disabled={isActionLoading} onClick={() => setCancelTarget(null)} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43]">{localize("Đóng", "Close")}</button><button disabled={isActionLoading || !cancelReason.trim()} onClick={handleStaffCancel} className="min-h-11 rounded-lg bg-rose-700 px-5 text-sm font-bold text-white disabled:opacity-50">{isActionLoading ? localize("Đang xử lý...", "Processing...") : Math.max(0, (cancelTarget.paidAmount || 0) - (Number(cancellationPenaltyAmount) || 0)) > 0 && refundChannel === "MANUAL_BANK_TRANSFER" ? localize("Duyệt hủy & tạo hoàn QR", "Approve & create QR refund") : Math.max(0, (cancelTarget.paidAmount || 0) - (Number(cancellationPenaltyAmount) || 0)) > 0 && refundChannel === "CASH_AT_COUNTER" ? localize("Duyệt hủy & tạo hoàn tiền mặt", "Approve & create cash refund") : localize("Xác nhận hủy và giữ tiền phạt", "Confirm cancellation and penalty")}</button></footer>
          </section>
        </ViewportModal>
      )}

      {refundMethodTarget && (
        <ViewportModal open onClose={() => setRefundMethodTarget(null)} labelledBy="refund-method-title" busy={isActionLoading} panelClassName="max-w-lg" testId="refund-method-modal">
          <section className="flex min-h-0 flex-1 flex-col">
            <header className="border-b border-[#0F2A43]/10 px-6 py-5">
              <p className="text-xs font-bold text-emerald-700">{localize("Xác nhận hoàn tiền", "Confirm refund")}</p>
              <h2 id="refund-method-title" className="mt-1 text-xl font-bold text-[#0F2A43]">{localize("Tạo quy trình hoàn tiền", "Create refund workflow")}</h2>
              <p className="mt-1 text-sm text-[#66727C]">{localize("Đơn", "Reservation")} {refundMethodTarget.reservation.reservationCode} · <span className="font-bold tabular-nums text-[#0F2A43]">{formatVND(refundMethodTarget.context === "CHECKOUT" ? finalPayment?.refundableAmount || 0 : refundMethodTarget.reservation.paidAmount || 0)}</span></p>
            </header>
            <div className="lux-scrollbar min-h-0 flex-1 overflow-y-auto px-6 py-5">
              {refundMethodTarget.context === "CANCELLATION" ? (
                <div className="space-y-4">
                  <section className="rounded-xl border border-[#0F2A43]/10 bg-[#FBFAF6] p-4">
                    <div className="grid gap-3 sm:grid-cols-3">
                      <div><p className="text-xs font-bold uppercase text-[#66727C]">Đã thu</p><p className="mt-1 font-bold tabular-nums text-[#0F2A43]">{formatVND(refundMethodTarget.reservation.paidAmount || 0)}</p></div>
                      <label className="text-xs font-bold uppercase text-[#66727C]">Tiền phạt
                        <input data-modal-autofocus type="number" min={0} max={refundMethodTarget.reservation.paidAmount || 0} step={1000} value={cancellationPenaltyAmount} onChange={(event) => { const value = event.target.value.replace(/\D/g, ""); setCancellationPenaltyAmount(value); setCancellationPenaltyReasonCode(Number(value || 0) > 0 ? (cancellationPenaltyReasonCode === "NO_PENALTY" ? "LATE_CANCELLATION" : cancellationPenaltyReasonCode) : "NO_PENALTY"); setCancellationPenaltyError(""); }} className="mt-1 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm font-bold tabular-nums text-[#0F2A43] outline-none focus:border-[#B8944F]" />
                      </label>
                      <div><p className="text-xs font-bold uppercase text-[#66727C]">Sẽ hoàn</p><p className="mt-1 font-bold tabular-nums text-emerald-800">{formatVND(Math.max(0, (refundMethodTarget.reservation.paidAmount || 0) - (Number(cancellationPenaltyAmount) || 0)))}</p></div>
                    </div>
                    {Number(cancellationPenaltyAmount || 0) > 0 && <div className="mt-4 grid gap-3"><label className="text-sm font-semibold text-[#0F2A43]">Loại chính sách phạt *<select value={cancellationPenaltyReasonCode} onChange={(event) => { setCancellationPenaltyReasonCode(event.target.value); setCancellationPenaltyError(""); }} className="mt-2 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm"><option value="LATE_CANCELLATION">Hủy sát ngày nhận phòng</option><option value="NO_SHOW_POLICY">Chính sách không đến</option><option value="MANUAL_POLICY_EXCEPTION">Ngoại lệ chính sách được phê duyệt</option></select></label><label className="text-sm font-semibold text-[#0F2A43]">Căn cứ áp dụng *<textarea rows={2} maxLength={500} value={cancellationPenaltyNote} onChange={(event) => { setCancellationPenaltyNote(event.target.value); setCancellationPenaltyError(""); }} className="mt-2 w-full resize-none rounded-lg border border-[#0F2A43]/15 bg-white px-3 py-2 text-sm" /></label></div>}
                  </section>
                  <div className="rounded-xl border border-sky-300 bg-sky-50 p-4 text-sm leading-6 text-sky-950">
                    <p className="font-bold">QR</p>
                    <p className="mt-1">{localize("Khách đã gửi ngân hàng, số tài khoản và họ tên khi tạo yêu cầu hủy online. Số tiền hoàn do backend lấy từ ledger rồi trừ tiền phạt; staff không được nhập số tiền hoàn trực tiếp.", "The guest supplied their bank details with the online cancellation request. The backend derives the refund from the ledger minus the penalty; staff cannot enter a refund amount directly.")}</p>
                  </div>
                  {cancellationPenaltyError && <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm font-semibold text-rose-700">{cancellationPenaltyError}</p>}
                </div>
              ) : (
                <RefundChannelSelector value={refundChannel} onChange={setRefundChannel} localize={localize} />
              )}
            </div>
            <footer className="flex flex-col-reverse gap-3 border-t border-[#0F2A43]/10 px-6 py-4 sm:flex-row sm:justify-end">
              <button type="button" disabled={isActionLoading} onClick={() => setRefundMethodTarget(null)} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43] hover:bg-[#F1F0EA] disabled:opacity-50">{localize("Quay lại", "Back")}</button>
              <button type="button" disabled={isActionLoading} onClick={() => void confirmRefundMethod()} className="min-h-11 rounded-lg bg-emerald-700 px-5 text-sm font-bold text-white hover:bg-emerald-800 disabled:opacity-50">{isActionLoading ? localize("Đang xử lý...", "Processing...") : localize("Xác nhận tạo hoàn tiền", "Confirm refund workflow")}</button>
            </footer>
          </section>
        </ViewportModal>
      )}

      {manualRefundTarget && (
        <ViewportModal open onClose={closeManualRefundDetails} labelledBy="manual-refund-title" busy={isActionLoading} panelClassName="max-w-2xl" zIndexClassName="z-[90]" testId="manual-refund-modal">
          <form onSubmit={completeManualRefund} className="flex min-h-0 flex-1 flex-col" noValidate>
            <header className="border-b border-[#0F2A43]/10 px-6 py-5">
              <p className="text-xs font-bold uppercase tracking-[0.14em] text-sky-700">{localize("Hoàn tiền QR", "QR refund")}</p>
              <h2 id="manual-refund-title" className="mt-1 text-xl font-bold text-[#0F2A43]">{localize("Chuyển đúng mã, chờ ngân hàng xác nhận", "Transfer with the exact code and await bank confirmation")}</h2>
              <p className="mt-1 text-sm text-[#66727C]">{manualRefundTarget.reservationCode || `${localize("Đơn", "Reservation")} #${manualRefundTarget.bookingId}`} · <span className="font-bold tabular-nums text-[#0F2A43]">{formatVND(manualRefundTarget.amount)}</span></p>
            </header>

            <div className="lux-scrollbar min-h-0 flex-1 space-y-5 overflow-y-auto px-6 py-5">
              {isManualDetailsLoading ? (
                <div role="status" aria-live="polite" className="rounded-xl bg-[#F1F0EA] p-5 text-sm font-semibold text-[#66727C]">{localize("Đang tải dữ liệu được bảo vệ...", "Loading protected details...")}</div>
              ) : manualRefundDetails ? (
                <>
                  <section className="rounded-xl border border-[#0F2A43]/10 bg-[#FBFAF6] p-4" aria-labelledby="manual-recipient-title">
                    <h3 id="manual-recipient-title" className="text-sm font-bold text-[#0F2A43]">{localize("Tài khoản khách nhận tiền", "Customer recipient account")}</h3>
                    <dl className="mt-3 grid gap-3 text-sm sm:grid-cols-2">
                      <div><dt className="text-xs font-semibold text-[#66727C]">{localize("Ngân hàng", "Bank")}</dt><dd className="mt-1 font-bold text-[#0F2A43]">{manualRefundDetails.bankName}</dd></div>
                      <div><dt className="text-xs font-semibold text-[#66727C]">{localize("Chủ tài khoản", "Account holder")}</dt><dd className="mt-1 font-bold uppercase text-[#0F2A43]">{manualRefundDetails.accountHolderName}</dd></div>
                      <div className="sm:col-span-2"><dt className="text-xs font-semibold text-[#66727C]">{localize("Số tài khoản", "Account number")}</dt><dd className="mt-1 break-all font-mono text-base font-bold tracking-wide text-[#0F2A43]">{manualRefundDetails.accountNumber}</dd></div>
                    </dl>
                    <div className="mt-4 grid gap-3 rounded-xl border border-[#B8944F]/30 bg-[#F7F1E4] p-4 sm:grid-cols-2">
                      <div><p className="text-[11px] font-bold uppercase tracking-wider text-[#80632F]">{localize("Số tiền phải chuyển", "Exact amount")}</p><p className="mt-1 text-xl font-bold tabular-nums text-[#0F2A43]">{formatVND(manualRefundDetails.expectedAmount || manualRefundDetails.amount)}</p></div>
                      <div><p className="text-[11px] font-bold uppercase tracking-wider text-[#80632F]">{localize("Mã hoàn tiền", "Refund code")}</p><p className="mt-1 break-all font-mono text-base font-black tracking-wide text-[#0F2A43]">{manualRefundDetails.refundCode}</p></div>
                    </div>
                    {manualRefundDetails.refundQrCodeUrl && !manualQrFailed && (
                      <div className="mt-4 flex flex-col items-center rounded-xl border border-sky-200 bg-white p-4 text-center">
                        {/* Provider-owned dynamic QR URLs are intentionally rendered without next/image host optimization. */}
                        <img
                          src={manualRefundDetails.refundQrCodeUrl}
                          alt={localize("QR chuyển khoản hoàn tiền", "Refund bank-transfer QR")}
                          width={220}
                          height={220}
                          loading="eager"
                          decoding="async"
                          onError={() => setManualQrFailed(true)}
                          className="h-[220px] w-[220px] object-contain"
                        />
                        <p className="mt-2 text-xs font-medium leading-5 text-sky-900">{localize("Quét QR bằng ứng dụng ngân hàng, giữ nguyên số tiền và nội dung để hệ thống tự đối chiếu.", "Scan with a banking app and keep the exact amount and memo so the system can reconcile automatically.")}</p>
                        {manualRefundDetails.transferContent && (
                          <p className="mt-2 rounded-lg bg-sky-50 px-3 py-2 font-mono text-xs font-bold text-sky-950">{localize("Nội dung", "Transfer content")}: {manualRefundDetails.transferContent}</p>
                        )}
                      </div>
                    )}
                    {manualRefundDetails.refundQrCodeUrl && manualQrFailed && (
                      <div className="mt-4 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950" role="alert">
                        <p className="font-bold">{localize("Không tải được ảnh QR trong trang.", "The QR image could not be loaded inline.")}</p>
                        <a href={manualRefundDetails.refundQrCodeUrl} target="_blank" rel="noreferrer" className="mt-2 inline-flex min-h-11 items-center font-bold underline underline-offset-4">{localize("Mở QR ở cửa sổ mới", "Open QR in a new window")}</a>
                      </div>
                    )}
                  </section>

                  <section className={`rounded-xl border p-4 ${payoutConfig?.configured ? "border-emerald-200 bg-emerald-50" : "border-rose-200 bg-rose-50"}`} aria-labelledby="payout-source-title">
                    <h3 id="payout-source-title" className={`text-sm font-bold ${payoutConfig?.configured ? "text-emerald-900" : "text-rose-800"}`}>{localize("Tài khoản khách sạn chi hoàn", "Hotel payout account")}</h3>
                    {payoutConfig?.configured ? (
                      <p className="mt-2 text-sm font-medium text-emerald-800">{[payoutConfig.bankName || payoutConfig.bankCode, payoutConfig.accountNumberMasked, payoutConfig.accountName].filter(Boolean).join(" · ")}</p>
                    ) : (
                      <p className="mt-2 text-sm font-medium text-rose-700">{localize("Chưa cấu hình tài khoản chi hoàn. Không thể xác nhận chuyển khoản.", "The payout account is not configured. The transfer cannot be confirmed.")}</p>
                    )}
                    <p className="mt-2 text-xs leading-5 text-[#66727C]">{localize("Staff thực hiện giao dịch bằng ứng dụng ngân hàng từ tài khoản chi hoàn. Hệ thống chỉ chốt refund sau khi nhận được xác nhận tiền ra khớp đúng mã và số tiền.", "Staff completes the transfer in the banking app from the payout account. The refund is finalized only after the outgoing transaction matches the exact code and amount.")}</p>
                  </section>

                  <section className="rounded-xl border border-sky-200 bg-sky-50 p-4" aria-live="polite">
                    <div className="flex items-start gap-3">
                      <span className="mt-1 inline-flex h-3 w-3 shrink-0 animate-pulse rounded-full bg-sky-600" />
                      <div>
                        <h3 className="text-sm font-bold text-sky-950">{localize("Đang chờ xác nhận tự động từ ngân hàng...", "Waiting for automatic bank confirmation...")}</h3>
                        <p className="mt-1 text-xs leading-5 text-sky-900">{localize("Bạn có thể đóng cửa sổ này. Hệ thống kiểm tra lại mỗi 5 giây và reservation vẫn giữ trạng thái chờ cho đến khi refund hoàn tất.", "You may close this window. The system checks every 5 seconds and the reservation remains pending until the refund is completed.")}</p>
                        {manualRefundDetails.fallbackAvailableAtUtc && !manualRefundDetails.fallbackAvailable && (
                          <p className="mt-2 text-xs font-semibold text-sky-950">{localize("Fallback thủ công mở từ", "Manual fallback available from")}: {formatDate(manualRefundDetails.fallbackAvailableAtUtc)}</p>
                        )}
                      </div>
                    </div>
                  </section>

                  {(manualRefundDetails.fallbackAvailable || isAdmin) && (
                    <section className="space-y-4 rounded-xl border border-amber-200 bg-amber-50 p-4" aria-labelledby="manual-fallback-title">
                      <div>
                        <h3 id="manual-fallback-title" className="text-sm font-bold text-amber-950">{localize("Xác nhận thủ công dự phòng", "Manual fallback confirmation")}</h3>
                        <p className="mt-1 text-xs leading-5 text-amber-900">{manualRefundDetails.fallbackAvailable
                          ? localize("Chỉ dùng khi đã chuyển thành công nhưng ngân hàng không gửi được xác nhận tự động. Ảnh minh chứng là tùy chọn.", "Use only after a successful transfer when automatic bank confirmation was not received. Proof is optional.")
                          : localize("Chưa hết thời gian chờ. Chỉ Admin được mở fallback sớm và thao tác này chưa hoàn tất refund.", "The waiting period has not expired. Only an Admin may open fallback early, and opening it does not complete the refund.")}</p>
                      </div>
                      <label className="block text-xs font-bold text-amber-950">
                        {localize("Lý do", "Reason")} *
                        <select value={manualFallbackReason} onChange={(event) => { setManualFallbackReason(event.target.value); setManualTransferError(""); }} className="mt-2 min-h-11 w-full rounded-lg border border-amber-300 bg-white px-3 text-sm font-semibold text-[#0F2A43] outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20">
                          <option value="">{localize("Chọn lý do", "Select a reason")}</option>
                          <option value="Đã chuyển khoản nhưng không nhận được webhook tự động">{localize("Đã chuyển nhưng chưa nhận xác nhận tự động", "Transferred but no automatic confirmation")}</option>
                          <option value="Chuyển từ tài khoản không liên kết hệ thống đối soát">{localize("Chuyển từ tài khoản không liên kết đối soát", "Transferred from an unlinked account")}</option>
                          <option value="Ngân hàng xác nhận thành công sau đối soát thủ công">{localize("Ngân hàng xác nhận sau đối soát thủ công", "Bank confirmed after manual review")}</option>
                        </select>
                      </label>

                      {manualRefundDetails.fallbackAvailable && (
                        <>
                          <DateTimeField tone="operations" label={`${localize("Thời điểm đã chuyển", "Transfer time")} *`} value={manualTransferredAt} max={formatDateTimeLocal(new Date())} onValueChange={(value) => { setManualTransferredAt(value); setManualTransferError(""); }} />
                          <div className="rounded-xl border border-sky-200 bg-white p-4">
                            <h4 className="text-sm font-bold text-sky-950">{localize("Minh chứng (không bắt buộc)", "Proof (optional)")}</h4>
                            <p className="mt-1 text-xs leading-5 text-sky-900">{localize("Có thể đính kèm ảnh/PDF để thuận tiện đối soát; không nhập mã giao dịch thủ công.", "Optionally attach an image/PDF for reconciliation; no manual bank transaction code is required.")}</p>
                            <ImageUploadField id="manual-refund-proof" folder="REFUND_PROOFS" refundId={manualRefundTarget.refundId} allowPdf value={manualTransferProof?.url || ""} label={localize("Ảnh biên lai", "Receipt image")} alt={localize("Xem trước biên lai hoàn tiền", "Refund receipt preview")} description={localize("JPEG, PNG, WebP hoặc PDF · tối đa 5 MB · không tải tệp có OTP/PIN.", "JPEG, PNG, WebP or PDF · up to 5 MB · never upload OTP/PIN details.")} disabled={isActionLoading || Boolean(manualTransferProof?.assetId)} className="mt-4" onUploaded={(image) => { setManualTransferProof(image); setManualTransferError(""); }} onUploadingChange={setIsManualProofUploading} />
                          </div>
                          <label className="flex cursor-pointer items-start gap-3 rounded-xl border border-amber-300 bg-white p-4 text-sm font-medium leading-6 text-amber-950">
                            <input type="checkbox" checked={manualTransferConfirmed} onChange={(event) => { setManualTransferConfirmed(event.target.checked); setManualTransferError(""); }} className="mt-1 h-4 w-4 shrink-0 accent-[#0F2A43]" />
                            <span>{localize("Tôi đã đối chiếu đúng người nhận, số tiền và xác nhận ngân hàng ghi nhận giao dịch thành công.", "I verified the recipient and amount, and confirmed that the bank recorded a successful transfer.")}</span>
                          </label>
                        </>
                      )}
                    </section>
                  )}
                </>
              ) : null}

              {manualTransferError && <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm font-medium text-rose-700">{manualTransferError}</p>}
            </div>

            <footer className="flex flex-col-reverse gap-3 border-t border-[#0F2A43]/10 px-6 py-4 sm:flex-row sm:justify-end">
              <button type="button" disabled={isActionLoading} onClick={closeManualRefundDetails} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43] hover:bg-[#F1F0EA] disabled:opacity-50">{localize("Đóng", "Close")}</button>
              {manualRefundDetails?.fallbackAvailable ? (
                <button type="submit" disabled={isActionLoading || isManualDetailsLoading || isManualProofUploading || !manualTransferConfirmed || !manualFallbackReason.trim() || !payoutConfig?.configured} className="min-h-11 rounded-lg bg-amber-700 px-5 text-sm font-bold text-white hover:bg-amber-800 disabled:cursor-not-allowed disabled:opacity-50">{isActionLoading ? localize("Đang ghi nhận...", "Recording...") : isManualProofUploading ? localize("Đang tải minh chứng...", "Uploading proof...") : localize("Xác nhận thủ công", "Confirm manually")}</button>
              ) : isAdmin && manualRefundDetails ? (
                <button type="button" onClick={() => void openManualFallbackEarly()} disabled={isActionLoading || !manualFallbackReason.trim()} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white hover:bg-[#091E30] disabled:cursor-not-allowed disabled:opacity-50">{isActionLoading ? localize("Đang mở...", "Opening...") : localize("Admin mở fallback sớm", "Admin open fallback early")}</button>
              ) : null}
            </footer>
          </form>
        </ViewportModal>
      )}

      {toast && <Toast message={toast.message} type={toast.type} onClose={() => setToast(null)} />}
      {invoice && <ReservationInvoiceModal invoice={invoice} onClose={() => setInvoice(null)} />}
    </div>
  );
}
