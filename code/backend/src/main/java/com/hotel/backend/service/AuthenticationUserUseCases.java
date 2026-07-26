package com.hotel.backend.service;

import com.hotel.backend.dto.request.UserCreationRequest;

public interface AuthenticationUserUseCases {

    Long save(UserCreationRequest request);

    Long verifyEmail(String secretCode);

    void resendVerification(String email);
}
