package com.hotel.backend.constant;

/**
 * High-level view used by the audit dashboard. This value is derived from the
 * canonical action and is not persisted, so adding the filter does not rewrite
 * append-only audit history.
 */
public enum AuditScope {
    OPERATION,
    MANAGEMENT
}
