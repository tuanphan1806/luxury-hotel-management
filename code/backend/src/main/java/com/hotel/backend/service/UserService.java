package com.hotel.backend.service;


import com.hotel.backend.dto.request.UserCreationRequest;
import com.hotel.backend.dto.request.UserCreationWithTypeRequest;
import com.hotel.backend.dto.request.UserPasswordRequest;
import com.hotel.backend.dto.request.UserUpdateRequest;
import com.hotel.backend.dto.request.AdminResetPasswordRequest;
import com.hotel.backend.dto.response.UserPageResponse;
import com.hotel.backend.dto.response.UserResponse;

public interface UserService {
    UserPageResponse findAll(String keyword,String sort, int page,int size);
    UserResponse findById(Long id);
    Long save(UserCreationRequest req);
    void update(UserUpdateRequest req,Long id);
    void changePassword(UserPasswordRequest req);
    void resetPasswordByAdmin(Long userId, AdminResetPasswordRequest request);
    void delete(Long id);
    Long verifyEmail(String secretCode);
    void resendVerification(String email);

    Long createUserWithType(UserCreationWithTypeRequest req);
}
