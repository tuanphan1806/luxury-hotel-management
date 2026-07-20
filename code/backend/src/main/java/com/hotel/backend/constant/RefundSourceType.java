package com.hotel.backend.constant;

/** Identifies which obligation supplied the money being refunded. */
public enum RefundSourceType {
    ACCEPTED_ALLOCATION,
    UNACCEPTED_PAYMENT,
    ADDITIONAL_TRANSFER,
    CHECKOUT_OVERPAYMENT,
    UNMATCHED_TRANSFER,
    MANUAL_RESERVATION,
    LEGACY;

    public String canonicalName() {
        return this == MANUAL_RESERVATION ? ACCEPTED_ALLOCATION.name() : name();
    }
}
