package com.hotel.backend.constant;

/** Compact internal accounts for operational reconciliation, not statutory accounting. */
public enum FinancialAccountCode {
    CASH_ON_HAND,
    BANK_SEPAY,
    CUSTOMER_DEPOSIT,
    REFUND_PAYABLE,
    ROOM_REVENUE,
    SERVICE_REVENUE,
    DISCOUNT,
    TAX_PAYABLE,
    UNRECONCILED_FUNDS
}
