package com.hotel.backend.service;

/**
 * Client-specific facade for authenticated profile and administration routes.
 */
public interface UserOperationsUseCases
        extends UserQueryUseCases, UserAdministrationUseCases, UserPasswordUseCases {
}
