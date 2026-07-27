package com.hotel.backend.constant;

/**
 * Selects the pricing contract used by a reservation.
 *
 * <p>Existing reservations remain on {@link #LEGACY_V1}; the package engine is
 * opt-in for newly created reservations only.</p>
 */
public enum PricingAlgorithmVersion {
    LEGACY_V1,
    MOTEL_PACKAGE_V2
}
