export type StatisticsGranularity = "day" | "week" | "month";
export type FinanceWorkspaceView = "overview" | "cashier";

export type MoneyBreakdown = {
  cashIncome: number;
  transferIncome: number;
  totalIncome: number;
  cashRefund: number;
  transferRefund: number;
  totalRefund: number;
  netRevenue: number;
  paymentCount: number;
  refundCount: number;
};

export type MoneyPeriod = {
  period: string;
  periodEndExclusive: string;
  amounts: MoneyBreakdown;
};

export type MoneyReport = {
  from: string;
  to: string;
  timezone: string;
  granularity: StatisticsGranularity;
  totals: MoneyBreakdown;
  periods: MoneyPeriod[];
  unmatchedTransferCount: number;
  unmatchedTransferAmount: number;
  generatedAtUtc: string;
};

export type ReservationMoneyEntry = {
  reservationId: number;
  reservationCode: string;
  reservationStatus: string;
  amounts: MoneyBreakdown;
  lastMovementAtUtc: string;
  period?: string;
  periodEndExclusive?: string;
};

export type ReservationMoneyPage = {
  content: ReservationMoneyEntry[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type StatisticsKpi = {
  current: number;
  previous: number;
  changePercent: number | null;
};

export type StatisticsOverview = {
  range: { from: string; to: string; timezone: string };
  recognizedRevenue: StatisticsKpi;
  bookings: StatisticsKpi;
  occupancyRate: StatisticsKpi;
  adr: StatisticsKpi;
  revPar: StatisticsKpi;
  grossCashInflow: number;
  acceptedCashInflow: number;
  refundOutflow: number;
  netCashFlow: number;
  outstandingReceivables: number;
  customerDeposits: number;
  refundPayable: number;
  dataQuality: {
    paymentCompleteness: string;
    occupancyAccuracy: string;
    legacyUnreconciledPaymentCount: number;
    legacyUnreconciledPaymentAmount: number;
    unmatchedCashInEventCount: number;
    unmatchedCashInAmount: number;
    unclassifiedCashOutEventCount: number;
    unclassifiedCashOutAmount: number;
    warnings: string[];
  };
  generatedAtUtc: string;
};

export type RevenuePoint = {
  period: string;
  periodEndExclusive: string;
  recognizedRevenue: number;
  roomRevenue: number;
  addOnServiceRevenue: number;
  otherRevenue: number;
  additionalFee: number;
  lateCheckoutFee: number;
  discountAmount: number;
  taxAmount: number;
  invoiceCount: number;
  grossCashInflow: number;
  acceptedCashInflow: number;
  refundOutflow: number;
  netCashFlow: number;
  unmatchedCashInflow: number;
  unmatchedCashInEventCount: number;
  legacyUnreconciledPaymentAmount: number;
  legacyUnreconciledPaymentCount: number;
};

export type CashFlowPoint = {
  period: string;
  periodEndExclusive: string;
  grossCashInflow: number;
  acceptedPaymentAmount: number;
  unacceptedReceivedAmount: number;
  refundOutflow: number;
  netCashFlow: number;
  unmatchedCashInflow: number;
  unclassifiedCashOutflow: number;
  netBankMovement: number;
  paymentCount: number;
  unmatchedCashInEventCount: number;
  refundCount: number;
  unclassifiedCashOutEventCount: number;
  legacyUnreconciledPaymentAmount: number;
  legacyUnreconciledPaymentCount: number;
};

export type BookingPoint = {
  period: string;
  periodEndExclusive: string;
  total: number;
  paymentPending: number;
  draft: number;
  confirmed: number;
  cancellationPending: number;
  cancelled: number;
  checkedIn: number;
  checkedOut: number;
  noShow: number;
};

export type OccupancyPoint = {
  period: string;
  periodEndExclusive: string;
  soldRoomHours: number;
  availableRoomHours: number;
  roomNightEquivalents: number;
  availableRoomNightEquivalents: number;
  occupancyRate: number;
  allocatedRoomRevenue: number;
  adr: number;
  revPar: number;
  dataQuality: string;
};

export type RoomTypePerformance = {
  roomTypeId: number;
  roomTypeCode: string;
  roomTypeName: string;
  bookingCount: number;
  reservedRoomQuantity: number;
  soldRoomHours: number;
  availableRoomHours: number;
  occupancyRate: number;
  recognizedRoomRevenue: number;
  extraGuestRevenue: number;
  adr: number;
  revPar: number;
  dataQuality: string;
};

export type LedgerEntry = {
  entryKey: string;
  eventType:
    | "CASH_IN"
    | "UNMATCHED_CASH_IN"
    | "REFUND_OUT"
    | "UNCLASSIFIED_CASH_OUT"
    | "REVENUE_RECOGNIZED"
    | string;
  occurredAtUtc: string;
  occurredAtLocal: string;
  reservationCode?: string;
  reference?: string;
  provider?: string;
  status?: string;
  amount: number;
  direction: "IN" | "OUT" | "RECOGNIZED" | string;
  dataQuality: string;
  description: string;
};

export type LedgerPage = {
  content: LedgerEntry[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type ReservationRevenueEntry = {
  period: string;
  periodEndExclusive: string;
  reservationId: number;
  reservationCode: string;
  reservationStatus: string;
  plannedCheckIn: string;
  plannedCheckOut: string;
  actualCheckIn?: string;
  actualCheckOut?: string;
  invoiceNumber: string;
  issuedAtUtc: string;
  issuedAtLocal: string;
  settlementStatus?: string;
  pricingVersion?: string;
  roomCharge: number;
  extraGuestCharge: number;
  addOnServiceAmount: number;
  additionalFee: number;
  lateCheckoutFee: number;
  otherRevenue: number;
  discountAmount: number;
  taxAmount: number;
  recognizedRevenue: number;
  grossCashInflow: number;
  acceptedCashInflow: number;
  refundOutflow: number;
  netCashFlow: number;
  dataQuality: string;
};

export type ReservationRevenuePage = {
  content: ReservationRevenueEntry[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

const pad = (value: number) => String(value).padStart(2, "0");

export const toDateInputValue = (date: Date) =>
  `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;

export const statisticsPreset = (days: number, today = new Date()) => {
  const to = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  const from = new Date(to);
  from.setDate(from.getDate() - Math.max(1, days) + 1);
  return { from: toDateInputValue(from), to: toDateInputValue(to) };
};

export const monthToDatePreset = (today = new Date()) => ({
  from: toDateInputValue(new Date(today.getFullYear(), today.getMonth(), 1)),
  to: toDateInputValue(today),
});

export const suggestedStatisticsGranularity = (
  period: { from: string; to: string },
): StatisticsGranularity => {
  const from = new Date(`${period.from}T00:00:00`);
  const to = new Date(`${period.to}T00:00:00`);
  const days = Math.max(1, Math.round((to.getTime() - from.getTime()) / 86_400_000) + 1);
  if (days <= 45) return "day";
  if (days <= 240) return "week";
  return "month";
};

export const financeWorkspaceFromQuery = (value: string | null): FinanceWorkspaceView =>
  value === "cashier" || value === "operations"
    ? "cashier"
    : "overview";

export const apiData = <T>(response: { data?: { data?: T } }): T => {
  if (response.data?.data === undefined) {
    throw new Error("Phản hồi API thống kê không có dữ liệu");
  }
  return response.data.data;
};
