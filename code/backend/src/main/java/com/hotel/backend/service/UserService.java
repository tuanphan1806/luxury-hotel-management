package com.hotel.backend.service;

/**
 * Backward-compatible facade retained for the implementation and existing
 * internal callers.
 */
public interface UserService extends UserOperationsUseCases, AuthenticationUserUseCases {
}
