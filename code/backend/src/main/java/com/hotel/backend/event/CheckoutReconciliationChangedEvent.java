package com.hotel.backend.event;

/**
 * Published after a legitimate financial operation can change checkout
 * reconciliation. The listener never edits ledger values; it only closes a
 * pending exception request when the canonical reconciliation is MATCHED.
 */
public record CheckoutReconciliationChangedEvent(Long reservationId, String source) {
}
