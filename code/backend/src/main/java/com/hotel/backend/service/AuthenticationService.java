package com.hotel.backend.service;

import com.hotel.backend.dto.request.SignInRequest;
import com.hotel.backend.dto.response.TokenResponse;
import com.hotel.backend.entity.User;

public interface AuthenticationService {
    TokenResponse getAccessToken(SignInRequest request);
    TokenResponse issueTokens(User user);
    TokenResponse getRefreshToken(String request);
    void logout(String accessToken, String refreshToken);
}
