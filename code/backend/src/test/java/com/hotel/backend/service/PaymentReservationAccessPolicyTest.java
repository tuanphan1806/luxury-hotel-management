package com.hotel.backend.service;

import com.hotel.backend.constant.UserType;
import com.hotel.backend.entity.CustomerProfile;
import com.hotel.backend.entity.Reservation;
import com.hotel.backend.entity.User;
import com.hotel.backend.exception.AppException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentReservationAccessPolicyTest {

    private final PaymentReservationAccessPolicy policy = new PaymentReservationAccessPolicy();

    @Test
    void guestTokenGrantsAccessOnlyWhenItMatches() {
        Reservation reservation = Reservation.builder().guestToken("guest-token").build();

        assertDoesNotThrow(() ->
                policy.ensureCanAccessReservation(null, reservation, "guest-token"));
        assertThrows(AppException.class, () ->
                policy.ensureCanAccessReservation(null, reservation, "wrong-token"));
    }

    @Test
    void staffAndAdminCanAccessOperationalReservations() {
        Reservation reservation = Reservation.builder().build();
        User staff = User.builder().type(UserType.STAFF).build();
        User admin = User.builder().type(UserType.ADMIN).build();

        assertDoesNotThrow(() -> policy.ensureCanAccessReservation(staff, reservation));
        assertDoesNotThrow(() -> policy.ensureCanAccessReservation(admin, reservation));
    }

    @Test
    void customerCanOnlyAccessReservationLinkedToTheSameUser() {
        User owner = User.builder().type(UserType.CUSTOMER).build();
        owner.setId(31L);
        User otherCustomer = User.builder().type(UserType.CUSTOMER).build();
        otherCustomer.setId(32L);
        CustomerProfile profile = CustomerProfile.builder().linkedUser(owner).fullName("Owner").build();
        Reservation reservation = Reservation.builder().customerProfile(profile).build();

        assertDoesNotThrow(() -> policy.ensureCanAccessReservation(owner, reservation));
        assertThrows(AppException.class,
                () -> policy.ensureCanAccessReservation(otherCustomer, reservation));
    }
}
