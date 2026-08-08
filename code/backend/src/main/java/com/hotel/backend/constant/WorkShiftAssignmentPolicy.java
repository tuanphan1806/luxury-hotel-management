package com.hotel.backend.constant;

/**
 * Controls how an employee can obtain an assignment for an opened daily shift.
 * The value is snapshotted on the daily shift so later template changes never
 * rewrite an already published schedule.
 */
public enum WorkShiftAssignmentPolicy {
    ADMIN_ONLY,
    MANUAL_APPROVAL,
    AUTO_ASSIGN
}
