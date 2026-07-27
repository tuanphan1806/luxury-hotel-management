package com.hotel.backend.constant;

public enum AddOnPricingUnit {
    PER_GUEST,
    PER_PACKAGE_CYCLE,
    /**
     * Compatibility alias for service-order snapshots created before V22.
     * New catalog entries use {@link #PER_PACKAGE_CYCLE}.
     */
    @Deprecated
    PER_NIGHT,
    PER_ITEM,
    PER_ORDER,
    PER_USE
}
