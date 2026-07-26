package com.hotel.backend.service;

import com.hotel.backend.dto.response.UserPageResponse;
import com.hotel.backend.dto.response.UserResponse;

public interface UserQueryUseCases {

    UserPageResponse findAll(String keyword, String sort, int page, int size);

    UserResponse findById(Long id);
}
