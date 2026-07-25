package com.hotel.backend.service;

/**
 * Backward-compatible facade retained for existing internal callers.
 */
public interface RoomService
        extends RoomCatalogUseCases, RoomAssignmentUseCases, RoomMaintenanceUseCases {
}
