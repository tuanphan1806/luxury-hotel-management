package com.hotel.backend.service;

import com.hotel.backend.dto.request.AdminResetPasswordRequest;
import com.hotel.backend.dto.request.UserPasswordRequest;

public interface UserPasswordUseCases {

    void changePassword(UserPasswordRequest request);

    void resetPasswordByAdmin(Long userId, AdminResetPasswordRequest request);
}
