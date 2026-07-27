package com.hotel.backend.constant;

public enum PricingTransitionReason {
    INITIAL_QUOTE,
    HOURLY_WINDOW,
    OVERNIGHT_WINDOW,
    DAILY_DURATION,
    PRICE_CAP,
    EXTENSION,
    ACTUAL_CHECKOUT,
    ADMIN_APPROVED_ADJUSTMENT
}
