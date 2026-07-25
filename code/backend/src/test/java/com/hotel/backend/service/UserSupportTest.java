package com.hotel.backend.service;

import com.hotel.backend.constant.UserStatus;
import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.response.UserResponse;
import com.hotel.backend.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserSupportTest {

    @Test
    void preservesIdentityNormalizationRules() {
        assertEquals("", UserIdentityNormalizer.username(null));
        assertEquals("Admin", UserIdentityNormalizer.username("  Admin "));
        assertEquals("", UserIdentityNormalizer.email(null));
        assertEquals("guest@example.com", UserIdentityNormalizer.email(" Guest@Example.COM "));
    }

    @Test
    void preservesPagingAndSortRules() {
        Pageable pageable = UserPageableFactory.create("username:desc", 3, 25);

        assertEquals(2, pageable.getPageNumber());
        assertEquals(25, pageable.getPageSize());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("username").getDirection());
    }

    @Test
    void mapsTheExistingUserResponseFields() {
        User user = User.builder()
                .fullName("Nguyễn Văn A")
                .username("nguyenvana")
                .email("a@example.com")
                .phone("0900000000")
                .address("Hà Nội")
                .type(UserType.CUSTOMER)
                .status(UserStatus.ACTIVE)
                .imageUrl("https://example.com/avatar.jpg")
                .build();
        user.setId(15L);

        UserResponse response = UserViewMapper.toResponse(user);

        assertEquals(15L, response.getId());
        assertEquals("nguyenvana", response.getUsername());
        assertEquals(UserType.CUSTOMER, response.getType());
        assertEquals(UserStatus.ACTIVE, response.getStatus());
    }
}
