package com.hotel.backend.constant;

/**
 * Defines how long inventory remains reserved after a priced package is bought.
 */
public enum InventoryProtectionMode {
    /**
     * Protect through the later of the customer's planned checkout and the
     * package entitlement.
     */
    PACKAGE_ENTITLEMENT,

    /**
     * Protect through the customer's planned checkout.
     * Kept for a future versioned policy; it is not the V2 default.
     */
    PLANNED_STAY
}
