package com.hotel.backend.service;

import com.hotel.backend.dto.request.UserCreationWithTypeRequest;
import com.hotel.backend.dto.request.UserUpdateRequest;

public interface UserAdministrationUseCases {

    Long createUserWithType(UserCreationWithTypeRequest request);

    void update(UserUpdateRequest request, Long id);

    void delete(Long id);
}
