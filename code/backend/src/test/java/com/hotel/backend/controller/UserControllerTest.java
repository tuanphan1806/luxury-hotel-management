package com.hotel.backend.controller;

import com.hotel.backend.constant.UserType;
import com.hotel.backend.dto.request.UserUpdateRequest;
import com.hotel.backend.entity.User;
import com.hotel.backend.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private UserController userController;

    /**
     * Customer chỉ được cập nhật dữ liệu hồ sơ. Nếu client cố gửi type=ADMIN,
     * controller phải khôi phục role CUSTOMER trước khi gọi service.
     */
    @Test
    void customerCannotPromoteOwnRoleWhileUpdatingProfile() {
        User customer = User.builder().type(UserType.CUSTOMER).build();
        UserUpdateRequest request = validUpdateRequest(UserType.ADMIN);

        userController.updateUser(10L, request, customer);

        ArgumentCaptor<UserUpdateRequest> captor = ArgumentCaptor.forClass(UserUpdateRequest.class);
        verify(userService).update(captor.capture(), eq(10L));
        assertEquals(UserType.CUSTOMER, captor.getValue().getType());
    }

    /**
     * Admin vẫn được phép thay đổi role của tài khoản trong trang quản lý user.
     */
    @Test
    void adminCanChangeUserRoleFromManagementFlow() {
        User admin = User.builder().type(UserType.ADMIN).build();
        UserUpdateRequest request = validUpdateRequest(UserType.STAFF);

        userController.updateUser(10L, request, admin);

        ArgumentCaptor<UserUpdateRequest> captor = ArgumentCaptor.forClass(UserUpdateRequest.class);
        verify(userService).update(captor.capture(), eq(10L));
        assertEquals(UserType.STAFF, captor.getValue().getType());
    }

    private UserUpdateRequest validUpdateRequest(UserType requestedType) {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setFullName("Nguyễn Văn A");
        request.setUsername("customer-a");
        request.setEmail("customer-a@example.com");
        request.setPhone("0900000000");
        request.setType(requestedType);
        return request;
    }
}
