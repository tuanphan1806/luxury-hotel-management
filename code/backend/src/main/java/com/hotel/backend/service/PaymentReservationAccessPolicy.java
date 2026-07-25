package com.hotel.backend.service;

import com.hotel.backend.constant.UserType;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import com.hotel.backend.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PaymentReservationAccessPolicy {

    public void ensureCanAccessReservation(User currentUser, Reservation reservation) {
        ensureCanAccessReservation(currentUser, reservation, null);
    }

    public void ensureCanAccessReservation(
            User currentUser,
            Reservation reservation,
            String guestToken) {
        if (currentUser == null) {
            if (hasText(guestToken) && guestToken.equals(reservation.getGuestToken())) {
                return;
            }
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Bạn cần đăng nhập hoặc cung cấp mã khách hợp lệ để thực hiện thao tác này");
        }
        if (List.of(UserType.ADMIN, UserType.STAFF).contains(currentUser.getType())) {
            return;
        }
        if (reservation.getCustomerProfile() == null
                || reservation.getCustomerProfile().getLinkedUser() == null
                || !reservation.getCustomerProfile().getLinkedUser().getId().equals(currentUser.getId())) {
            throw new AppException(ErrorCode.INVALID_REQUEST,
                    "Bạn không có quyền thao tác với đặt phòng này");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
