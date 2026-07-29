export interface BusinessDayClose {
  id?: number | null;
  businessDate: string;
  status?: "CLOSED" | null;
  closed: boolean;
  closeAllowed: boolean;
  blockers: string[];
  closedById?: number | null;
  closedByName?: string | null;
  closedByRole?: string | null;
  closedAtUtc?: string | null;
  journalEntryCount: number;
  totalDebit: number;
  totalCredit: number;
  paymentReceivedAmount: number;
  refundCompletedAmount: number;
  recognizedRevenueAmount: number;
  pendingRefundPayableAmount: number;
  cashVarianceAmount: number;
  openShiftCount: number;
  unresolvedProviderEventCount: number;
  unpostedPaymentCount: number;
  unpostedRefundCount: number;
  unpostedInvoiceCount: number;
  unreconciledFundsBalance: number;
  note?: string | null;
}

export interface FinancialJournalLine {
  lineNumber: number;
  accountCode: string;
  direction: "DEBIT" | "CREDIT";
  amount: number;
  description?: string | null;
}

export interface FinancialJournalEntry {
  id: number;
  entryNumber: string;
  businessDate: string;
  originalBusinessDate: string;
  occurredAtUtc: string;
  postedAtUtc: string;
  sourceType: string;
  sourceId: string;
  postingKind: string;
  currency: string;
  description: string;
  latePosting: boolean;
  totalDebit: number;
  totalCredit: number;
  reservationId?: number | null;
  reservationCode?: string | null;
  detail?: Record<string, unknown> | null;
  lines: FinancialJournalLine[];
}

export interface PageResult<T> {
  content: T[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export function businessDayProgress(day: BusinessDayClose) {
  const journalReady = day.unpostedPaymentCount === 0
    && day.unpostedRefundCount === 0
    && day.unpostedInvoiceCount === 0
    && Number(day.totalDebit) === Number(day.totalCredit);
  return {
    journalReady,
    shiftsReady: day.openShiftCount === 0,
    closeReady: !day.closed && day.closeAllowed,
    closed: day.closed,
  };
}

export const formatVnd = (value: number | null | undefined) => new Intl.NumberFormat("vi-VN", {
  style: "currency",
  currency: "VND",
  maximumFractionDigits: 0,
}).format(Number(value || 0));

export function defaultBusinessDate(now = new Date()): string {
  const previousDay = new Date(now.getTime() - 86_400_000);
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Ho_Chi_Minh",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(previousDay);
  const value = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${value.year}-${value.month}-${value.day}`;
}

const BLOCKER_LABELS: Record<string, string> = {
  ACCOUNTING_GO_LIVE_DATE_NOT_CONFIGURED: "Chưa cấu hình ngày bắt đầu sổ kế toán",
  BEFORE_ACCOUNTING_GO_LIVE_DATE: "Ngày được chọn nằm trước ngày bắt đầu sổ kế toán",
  DATE_NOT_FINISHED: "Ngày nghiệp vụ chưa kết thúc",
  OPEN_CASHIER_SHIFTS: "Còn ca thu ngân chưa đóng",
  UNRESOLVED_PROVIDER_EVENTS: "Còn giao dịch SePay chưa xử lý",
  UNPOSTED_PAYMENTS: "Có thanh toán chưa vào journal",
  UNPOSTED_REFUNDS: "Có hoàn tiền chưa vào journal",
  UNPOSTED_INVOICES: "Có hóa đơn chưa ghi nhận doanh thu",
  UNBALANCED_JOURNAL: "Có bút toán Nợ/Có không cân",
  UNRECONCILED_FUNDS: "Còn tiền ngân hàng chưa xác định",
};

export function blockerLabel(blocker: string): string {
  const separator = blocker.indexOf(":");
  const key = separator >= 0 ? blocker.slice(0, separator) : blocker;
  const detail = separator >= 0 ? blocker.slice(separator + 1) : "";
  const label = BLOCKER_LABELS[key] || key;
  if (!detail) return label;
  return key === "UNRECONCILED_FUNDS"
    ? `${label}: ${formatVnd(Number(detail))}`
    : `${label}: ${detail}`;
}

export const accountLabel = (account: string) => ({
  CASH_ON_HAND: "Tiền mặt tại quầy",
  BANK_SEPAY: "Tài khoản ngân hàng SePay",
  CUSTOMER_DEPOSIT: "Tiền khách đã thanh toán",
  REFUND_PAYABLE: "Nghĩa vụ hoàn tiền",
  ROOM_REVENUE: "Doanh thu phòng",
  SERVICE_REVENUE: "Doanh thu dịch vụ",
  DISCOUNT: "Chiết khấu",
  TAX_PAYABLE: "Thuế phải nộp",
  UNRECONCILED_FUNDS: "Tiền chưa xác định",
}[account] || account);

export const postingKindLabel = (kind: string) => ({
  PAYMENT_RECEIVED: "Nhận thanh toán",
  REFUND_COMPLETED: "Hoàn tiền hoàn tất",
  INVOICE_RECOGNIZED: "Ghi nhận doanh thu hóa đơn",
  PROVIDER_CASH_OBSERVED: "Ghi nhận dòng tiền SePay",
  PAYMENT_ALLOCATED: "Phân bổ tiền vào",
  REFUND_ALLOCATED: "Phân bổ tiền ra",
  REVERSAL: "Bút toán đảo",
}[kind] || kind);
