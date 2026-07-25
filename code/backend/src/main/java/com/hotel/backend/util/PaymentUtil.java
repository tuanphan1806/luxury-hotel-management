package com.hotel.backend.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Shared helpers for payment records that are independent from any provider.
 */
public final class PaymentUtil {

    private PaymentUtil() {
    }

    /**
     * Preserves the existing client-IP resolution used by payment and walk-in
     * flows without coupling them to a payment provider.
     */
    public static String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    public static String generateTxnRef(String scope) {
        return scope + "_" + System.currentTimeMillis();
    }
}
