package com.hotel.backend.constant;

/** Tổng hợp các kênh hoàn của một reservation cho UI khách hàng. */
public enum RefundRoute {
    NONE,
    VNPAY_ORIGINAL,
    MANUAL_BANK_TRANSFER,
    CASH_AT_COUNTER,
    MIXED
}
