package com.hotel.backend.service;

import com.hotel.backend.dto.response.UserPageResponse;
import com.hotel.backend.dto.response.UserResponse;
import com.hotel.backend.entity.User;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * User response mapping extracted from account orchestration.
 */
public final class UserViewMapper {

    private UserViewMapper() {
    }

    public static UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .username(user.getUsername())
                .email(user.getEmail())
                .phone(user.getPhone())
                .address(user.getAddress())
                .type(user.getType())
                .status(user.getStatus())
                .imageUrl(user.getImageUrl())
                .build();
    }

    public static UserPageResponse toPage(int page, int size, Page<User> users) {
        List<UserResponse> userList = users.stream()
                .map(UserViewMapper::toResponse)
                .toList();

        UserPageResponse response = new UserPageResponse();
        response.setPageNumber(page);
        response.setPageSize(size);
        response.setTotalElements(users.getTotalElements());
        response.setTotalPages(users.getTotalPages());
        response.setUsers(userList);
        return response;
    }
}
