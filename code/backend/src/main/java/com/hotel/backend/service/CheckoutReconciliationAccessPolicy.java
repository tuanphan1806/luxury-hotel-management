package com.hotel.backend.service;

import com.hotel.backend.constant.UserType;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class CheckoutReconciliationAccessPolicy {

    public void requireOperationsUser(User user) {
        if (user == null || (user.getType() != UserType.STAFF && user.getType() != UserType.ADMIN)) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ STAFF/ADMIN được gửi yêu cầu đối soát");
        }
    }

    public void requireAdmin(User user) {
        if (user == null || user.getType() != UserType.ADMIN) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Chỉ ADMIN được xử lý yêu cầu đối soát");
        }
    }

    public String actorName(User user) {
        if (hasText(user.getFullName())) return user.getFullName().trim();
        if (hasText(user.getUsername())) return user.getUsername().trim();
        return "user:" + user.getId();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
