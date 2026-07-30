export type CashierShiftStatus = "OPEN" | "CLOSING" | "CLOSED" | "CANCELLED";
export type CashMovementDirection = "IN" | "OUT";
export type CashMovementType =
  | "OPENING_FLOAT"
  | "CASH_PAYMENT"
  | "CASH_REFUND"
  | "CASH_IN"
  | "CASH_OUT"
  | "ADJUSTMENT";

export interface CashMovement {
  id: number;
  cashierShiftId: number;
  movementType: CashMovementType;
  direction: CashMovementDirection;
  amount: number;
  sourceType: string;
  sourceId: string;
  reservationId?: number | null;
  reservationCode?: string | null;
  paymentTransactionId?: string | null;
  refundId?: string | null;
  createdById: number;
  createdByName: string;
  createdByRole: string;
  reason?: string | null;
  occurredAtUtc: string;
}

export interface CashierShift {
  id: number;
  shiftCode: string;
  businessDate: string;
  status: CashierShiftStatus;
  openedById: number;
  openedByName: string;
  openedByRole: string;
  openedAtUtc: string;
  closedById?: number | null;
  closedByName?: string | null;
  closedByRole?: string | null;
  closedAtUtc?: string | null;
  openingCashAmount: number;
  expectedCashAmount: number;
  countedCashAmount?: number | null;
  varianceAmount?: number | null;
  note?: string | null;
  closeNote?: string | null;
  movementCount: number;
  movements: CashMovement[];
  cashIncomeAmount: number;
  transferIncomeAmount: number;
  totalIncomeAmount: number;
  cashRefundAmount: number;
  transferRefundAmount: number;
  totalRefundAmount: number;
  netAmount: number;
}

export interface PageResult<T> {
  content: T[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export function formatVnd(value: number | null | undefined): string {
  return new Intl.NumberFormat("vi-VN", {
    style: "currency",
    currency: "VND",
    maximumFractionDigits: 0,
  }).format(Number(value || 0));
}

export function movementLabel(type: CashMovementType): string {
  return {
    OPENING_FLOAT: "Tiền đầu ca",
    CASH_PAYMENT: "Thu tiền đặt phòng",
    CASH_REFUND: "Hoàn tiền khách",
    CASH_IN: "Thu khác",
    CASH_OUT: "Chi khác",
    ADJUSTMENT: "Điều chỉnh",
  }[type];
}
