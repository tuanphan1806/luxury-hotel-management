package com.hotel.backend.util;

/**
 * Shared helpers for payment records that are independent from any provider.
 */
public final class PaymentUtil {

    private PaymentUtil() {
    }

    public static String generateTxnRef(String scope) {
        return scope + "_" + System.currentTimeMillis();
    }
}
